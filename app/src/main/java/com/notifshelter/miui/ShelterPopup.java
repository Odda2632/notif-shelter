package com.notifshelter.miui;

import android.app.AndroidAppHelper;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * 在 SystemUI 进程里弹出的「收纳」菜单。
 *
 * 刻意不依赖框架自己的长按菜单实现（那部分是版本相关的、最容易坏）：
 * 这里用独立 overlay 窗口弹一个自带菜单，只要找到长按入口就能工作。
 */
final class ShelterPopup {

    private ShelterPopup() {
    }

    /** 弹出收纳菜单。 */
    static void showMenu(final String key, final String title) {
        final Context ctx = context();
        if (ctx == null) {
            XLog.w("拿不到 Context，无法弹窗，改为直接切换");
            toggleDirect(key);
            return;
        }
        final boolean shelved = isShelved(key);
        final String[] items = shelved
                ? new String[]{"移出「不常用通知」", "复制通知 key（用于排查）"}
                : new String[]{"收纳到「不常用通知」", "复制通知 key（用于排查）"};

        onMain(new Runnable() {
            @Override
            public void run() {
                try {
                    AlertDialog d = new AlertDialog.Builder(ctx)
                            .setTitle(title + "\n" + shorten(key))
                            .setItems(items, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which == 0) {
                                        applyState(ctx, key, !shelved);
                                    } else {
                                        copy(ctx, key);
                                    }
                                }
                            })
                            .setNegativeButton("取消", null)
                            .create();
                    if (d.getWindow() != null) {
                        // SystemUI 持有 SYSTEM_ALERT_WINDOW，可以直接用 overlay 窗口
                        d.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                    }
                    d.show();
                } catch (Throwable t) {
                    XLog.e("弹出收纳菜单失败，改为直接切换", t);
                    toggleDirect(key);
                }
            }
        });
    }

    /** 不做菜单，直接把这条通知在「已收纳 / 未收纳」之间切换。 */
    static void toggleDirect(String key) {
        Context ctx = context();
        if (ctx == null) {
            return;
        }
        applyState(ctx, key, !isShelved(key));
    }

    private static void applyState(Context ctx, String key, boolean shelved) {
        ShelvedKeys.requestSet(ctx, key, shelved);
        toast(ctx, shelved ? "已收纳到「不常用通知」" : "已移出「不常用通知」");
        refreshSoon();
    }

    private static void refreshSoon() {
        final Prefs p = ShelterModule.prefs();
        if (p == null) {
            return;
        }
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                p.shelvedKeys(true);
            }
        }, 400);
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                p.shelvedKeys(true);
            }
        }, 1200);
    }

    private static boolean isShelved(String key) {
        Prefs p = ShelterModule.prefs();
        return p != null && p.isShelved(key);
    }

    private static void copy(Context ctx, String key) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("notif key", key));
                toast(ctx, "key 已复制");
            }
        } catch (Throwable t) {
            XLog.e("复制失败", t);
        }
    }

    private static void toast(final Context ctx, final String msg) {
        onMain(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    XLog.w("Toast 失败：" + XLog.describe(t));
                }
            }
        });
    }

    private static void onMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            new Handler(Looper.getMainLooper()).post(r);
        }
    }

    private static Context context() {
        try {
            return AndroidAppHelper.currentApplication();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String shorten(String key) {
        if (key == null) {
            return "";
        }
        return key.length() <= 48 ? key : key.substring(0, 45) + "…";
    }
}