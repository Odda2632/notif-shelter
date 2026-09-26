package com.notifshelter.miui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从 DEX 里枚举类名。
 *
 * 不依赖任何隐藏 API：直接读 SystemUI.apk 里的 classes*.dex，按字节扫描 class descriptor
 * （形如 {@code Lcom/android/systemui/foo/Bar;}）。DEX 的字符串表是连续存放的 MUTF-8，
 * 因此“找到 'L' 开头、';' 结尾、中间含 '/' 的可打印标识符串”就能可靠地捞出类名。
 *
 * 这样做的原因：HyperOS 4 / Android 17 上无法预知混淆后的真实类名，必须先在设备上把
 * 候选类名枚举出来，再去定位收纳逻辑所在的方法。
 */
public final class DexScanner {

    private static final int CHUNK = 4 << 20;   // 4MB
    private static final int OVERLAP = 1024;    // 跨块描述符的兜底重叠
    private static final int MAX_DESC = 512;

    private DexScanner() {
    }

    /** 扫描一个 .apk / .dex / .jar 文件。 */
    public static void scanFile(File f, Set<String> out) {
        if (f == null || !f.isFile()) {
            return;
        }
        String name = f.getName().toLowerCase();
        if (name.endsWith(".apk") || name.endsWith(".jar") || name.endsWith(".zip")) {
            ZipFile zf = null;
            try {
                zf = new ZipFile(f);
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String en2 = e.getName();
                    if (!en2.endsWith(".dex")) {
                        continue;
                    }
                    InputStream in = null;
                    try {
                        in = zf.getInputStream(e);
                        scanStream(in, out);
                    } catch (Throwable t) {
                        XLog.w("scan entry failed: " + en2 + " " + XLog.describe(t));
                    } finally {
                        closeQuietly(in);
                    }
                }
            } catch (Throwable t) {
                XLog.w("scan apk failed: " + f.getAbsolutePath() + " " + XLog.describe(t));
            } finally {
                closeQuietly(zf);
            }
        } else if (name.endsWith(".dex")) {
            InputStream in = null;
            try {
                in = new java.io.FileInputStream(f);
                scanStream(in, out);
            } catch (Throwable t) {
                XLog.w("scan dex failed: " + f.getAbsolutePath() + " " + XLog.describe(t));
            } finally {
                closeQuietly(in);
            }
        }
    }

    /**
     * 通过 ClassLoader 内部结构枚举已加载的 dex（作为补充，能覆盖动态加载的 dex）。
     * 依赖 dexFile.entries() 隐藏 API，失败时静默返回。
     */
    public static Set<String> scanClassLoader(ClassLoader cl) {
        Set<String> out = new LinkedHashSet<>();
        try {
            Object pathList = field(cl, "pathList");
            Object[] elements = (Object[]) field(pathList, "dexElements");
            if (elements == null) {
                return out;
            }
            for (Object el : elements) {
                Object dexFile = tryField(el, "dexFile");
                if (dexFile == null) {
                    continue;
                }
                java.lang.reflect.Method m = dexFile.getClass().getDeclaredMethod("entries");
                m.setAccessible(true);
                Object res = m.invoke(dexFile);
                if (res instanceof Enumeration<?>) {
                    Enumeration<?> en = (Enumeration<?>) res;
                    while (en.hasMoreElements()) {
                        Object o = en.nextElement();
                        if (o instanceof String) {
                            out.add((String) o);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            XLog.v("classloader dex 枚举不可用（可忽略）：" + XLog.describe(t));
        }
        return out;
    }

    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Object tryField(Object target, String name) {
        try {
            return field(target, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void scanStream(InputStream in, Set<String> out) throws IOException {
        byte[] buf = new byte[CHUNK + OVERLAP];
        int fill = 0;
        boolean eof = false;
        while (!eof) {
            int got = 0;
            while (fill + got < buf.length) {
                int r = in.read(buf, fill + got, buf.length - fill - got);
                if (r < 0) {
                    eof = true;
                    break;
                }
                got += r;
            }
            int total = fill + got;
            if (total == 0) {
                break;
            }
            // 非最后一块时，末尾 OVERLAP 字节可能截断了某个 descriptor，留给下一轮重扫
            int safeEnd = eof ? total : Math.max(0, total - OVERLAP);
            extract(buf, safeEnd, out);
            if (!eof) {
                int keep = Math.min(OVERLAP, total);
                System.arraycopy(buf, total - keep, buf, 0, keep);
                fill = keep;
            } else {
                fill = total;
            }
        }
    }

    private static void extract(byte[] buf, int end, Set<String> out) {
        int i = 0;
        while (i < end) {
            if (buf[i] != 'L') {
                i++;
                continue;
            }
            // 要求 'L' 前一个字节不是标识符字符，降低误命中
            if (i > 0 && isIdent(buf[i - 1])) {
                i++;
                continue;
            }
            int j = i + 1;
            int slash = 0;
            while (j < end && isIdent(buf[j])) {
                if (buf[j] == '/') {
                    slash++;
                }
                j++;
            }
            if (j < end && buf[j] == ';' && slash >= 1 && (j - i) <= MAX_DESC) {
                out.add(new String(buf, i, j - i + 1, StandardCharsets.US_ASCII));
                i = j + 1;
            } else {
                i++;
            }
        }
    }

    private static boolean isIdent(byte b) {
        return (b >= 'A' && b <= 'Z')
                || (b >= 'a' && b <= 'z')
                || (b >= '0' && b <= '9')
                || b == '_' || b == '$' || b == '/';
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }
}