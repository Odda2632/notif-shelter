package com.notifshelter.miui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * SystemUI 进程侧：把探测报告回传给模块应用。
 *
 * 为什么走广播而不是让应用去读文件：
 *  1) SystemUI 以 uid 1000 运行，而 /data/local/tmp 是 shell:shell 0771，
 *     对 uid 1000 只有执行权没有写权，第一个候选路径必然失败；
 *  2) 剩下两个候选（SystemUI 的外部目录 / 私有目录）在 Android 11+ 下，
 *     普通应用即使拿到「所有文件访问」也读不到别的包的 Android/data 和 /data/data。
 * 所以广播是唯一不依赖 root 的可靠通道，而且报告正文走的是内存副本，
 * 即使三处落盘全失败也照样能拿到。
 *
 * 完整报告有几十万字符，单个广播塞进 Intent 会触发 TransactionTooLargeException
 * （Binder 事务上限 1MB），所以按 {@link #CHUNK_CHARS} 切片发送，应用侧按 index 重组。
 */
public final class ReportBridge {

    /** 单个分片的最大字符数。UTF-8 下中文占 3 字节，这里留足余量。 */
    private static final int CHUNK_CHARS = 60_000;

    private static volatile String sText;
    private static volatile String sPath = "-";
    private static volatile long sTime;
    private static volatile Context sCtx;

    private ReportBridge() {
    }

    /** 注册「报告请求」接收器。必须在带 Looper 的线程（主线程）调用。 */
    public static void install(Context ctx) {
        try {
            sCtx = ctx.getApplicationContext();
            IntentFilter filter = new IntentFilter(Keys.ACTION_RECON_REQUEST);
            // targetSdk 33+ 起动态注册必须显式声明是否导出；SystemUI 的 targetSdk 很高
            if (Build.VERSION.SDK_INT >= 33) {
                sCtx.registerReceiver(sReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                sCtx.registerReceiver(sReceiver, filter);
            }
            XLog.i("报告回传接收器已注册");
        } catch (Throwable t) {
            XLog.e("注册报告回传接收器失败", t);
        }
    }

    private static final BroadcastReceiver sReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (intent == null || !Keys.ACTION_RECON_REQUEST.equals(intent.getAction())) {
                    return;
                }
                // 接收器是导出的，必须校验 token，否则任意应用都能拉走 SystemUI 内部类名
                String expected = expectedToken();
                String actual = intent.getStringExtra(Keys.EXTRA_TOKEN);
                if (expected == null || expected.isEmpty() || !expected.equals(actual)) {
                    XLog.w("收到报告请求但 token 校验失败，已忽略");
                    return;
                }
                XLog.i("收到报告请求，开始回传");
                push(context.getApplicationContext());
            } catch (Throwable t) {
                XLog.e("处理报告请求失败", t);
            }
        }
    };

    private static String expectedToken() {
        Prefs p = ShelterModule.prefs();
        if (p == null) {
            return null;
        }
        // token 是应用侧后写的，必须强制重读，否则 XSharedPreferences 里还是旧快照
        p.reload(true);
        return p.string(Keys.RECON_TOKEN, null);
    }

    /** 探测结束后调用：缓存正文并主动回传一次。 */
    public static void publish(Context ctx, String text, String path) {
        sCtx = ctx.getApplicationContext();
        sText = text;
        sPath = path;
        sTime = System.currentTimeMillis();
        XLog.i("探测报告已缓存：" + (text == null ? 0 : text.length()) + " 字符，落盘=" + path);
        push(sCtx);
    }

    private static void push(Context ctx) {
        if (ctx == null) {
            XLog.w("回传中止：没有 SystemUI Context");
            return;
        }
        String text = sText;
        if (text == null || text.isEmpty()) {
            send(ctx, Keys.RECON_STATE_NONE, 0, 0,
                    "SystemUI 内存里还没有报告。请把模式切到「探测模式」并重启系统界面，等待 12 秒后再拉取。",
                    0L);
            return;
        }
        int total = (text.length() + CHUNK_CHARS - 1) / CHUNK_CHARS;
        for (int i = 0; i < total; i++) {
            int from = i * CHUNK_CHARS;
            int to = Math.min(text.length(), from + CHUNK_CHARS);
            send(ctx, Keys.RECON_STATE_OK, total, i, text.substring(from, to), sTime);
        }
        XLog.i("已回传报告 " + total + " 个分片（" + text.length() + " 字符）");
    }

    private static void send(Context ctx, String state, int total, int index,
                             String text, long time) {
        try {
            Intent i = new Intent(Keys.ACTION_RECON_REPORT);
            i.setPackage(Keys.PKG);
            i.putExtra(Keys.EXTRA_RECON_STATE, state);
            i.putExtra(Keys.EXTRA_RECON_TOTAL, total);
            i.putExtra(Keys.EXTRA_RECON_INDEX, index);
            i.putExtra(Keys.EXTRA_RECON_TEXT, text);
            i.putExtra(Keys.EXTRA_RECON_TIME, time);
            i.putExtra(Keys.EXTRA_RECON_PATH, sPath);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            XLog.e("发送报告分片失败", t);
        }
    }
}