package com.notifshelter.miui;

import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedBridge;

/**
 * 统一日志出口。
 *
 * 同时写两处：
 *  1) logcat，tag = {@link #TAG}，方便 `adb logcat -s MIUI-Shelter` 直接看；
 *  2) 框架日志（XposedBridge.log），在 LSPosed 管理器的日志页可见。
 *
 * 探测模式还会把完整报告写入文件，文件名由 {@link #openReport(File)} 指定。
 */
public final class XLog {

    public static final String TAG = "MIUI-Shelter";

    private static volatile boolean sVerbose = true;
    private static volatile int sLines;
    private static BufferedWriter sReport;

    private XLog() {
    }

    /** 已输出的日志行数，用于报告结尾统计。 */
    public static int lineCount() {
        return sLines;
    }

    public static void setVerbose(boolean verbose) {
        sVerbose = verbose;
    }

    public static void i(String msg) {
        out(Log.INFO, "I", msg);
    }

    public static void v(String msg) {
        if (sVerbose) {
            out(Log.DEBUG, "V", msg);
        }
    }

    public static void w(String msg) {
        out(Log.WARN, "W", msg);
    }

    public static void e(String msg, Throwable t) {
        out(Log.ERROR, "E", msg + " -> " + describe(t));
    }

    public static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        return t.getClass().getName() + ": " + t.getMessage();
    }

    private static void out(int priority, String level, String msg) {
        sLines++;
        try {
            Log.println(priority, TAG, msg);
        } catch (Throwable ignored) {
            // 某些进程早期 Log 不可用
        }
        if (priority >= Log.WARN) {
            try {
                XposedBridge.log(TAG + " [" + level + "] " + msg);
            } catch (Throwable ignored) {
                // 非 Xposed 环境（应用进程）下没有 XposedBridge
            }
        }
        BufferedWriter w = sReport;
        if (w != null) {
            try {
                w.write(level);
                w.write(' ');
                w.write(msg);
                w.write('\n');
            } catch (Throwable ignored) {
                // 报告写失败不影响主流程
            }
        }
    }

    /** 打开报告文件，后续所有日志都会同步写入。 */
    public static synchronized boolean openReport(File f) {
        closeReport();
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            sReport = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(f, false), StandardCharsets.UTF_8));
            return true;
        } catch (Throwable t) {
            sReport = null;
            return false;
        }
    }

    public static synchronized void closeReport() {
        if (sReport != null) {
            try {
                sReport.flush();
                sReport.close();
            } catch (Throwable ignored) {
                // ignore
            }
            sReport = null;
        }
    }
}