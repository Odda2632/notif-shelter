package com.notifshelter.miui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「已收纳通知」集合的读写与跨进程同步。
 *
 * 状态只有一份：模块应用的 SharedPreferences（{@link Keys#SHELVED_KEYS}）。
 *  - 应用侧直接读写（手动选择、列表管理）；
 *  - SystemUI 侧只读（通过 {@link Prefs}），需要改状态时用广播回传给应用，由应用落盘。
 *
 * 之所以让应用当唯一写入方：SystemUI 与模块应用是不同 UID，直接互相写对方的
 * SharedPreferences 在 Android 10+ 上不可靠，走广播最简单也最稳。
 */
public final class ShelvedKeys {

    private ShelvedKeys() {
    }

    // ==================== 应用侧：持久化 ====================

    public static Set<String> read(Context ctx) {
        Set<String> s = prefs(ctx).getStringSet(Keys.SHELVED_KEYS, null);
        return s == null ? new LinkedHashSet<String>() : new LinkedHashSet<>(s);
    }

    public static void write(Context ctx, Set<String> keys) {
        prefs(ctx).edit()
                .putStringSet(Keys.SHELVED_KEYS, new LinkedHashSet<>(keys))
                .apply();
    }

    /** @return 写入后的状态 */
    public static boolean set(Context ctx, String key, boolean shelved) {
        if (key == null) {
            return false;
        }
        Set<String> s = read(ctx);
        if (shelved) {
            s.add(key);
        } else {
            s.remove(key);
        }
        write(ctx, s);
        XLog.i("收纳状态更新: " + key + " -> " + shelved + "（当前共 " + s.size() + " 条）");
        return shelved;
    }

    public static boolean toggle(Context ctx, String key) {
        return set(ctx, key, !read(ctx).contains(key));
    }

    public static void clear(Context ctx) {
        write(ctx, new LinkedHashSet<String>());
        XLog.i("已清空全部收纳记录");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Keys.PREF_FILE, Context.MODE_PRIVATE);
    }

    // ==================== SystemUI 侧：广播回传 ====================

    public static void requestSet(Context ctx, String key, boolean shelved) {
        try {
            Intent i = new Intent(Keys.ACTION_TOGGLE);
            i.setPackage(Keys.PKG);
            i.putExtra(Keys.EXTRA_KEY, key);
            i.putExtra(Keys.EXTRA_VALUE, shelved);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            XLog.e("发送收纳广播失败", t);
        }
    }

    public static void requestClear(Context ctx) {
        try {
            Intent i = new Intent(Keys.ACTION_CLEAR);
            i.setPackage(Keys.PKG);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            XLog.e("发送清空广播失败", t);
        }
    }
}