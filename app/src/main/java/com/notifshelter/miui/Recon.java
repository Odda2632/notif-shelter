package com.notifshelter.miui;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SystemUI 进程侧的探测：把类名候选列表和方法签名写到报告文件 + logcat。
 *
 * 为什么必须在 SystemUI 进程里做「方法签名」这一层：
 * 应用进程的 ClassLoader 看不到 SystemUI 的类，只有 hook 进 SystemUI 之后才能反射出
 * 真实的方法签名，而方法签名正是配置 hook 所必需的输入。
 */
public final class Recon {

    private static final int DEFAULT_MAX_PER_GROUP = 300;
    private static final int MAX_TARGETS = 60;
    private static final int METHOD_DUMP_DELAY_MS = 12_000;

    private Recon() {
    }

    /** 延迟执行，等 SystemUI 把自己的类都加载起来。 */
    public static void schedule(final ClassLoader cl, final Prefs prefs) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                File report = null;
                String text;
                try {
                    // 先开捕获：三处落盘路径可能全写不进去，内存副本是兜底
                    XLog.beginCapture();
                    report = Recon.run(cl, prefs);
                } catch (Throwable t) {
                    XLog.e("探测执行失败", t);
                } finally {
                    XLog.closeReport();
                    text = XLog.endCapture();
                }
                try {
                    Context ctx = currentContext();
                    if (ctx == null) {
                        XLog.w("拿不到 SystemUI Context，报告无法回传给应用");
                        return;
                    }
                    ReportBridge.publish(ctx, text, report == null ? "-" : report.getAbsolutePath());
                } catch (Throwable t) {
                    XLog.e("回传探测报告失败", t);
                }
            }
        }, METHOD_DUMP_DELAY_MS);
        XLog.important("探测已排队，" + (METHOD_DUMP_DELAY_MS / 1000) + " 秒后开始");
    }

    /** 执行探测，返回实际写入的报告文件（可能为 null，表示三处路径都写不进去）。 */
    public static File run(ClassLoader cl, Prefs prefs) {
        Context ctx = currentContext();
        if (ctx == null) {
            XLog.e("拿不到 SystemUI Context，探测中止", new IllegalStateException("no context"));
            return null;
        }

        File report = openReport(ctx);
        XLog.important("探测报告写入: " + (report == null ? "(仅内存与 logcat)" : report.getAbsolutePath()));

        XLog.i("=== 通知收纳 · SystemUI 探测报告 ===");
        XLog.i("Android SDK = " + android.os.Build.VERSION.SDK_INT
                + " (" + android.os.Build.VERSION.RELEASE + ")  "
                + android.os.Build.BRAND + " / " + android.os.Build.MODEL);
        XLog.i("fingerprint = " + android.os.Build.FINGERPRINT);
        XLog.i("SystemUI process = " + android.app.Application.getProcessName());

        // 1) 枚举类名
        Set<String> descriptors = new LinkedHashSet<>();
        List<String> apks = systemUiApks(ctx);
        for (String p : apks) {
            XLog.i("扫描 APK: " + p);
            DexScanner.scanFile(new File(p), descriptors);
        }
        for (String extra : split(prefs.string("recon_extra_paths", ""))) {
            XLog.i("扫描附加路径: " + extra);
            DexScanner.scanFile(new File(extra), descriptors);
        }
        Set<String> dynamic = DexScanner.scanClassLoader(cl);
        for (String name : dynamic) {
            descriptors.add("L" + name.replace('.', '/') + ";");
        }

        Set<String> binaries = new LinkedHashSet<>();
        for (String d : descriptors) {
            String b = ClassCatalog.toBinaryName(d);
            if (b != null && !b.isEmpty()) {
                binaries.add(b);
            }
        }
        XLog.i("枚举到类总数: " + binaries.size());

        // 2) 按关键词分组
        int maxPerGroup = prefs.intValue(Keys.RECON_MAX_CLASSES, DEFAULT_MAX_PER_GROUP);
        Map<String, List<String>> buckets = ClassCatalog.bucket(binaries, maxPerGroup);
        int candidateTotal = 0;
        for (Map.Entry<String, List<String>> e : buckets.entrySet()) {
            List<String> list = e.getValue();
            candidateTotal += list.size();
            XLog.i("");
            XLog.i("--- " + e.getKey() + "  命中 " + list.size() + " 个 ---");
            for (String b : list) {
                XLog.i("  " + b);
            }
        }
        XLog.i("");
        XLog.i("候选类合计: " + candidateTotal + "（上限 " + maxPerGroup + "/组）");

        // 3) 方法签名 dump
        boolean dump = prefs.bool(Keys.RECON_DUMP_METHODS, true);
        Set<String> targets = new LinkedHashSet<>();
        try {
            Set<String> configured = prefs.stringSet(Keys.RECON_TARGETS);
            if (configured != null) {
                targets.addAll(configured);
            }
        } catch (Throwable t) {
            XLog.w("读取 recon_targets 失败：" + XLog.describe(t));
        }

        if (dump && targets.isEmpty()) {
            // 没指定目标时，自动 dump 组①的候选（最可能藏着折叠判定）
            List<String> auto = buckets.get(ClassCatalog.GROUPS[0].title);
            if (auto != null) {
                for (String b : auto) {
                    if (targets.size() >= MAX_TARGETS) {
                        break;
                    }
                    if (!b.startsWith("…")) {
                        targets.add(b);
                    }
                }
            }
            XLog.i("未配置 recon_targets，自动 dump 组① 的前 " + targets.size() + " 个类");
        }

        if (dump) {
            int i = 0;
            for (String t : targets) {
                if (i++ >= MAX_TARGETS) {
                    XLog.w("目标类超过 " + MAX_TARGETS + " 个，已截断");
                    break;
                }
                XLog.i("");
                XLog.i("<<< 方法签名: " + t);
                for (String line : ClassCatalog.dumpMethods(t, cl)) {
                    XLog.i(line);
                }
            }
        } else {
            XLog.i("recon_dump_methods = false，跳过方法签名 dump");
        }

        XLog.i("");
        XLog.important("=== 报告结束，共 " + XLog.lineCount() + " 行 ===");
        return report;
    }

    private static Context currentContext() {
        try {
            // Xposed API 提供的当前 Application，避免直接碰 ActivityThread 隐藏 API
            return AndroidAppHelper.currentApplication();
        } catch (Throwable t) {
            XLog.w("currentApplication 不可用：" + XLog.describe(t));
            return null;
        }
    }

    private static List<String> systemUiApks(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            ApplicationInfo ai = ctx.getPackageManager()
                    .getApplicationInfo(Keys.SYSTEMUI_PKG, 0);
            if (ai.sourceDir != null) {
                out.add(ai.sourceDir);
            }
            if (ai.splitSourceDirs != null) {
                for (String s : ai.splitSourceDirs) {
                    out.add(s);
                }
            }
        } catch (Throwable t) {
            XLog.e("获取 SystemUI APK 路径失败", t);
        }
        return out;
    }

    private static File openReport(Context ctx) {
        List<File> candidates = new ArrayList<>();
        candidates.add(new File("/data/local/tmp/miui_shelter/recon.txt"));
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                candidates.add(new File(ext, "recon.txt"));
            }
        } catch (Throwable ignored) {
            // ignore
        }
        candidates.add(new File(ctx.getFilesDir(), "recon.txt"));

        for (File f : candidates) {
            if (XLog.openReport(f)) {
                return f;
            }
        }
        XLog.w("所有报告路径都不可写，仅输出到 logcat");
        return null;
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.trim().isEmpty()) {
            return out;
        }
        for (String part : s.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }
}