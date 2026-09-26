package com.notifshelter.miui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 接收 SystemUI 侧长按菜单的「收纳 / 移出」请求并落盘。 */
public class ShelterReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        try {
            if (Keys.ACTION_TOGGLE.equals(action)) {
                String key = intent.getStringExtra(Keys.EXTRA_KEY);
                if (key == null) {
                    XLog.w("收到收纳广播但没有 key");
                    return;
                }
                if (intent.hasExtra(Keys.EXTRA_VALUE)) {
                    ShelvedKeys.set(context, key, intent.getBooleanExtra(Keys.EXTRA_VALUE, false));
                } else {
                    ShelvedKeys.toggle(context, key);
                }
            } else if (Keys.ACTION_CLEAR.equals(action)) {
                ShelvedKeys.clear(context);
            } else if (Keys.ACTION_HELLO.equals(action)) {
                context.getSharedPreferences(Keys.PREF_FILE, Context.MODE_PRIVATE)
                        .edit()
                        .putLong(Keys.LAST_SEEN, System.currentTimeMillis())
                        .putString(Keys.LAST_SEEN_MODE, intent.getStringExtra(Keys.EXTRA_MODE))
                        .apply();
                XLog.i("收到 SystemUI 存活广播，模式=" + intent.getStringExtra(Keys.EXTRA_MODE));
            } else if (Keys.ACTION_RECON_REPORT.equals(action)) {
                ReconReport.accept(context,
                        intent.getStringExtra(Keys.EXTRA_RECON_STATE),
                        intent.getIntExtra(Keys.EXTRA_RECON_TOTAL, 0),
                        intent.getIntExtra(Keys.EXTRA_RECON_INDEX, 0),
                        intent.getStringExtra(Keys.EXTRA_RECON_TEXT),
                        intent.getLongExtra(Keys.EXTRA_RECON_TIME, 0),
                        intent.getStringExtra(Keys.EXTRA_RECON_PATH));
            }
        } catch (Throwable t) {
            XLog.e("处理收纳广播失败", t);
        }
    }
}