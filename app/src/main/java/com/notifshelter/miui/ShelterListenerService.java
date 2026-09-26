package com.notifshelter.miui;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 用于在应用内列出「当前真实存在的通知」，让用户点选要收纳哪几条。
 *
 * 这条路径刻意做成不依赖任何 SystemUI 内部实现：即使折叠 hook 还没适配好，
 * 用户也已经能把要收纳的通知选出来（存进 SharedPreferences），等 hook 定位完立刻生效。
 */
public class ShelterListenerService extends NotificationListenerService {

    private static volatile ShelterListenerService sInstance;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sInstance = this;
        XLog.i("通知监听已连接");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (sInstance == this) {
            sInstance = null;
        }
        XLog.i("通知监听已断开");
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }

    public static boolean isConnected() {
        return sInstance != null;
    }

    /** 请求系统重新绑定本监听服务，用于刚授权后立刻拿到实例。 */
    public static void tryRebind(Context ctx) {
        try {
            requestRebind(new ComponentName(ctx, ShelterListenerService.class));
        } catch (Throwable t) {
            XLog.v("requestRebind 失败（可忽略）：" + XLog.describe(t));
        }
    }

    /** 当前活动通知列表；未连接时返回空列表。 */
    public static List<StatusBarNotification> active() {
        ShelterListenerService s = sInstance;
        List<StatusBarNotification> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        try {
            StatusBarNotification[] arr = s.getActiveNotifications();
            if (arr != null) {
                Collections.addAll(out, arr);
            }
        } catch (Throwable t) {
            XLog.w("getActiveNotifications 失败：" + XLog.describe(t));
        }
        return out;
    }

    /** 通知使用权是否已授予本应用。 */
    public static boolean isGranted(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(),
                    "enabled_notification_listeners");
            if (flat == null || flat.isEmpty()) {
                return false;
            }
            ComponentName cn = new ComponentName(ctx, ShelterListenerService.class);
            return flat.contains(cn.flattenToString()) || flat.contains(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }
}