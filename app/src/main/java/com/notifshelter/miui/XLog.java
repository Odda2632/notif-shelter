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
 * 同时写三处：
 *  1) logcat，tag = {@link #TAG}，方便 `adb logcat -s MIUI-Shelter` 直接看（全部级别）；
 *  2) 框架日志（XposedBridge.log），在 LSPosed 管理器的日志页可见
 *     —— 只送 WARN 以上和 {@link #important(String)} 标记的生命周期节点，
 *     因为报告正文有上千行，全送过去会把 LSPosed 日志刷爆；
 *  3) 探测模式下的报告文件，文件名由 {@link #openReport(File)} 指定。
 *
 * 探测期间还会额外留一份内存副本（{@link #beginCapture()}），落盘失败时靠它回传。
 */
public final class XLog {

    public static final String TAG = "MIUI-Shelter";

    private static final Object CAPTURE_LOCK = new Object();

    private static volatile boolean sVerbose = true;
    private static volatile int sLines;
    private static BufferedWriter sReport;

    /** 探测期间的内存副本，落盘全部失败时靠它回传。 */
    private static volatile boolean sCapturing;
    private static final StringBuilder sCapture = new StringBuilder();

    private XLog() {
    }

    /** 已输出的日志行数，用于报告结尾统计。 */
    public static int lineCount() {
        return sLines;
    }

    /**
     * 开始捕获报告正文。
     * 注意：不能只看「文件是否打开成功」——三处路径可能全写不进去，
     * 那种情况下内存副本是唯一的获取途径。
     */
    public static void beginCapture() {
        synchronized (CAPTURE_LOCK) {
            sCapture.setLength(0);
            sCapturing = true;
        }
    }

    /** 结束捕获并返回正文。 */
    public static String endCapture() {
        synchronized (CAPTURE_LOCK) {
            sCapturing = false;
            return sCapture.toString();
        }
    }

    public static void setVerbose(boolean verbose) {
        sVerbose = verbose;
    }

    public static void i(String msg) {
        out(Log.INFO, "I", msg, false);
    }

    /**
     * 生命周期节点日志：级别仍是 INFO，但强制镜像进 XposedBridge.log，
     * 于是会出现在 LSPosed 管理器的「日志」页里——手机上不用终端、不用 adb 就能自查。
     *
     * 只给「模块是否加载 / 读到什么模式 / 探测是否排队 / 接收器是否注册 / 报告是否回传」
     * 这十来个节点用。报告正文绝不能走这里。
     */
    public static void important(String msg) {
        out(Log.INFO, "I", msg, true);
    }

    public static void v(String msg) {
        if (sVerbose) {
            out(Log.DEBUG, "V", msg, false);
        }
    }

    public static void w(String msg) {
        out(Log.WARN, "W", msg, false);
    }

    public static void e(String msg, Throwable t) {
        out(Log.ERROR, "E", msg + " -> " + describe(t), false);
    }

    public static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        return t.getClass().getName() + ": " + t.getMessage();
    }

    private static void out(int priority, String level, String msg, boolean mirror) {
        sLines++;
        try {
            Log.println(priority, TAG, msg);
        } catch (Throwable ignored) {
            // 某些进程早期 Log 不可用
        }
        if (mirror || priority >= Log.WARN) {
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
        if (sCapturing) {
            synchronized (CAPTURE_LOCK) {
                sCapture.append(level).append(' ').append(msg).append('\n');
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