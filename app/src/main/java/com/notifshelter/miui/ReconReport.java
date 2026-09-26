package com.notifshelter.miui;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 应用侧：接收 SystemUI 回传的探测报告分片 → 重组 → 落盘到应用私有目录 → 通知界面刷新。
 *
 * 报告落在应用自己的 filesDir 里，所以「界面显示」和「退出应用后再进来」都不需要任何权限，
 * 也不受 Android 11+ 分区存储的影响。
 */
public final class ReconReport {

    private static final String FILE_NAME = "recon.txt";

    private static final Map<Integer, String> sChunks = new HashMap<>();
    private static int sTotal = -1;
    private static volatile String sCache;
    private static volatile String sStatus = "";
    private static volatile int sReceived;

    /** 界面监听，由 Activity 在 onResume/onPause 注册与反注册。 */
    public interface Listener {
        void onReportChanged();
    }

    private static final CopyOnWriteArrayList<Listener> sListeners = new CopyOnWriteArrayList<>();

    private ReconReport() {
    }

    public static void addListener(Listener l) {
        sListeners.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        sListeners.remove(l);
    }

    private static void notifyChanged() {
        for (Listener l : sListeners) {
            try {
                l.onReportChanged();
            } catch (Throwable ignored) {
                // 单个监听器出错不影响其他
            }
        }
    }

    /** 收到一个分片，或一条状态说明。 */
    public static void accept(Context ctx, String state, int total, int index,
                              String text, long time, String path) {
        if (Keys.RECON_STATE_OK.equals(state) && total > 0) {
            String assembled = null;
            synchronized (sChunks) {
                if (sTotal != total) {
                    // 分片总数变了，说明这是新一轮回传，丢弃上一轮的残片
                    sChunks.clear();
                    sTotal = total;
                }
                sChunks.put(index, text == null ? "" : text);
                sReceived = sChunks.size();
                if (sReceived >= total) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < total; i++) {
                        String c = sChunks.get(i);
                        if (c != null) {
                            sb.append(c);
                        }
                    }
                    sChunks.clear();
                    sTotal = -1;
                    sReceived = 0;
                    assembled = sb.toString();
                }
            }
            if (assembled == null) {
                int done = sReceived;
                int all = sTotal;
                sStatus = "正在接收报告分片 " + done + " / " + (all < 0 ? total : all);
            } else {
                save(ctx, assembled, time, path);
            }
        } else {
            sStatus = (text == null || text.isEmpty()) ? "SystemUI 未回传报告" : text;
        }
        notifyChanged();
    }

    /** 界面点「拉取报告」时调用，先把状态改成等待中。 */
    public static void markRequested() {
        sStatus = "已向系统界面发出请求，等待回传…";
        notifyChanged();
    }

    public static String status() {
        return sStatus;
    }

    private static void save(Context ctx, String text, long time, String path) {
        sCache = text;
        try {
            File f = new File(ctx.getFilesDir(), FILE_NAME);
            try (FileOutputStream os = new FileOutputStream(f, false)) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            XLog.e("报告写入应用私有目录失败", t);
        }
        SharedPreferences sp = ctx.getSharedPreferences(Keys.PREF_FILE, Context.MODE_PRIVATE);
        sp.edit()
                .putLong(Keys.LAST_RECON_TIME, time)
                .putString(Keys.LAST_RECON_PATH, path == null ? "-" : path)
                .putString(Keys.LAST_RECON_SUMMARY, describe(text))
                .apply();
        sStatus = "已收到报告";
        XLog.i("已保存系统界面回传的报告：" + describe(text));
    }

    /** 应用私有目录里的报告正文；没有则返回空串。 */
    public static String text(Context ctx) {
        String cached = sCache;
        if (cached != null) {
            return cached;
        }
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) {
            return "";
        }
        try {
            String s = read(f);
            sCache = s;
            return s;
        } catch (Throwable t) {
            XLog.e("读取本地报告失败", t);
            return "";
        }
    }

    /** 形如「共 1234 行 / 56789 字符」。 */
    public static String describe(String text) {
        if (text == null) {
            return "空";
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return "共 " + lines + " 行 / " + text.length() + " 字符";
    }

    private static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}