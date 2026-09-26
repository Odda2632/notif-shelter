package com.notifshelter.miui;

import android.app.Activity;
import android.app.Notification;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 模块设置界面：模式切换、通知选择、类名探测、hook 配置。 */
public class SettingsActivity extends Activity {

    private static final int DISPLAY_MAX_LINES = 800;
    private static final int RECON_TARGET_LIMIT = 60;

    private SharedPreferences sp;
    private final Map<String, String> appLabels = new HashMap<>();

    private TextView statusView;
    private TextView notifStatusView;
    private LinearLayout notifList;
    private TextView scanView;
    private EditText editBool;
    private EditText editField;
    private EditText editMenu;

    private final Set<String> scannedCandidates = new LinkedHashSet<>();
    private String scannedFullText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences(Keys.PREF_FILE, Context.MODE_PRIVATE);
        ensureDefaults();
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ShelterListenerService.tryRebind(this);
        refreshStatus();
        // 监听服务重连需要一点时间，稍后再刷一次
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshNotifications();
            }
        }, 800);
        refreshNotifications();
    }

    // ==================== 默认配置 ====================

    /** 首次启动写入默认值。这一步同时会创建配置文件，SystemUI 侧才读得到。 */
    private void ensureDefaults() {
        if (sp.contains(Keys.MODE)) {
            return;
        }
        sp.edit()
                .putString(Keys.MODE, Keys.MODE_OFF)
                .putBoolean(Keys.VERBOSE, true)
                .putBoolean(Keys.RECON_DUMP_METHODS, true)
                .putInt(Keys.RECON_MAX_CLASSES, 300)
                .putString(Keys.HOOK_BOOL, "[]")
                .putString(Keys.HOOK_FIELD, "[]")
                .putString(Keys.HOOK_MENU, "[]")
                .apply();
    }

    // ==================== 界面搭建 ====================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        root.setPadding(p, p, p, p);

        root.addView(text("通知收纳", 22, true));
        root.addView(text("把任意通知手动收纳进「不常用通知」· LSPosed 模块", 12, false));

        // ---------- 1 状态 ----------
        root.addView(section("1. 当前状态"));
        statusView = text("", 13, false);
        root.addView(statusView);

        // ---------- 2 运行模式 ----------
        root.addView(section("2. 运行模式"));
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        final String[] modes = {Keys.MODE_OFF, Keys.MODE_RECON, Keys.MODE_ACTIVE};
        final String[] labels = {
                "关闭 —— 不做任何事",
                "探测模式 —— 输出类名与方法签名报告（适配新机型用）",
                "收纳模式 —— 按配置注入 hook，真正生效",
        };
        String current = sp.getString(Keys.MODE, Keys.MODE_OFF);
        for (int i = 0; i < modes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setTag(modes[i]);
            rb.setId(View.generateViewId());
            rb.setChecked(modes[i].equals(current));
            group.addView(rb);
        }
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                View v = g.findViewById(checkedId);
                if (v == null) {
                    return;
                }
                Object tag = v.getTag();
                if (tag != null) {
                    setMode(String.valueOf(tag));
                }
            }
        });
        root.addView(group);
        root.addView(text("改完模式必须让 SystemUI 重启才生效：在 LSPosed 管理器里对「系统界面」"
                + "执行重启，或直接重启手机。", 12, false));

        // ---------- 3 通知使用权 ----------
        root.addView(section("3. 通知使用权"));
        notifStatusView = text("", 13, false);
        root.addView(notifStatusView);
        root.addView(button("去授予 / 检查通知使用权", new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                } catch (Throwable t) {
                    toast("打不开设置页：" + XLog.describe(t));
                }
            }
        }));

        // ---------- 4 手动收纳 ----------
        root.addView(section("4. 当前通知 · 手动收纳"));
        root.addView(text("这里列出的是此刻真实存在的通知。点「收纳」把它加入不常用列表"
                + "（收纳效果需要「收纳模式」下的 hook 已适配完成才会体现在状态栏）。", 12, false));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button("刷新", new Runnable() {
            @Override
            public void run() {
                refreshNotifications();
            }
        }));
        actions.addView(button("全部收纳", new Runnable() {
            @Override
            public void run() {
                shelveAll();
            }
        }));
        actions.addView(button("清空记录", new Runnable() {
            @Override
            public void run() {
                ShelvedKeys.clear(SettingsActivity.this);
                refreshNotifications();
            }
        }));
        root.addView(actions);
        notifList = new LinearLayout(this);
        notifList.setOrientation(LinearLayout.VERTICAL);
        root.addView(notifList);

        // ---------- 5 类名探测 ----------
        root.addView(section("5. 探测本机 SystemUI 类名"));
        root.addView(text("直接读取本机 SystemUI.apk 里的 DEX 来枚举类名，不需要 root，"
                + "也不会影响系统运行。先在应用里扫出候选类，再让 SystemUI 侧 dump 方法签名。", 12, false));
        root.addView(button("开始扫描 SystemUI 类名", new Runnable() {
            @Override
            public void run() {
                doScan();
            }
        }));
        root.addView(button("复制完整结果", new Runnable() {
            @Override
            public void run() {
                copyToClipboard(scannedFullText.isEmpty() ? "（还没扫描）" : scannedFullText);
            }
        }));
        root.addView(button("把候选类写入 recon_targets（供 SystemUI dump 方法签名）", new Runnable() {
            @Override
            public void run() {
                writeReconTargets();
            }
        }));
        scanView = text("", 11, false);
        scanView.setTypeface(Typeface.MONOSPACE);
        root.addView(scanView);

        // ---------- 6 hook 配置 ----------
        root.addView(section("6. 高级：hook 配置（JSON）"));
        root.addView(text("探测报告拿到真实类名/方法签名后填在这里。格式见工程里的说明文档；"
                + "保存后同样需要重启 SystemUI 生效。", 12, false));
        editBool = jsonEditor(Keys.HOOK_BOOL, "hook_bool —— 强制判定方法返回指定值（核心折叠手段）");
        editField = jsonEditor(Keys.HOOK_FIELD, "hook_field —— 方法执行后改写对象字段");
        editMenu = jsonEditor(Keys.HOOK_MENU, "hook_entry —— 长按通知的入口（弹菜单/直接切换）");
        root.addView(button("保存 hook 配置", new Runnable() {
            @Override
            public void run() {
                saveHooks();
            }
        }));

        // ---------- 7 报告位置 ----------
        root.addView(section("7. 探测报告在哪里"));
        TextView paths = text("SystemUI 侧生成的报告会按顺序尝试写到：\n"
                + "  /data/local/tmp/miui_shelter/recon.txt\n"
                + "  /sdcard/Android/data/com.android.systemui/files/recon.txt\n\n"
                + "命令：\n"
                + "  adb logcat -s MIUI-Shelter\n"
                + "  adb pull /data/local/tmp/miui_shelter/recon.txt", 12, false);
        paths.setTypeface(Typeface.MONOSPACE);
        root.addView(paths);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        return sv;
    }

    private EditText jsonEditor(String key, String hint) {
        LinearLayout box = new LinearLayout(this);
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(sp.getString(key, "[]"));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(3);
        et.setTypeface(Typeface.MONOSPACE);
        et.setTextSize(11);
        box.addView(et);
        // 用 tag 关联 key，保存时统一读取
        et.setTag(key);
        pendingEditors.add(et);
        return et;
    }

    private final List<EditText> pendingEditors = new ArrayList<>();

    // ==================== 状态刷新 ====================

    private void refreshStatus() {
        String mode = sp.getString(Keys.MODE, Keys.MODE_OFF);
        long lastSeen = sp.getLong(Keys.LAST_SEEN, 0);
        String lastMode = sp.getString(Keys.LAST_SEEN_MODE, null);

        StringBuilder sb = new StringBuilder();
        sb.append("运行模式：").append(modeDesc(mode)).append('\n');
        if (lastSeen > 0) {
            sb.append("模块最近一次加载：")
                    .append(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                            .format(new Date(lastSeen)))
                    .append("（模式 ").append(lastMode).append("）\n");
        } else {
            sb.append("模块最近一次加载：尚未收到回执\n")
                    .append("若一直是这样，请确认：LSPosed 里本模块已启用、"
                            + "作用域勾选了「系统界面」，并且重启过 SystemUI。\n");
        }
        sb.append("已收纳通知数：").append(ShelvedKeys.read(this).size());
        statusView.setText(sb.toString());
    }

    private void refreshNotifications() {
        notifList.removeAllViews();
        boolean granted = ShelterListenerService.isGranted(this);
        boolean connected = ShelterListenerService.isConnected();
        notifStatusView.setText("通知使用权：" + (granted ? "已授予" : "未授予")
                + "　服务连接：" + (connected ? "已连接" : "未连接"));
        if (!granted) {
            return;
        }
        List<StatusBarNotification> list = ShelterListenerService.active();
        if (list.isEmpty()) {
            notifList.addView(text("当前没有活动通知（或服务尚未连接）。", 12, false));
            return;
        }
        Set<String> shelved = ShelvedKeys.read(this);
        for (final StatusBarNotification sbn : list) {
            final String key = sbn.getKey();
            final boolean isShelved = shelved.contains(key);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView tv = text((isShelved ? "[已收纳] " : "") + label(sbn), 12, false);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tv);

            row.addView(button(isShelved ? "移出" : "收纳", new Runnable() {
                @Override
                public void run() {
                    ShelvedKeys.set(SettingsActivity.this, key, !isShelved);
                    refreshStatus();
                    refreshNotifications();
                }
            }));
            notifList.addView(row);
        }
    }

    private String label(StatusBarNotification sbn) {
        StringBuilder sb = new StringBuilder(appLabel(sbn.getPackageName()));
        try {
            Bundle ex = sbn.getNotification().extras;
            if (ex != null) {
                String title = ex.getString(Notification.EXTRA_TITLE);
                String text = ex.getString(Notification.EXTRA_TEXT);
                if (title != null && !title.isEmpty()) {
                    sb.append(" · ").append(title);
                }
                if (text != null && !text.isEmpty()) {
                    sb.append(" — ").append(text);
                }
            }
        } catch (Throwable ignored) {
            // extras 读不到就只显示包名
        }
        sb.append('\n').append(sbn.getPackageName());
        return sb.toString();
    }

    private String appLabel(String pkg) {
        String cached = appLabels.get(pkg);
        if (cached != null) {
            return cached;
        }
        String label = pkg;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null) {
                label = cs.toString();
            }
        } catch (Throwable ignored) {
            // 保持包名
        }
        appLabels.put(pkg, label);
        return label;
    }

    // ==================== 动作 ====================

    private void setMode(String mode) {
        sp.edit().putString(Keys.MODE, mode).apply();
        refreshStatus();
        toast("模式已切换为「" + modeDesc(mode) + "」，重启 SystemUI 后生效");
    }

    private void shelveAll() {
        List<StatusBarNotification> list = ShelterListenerService.active();
        if (list.isEmpty()) {
            toast("当前没有可收纳的通知");
            return;
        }
        Set<String> s = ShelvedKeys.read(this);
        for (StatusBarNotification sbn : list) {
            s.add(sbn.getKey());
        }
        ShelvedKeys.write(this, s);
        toast("已收纳 " + list.size() + " 条通知");
        refreshStatus();
        refreshNotifications();
    }

    private void doScan() {
        scanView.setText("扫描中…（读取 SystemUI.apk，可能需要几秒）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result;
                try {
                    result = scanSystemUi();
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            scanView.setText("扫描失败：" + XLog.describe(t));
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        scannedFullText = result;
                        scanView.setText(truncate(result));
                    }
                });
            }
        }, "shelter-scan").start();
    }

    private String scanSystemUi() throws Exception {
        Set<String> descriptors = new LinkedHashSet<>();
        List<String> paths = new ArrayList<>();
        PackageManager pm = getPackageManager();
        ApplicationInfo ai = pm.getApplicationInfo(Keys.SYSTEMUI_PKG, 0);
        if (ai.sourceDir != null) {
            paths.add(ai.sourceDir);
        }
        if (ai.splitSourceDirs != null) {
            for (String s : ai.splitSourceDirs) {
                paths.add(s);
            }
        }
        for (String path : paths) {
            XLog.i("应用侧扫描: " + path);
            DexScanner.scanFile(new File(path), descriptors);
        }

        Set<String> binaries = new LinkedHashSet<>();
        for (String d : descriptors) {
            String b = ClassCatalog.toBinaryName(d);
            if (b != null && !b.isEmpty()) {
                binaries.add(b);
            }
        }
        Map<String, List<String>> buckets = ClassCatalog.bucket(binaries, 300);

        scannedCandidates.clear();
        StringBuilder sb = new StringBuilder();
        sb.append("SystemUI 类总数：").append(binaries.size()).append('\n');
        sb.append("APK：").append(paths.isEmpty() ? "-" : paths.get(0)).append('\n');
        sb.append('\n');
        for (Map.Entry<String, List<String>> e : buckets.entrySet()) {
            List<String> v = e.getValue();
            sb.append("--- ").append(e.getKey()).append("  命中 ").append(v.size()).append(" ---\n");
            for (String c : v) {
                sb.append(c).append('\n');
                if (!c.startsWith("…") && scannedCandidates.size() < 4000) {
                    scannedCandidates.add(c);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String truncate(String s) {
        String[] lines = s.split("\n");
        if (lines.length <= DISPLAY_MAX_LINES) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DISPLAY_MAX_LINES; i++) {
            sb.append(lines[i]).append('\n');
        }
        sb.append("\n…（界面仅显示前 ").append(DISPLAY_MAX_LINES)
                .append(" 行，共 ").append(lines.length)
                .append(" 行。点「复制完整结果」拿全量）");
        return sb.toString();
    }

    private void writeReconTargets() {
        if (scannedCandidates.isEmpty()) {
            toast("请先扫描一次");
            return;
        }
        Set<String> targets = new LinkedHashSet<>();
        for (String c : scannedCandidates) {
            if (targets.size() >= RECON_TARGET_LIMIT) {
                break;
            }
            targets.add(c);
        }
        sp.edit().putStringSet(Keys.RECON_TARGETS, targets).apply();
        toast("已写入 " + targets.size() + " 个目标类，切到「探测模式」并重启 SystemUI 即可 dump 方法签名");
    }

    private void saveHooks() {
        for (EditText et : pendingEditors) {
            String key = String.valueOf(et.getTag());
            String value = et.getText().toString().trim();
            if (value.isEmpty()) {
                value = "[]";
            }
            if (!isValidJson(value)) {
                toast(key + " 不是合法 JSON，请检查");
                return;
            }
            sp.edit().putString(key, value).apply();
        }
        toast("hook 配置已保存，重启 SystemUI 后生效");
    }

    private boolean isValidJson(String s) {
        try {
            new org.json.JSONArray(s);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void copyToClipboard(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("notif-shelter", s));
                toast("已复制");
            }
        } catch (Throwable t) {
            toast("复制失败：" + XLog.describe(t));
        }
    }

    // ==================== 小工具 ====================

    private static String modeDesc(String mode) {
        if (Keys.MODE_ACTIVE.equals(mode)) {
            return "收纳模式";
        }
        if (Keys.MODE_RECON.equals(mode)) {
            return "探测模式";
        }
        return "关闭";
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView text(String s, float size, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        tv.setPadding(0, dp(4), 0, dp(4));
        return tv;
    }

    private TextView section(String s) {
        TextView tv = text(s, 16, true);
        tv.setPadding(0, dp(18), 0, dp(6));
        return tv;
    }

    private Button button(String s, final Runnable action) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    action.run();
                } catch (Throwable t) {
                    toast("操作失败：" + XLog.describe(t));
                }
            }
        });
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}