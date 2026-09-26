package com.notifshelter.miui;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口。
 *
 * 两种运行模式（写在模块 SharedPreferences 的 {@link Keys#MODE}）：
 *   - recon ：只做类名/方法签名探测，输出报告，不注入任何 hook。用于适配新机型/新版本。
 *   - active：按配置注入 hook，真正实现「收纳到不常用通知」。
 *   - off   ：什么都不做。
 *
 * 默认 off，装完必须先去应用里选模式——避免刚装好就往 logcat 里刷一堆东西。
 */
public class ShelterModule implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static volatile Prefs sPrefs;

    /** 供 SystemUI 侧其他类读取当前配置。 */
    public static Prefs prefs() {
        return sPrefs;
    }

    @Override
    public void initZygote(StartupParam startupParam) {
        XLog.i("initZygote：模块已加载，modulePath=" + startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (!Keys.SYSTEMUI_PKG.equals(lpparam.packageName)) {
                return;
            }
            XLog.i("命中 SystemUI 进程: process=" + lpparam.processName
                    + " isFirstApplication=" + lpparam.isFirstApplication);

            Prefs prefs = new Prefs();
            sPrefs = prefs;
            XLog.setVerbose(prefs.verbose());

            if (!prefs.available()) {
                XLog.w("读不到模块配置。请先打开一次「通知收纳」应用（用于生成配置文件），再重启 SystemUI。");
                return;
            }

            String mode = prefs.mode();
            XLog.i("运行模式 = " + mode);

            // 无论什么模式都回传一次存活广播，方便应用界面确认「模块确实挂上了」
            announceLater(mode);

            if (Keys.MODE_OFF.equals(mode)) {
                XLog.i("模式为 off，不注入任何 hook。请在应用内选择「探测模式」或「收纳模式」。");
                return;
            }

            if (Keys.MODE_RECON.equals(mode)) {
                Recon.schedule(lpparam.classLoader, prefs);
                return;
            }

            new HookEngine(prefs).applyAll(lpparam.classLoader);
        } catch (Throwable t) {
            // 绝不把异常抛回 SystemUI，否则会导致状态栏崩溃/重启
            XLog.e("handleLoadPackage 异常（已捕获，避免影响 SystemUI）", t);
        }
    }

    /**
     * 延迟回传存活广播。
     * handleLoadPackage 执行时 SystemUI 的 Application 还没创建，拿不到 Context，所以必须延后。
     */
    private void announceLater(final String mode) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = AndroidAppHelper.currentApplication();
                    if (ctx == null) {
                        XLog.w("存活广播延期失败：拿不到 Context");
                        return;
                    }
                    Intent i = new Intent(Keys.ACTION_HELLO);
                    i.setPackage(Keys.PKG);
                    i.putExtra(Keys.EXTRA_MODE, mode);
                    ctx.sendBroadcast(i);
                    XLog.i("已回传存活广播 (mode=" + mode + ")");
                } catch (Throwable t) {
                    XLog.w("回传存活广播失败: " + XLog.describe(t));
                }
            }
        }, 6000);
    }
}