package com.notifshelter.miui;

/** 模块与应用共享的常量。SharedPreferences 文件名/键名必须两边一致。 */
public final class Keys {

    /** 模块包名。SystemUI 侧用固定常量，避免依赖 BuildConfig。 */
    public static final String PKG = "com.notifshelter.miui";

    public static final String SYSTEMUI_PKG = "com.android.systemui";

    /** SharedPreferences 文件名（LSPosed 通过 xposedsharedprefs 让 SystemUI 可读） */
    public static final String PREF_FILE = "config";

    // ---- 运行模式 ----
    public static final String MODE = "mode";
    public static final String MODE_OFF = "off";
    public static final String MODE_RECON = "recon";
    public static final String MODE_ACTIVE = "active";

    public static final String VERBOSE = "verbose";

    // ---- 探测 ----
    public static final String RECON_TARGETS = "recon_targets";       // StringSet: 待 dump 方法签名的类
    public static final String RECON_KEYWORDS = "recon_keywords";     // String: 逗号分隔，覆盖默认关键词
    public static final String RECON_DUMP_METHODS = "recon_dump_methods"; // boolean
    public static final String RECON_MAX_CLASSES = "recon_max_classes";   // int，每组上限
    public static final String LAST_RECON_PATH = "last_recon_path";
    public static final String LAST_RECON_TIME = "last_recon_time";
    public static final String LAST_RECON_SUMMARY = "last_recon_summary";

    // ---- Hook 配置（JSON 字符串）----
    public static final String HOOK_BOOL = "hook_bool";
    public static final String HOOK_FIELD = "hook_field";
    public static final String HOOK_MENU = "hook_menu";

    // ---- 已收纳通知 ----
    public static final String SHELVED_KEYS = "shelved_keys";         // StringSet: Notification key
    public static final String SHELVE_INVERT = "shelve_invert";       // boolean: true 则只收纳命中的，false 同

    // ---- 跨进程通信 ----
    public static final String ACTION_TOGGLE = "com.notifshelter.miui.action.TOGGLE_SHELVE";
    public static final String ACTION_CLEAR = "com.notifshelter.miui.action.CLEAR_SHELVE";
    /** SystemUI 侧启动完成后回传的「存活广播」，让应用界面能确认模块真的加载了。 */
    public static final String ACTION_HELLO = "com.notifshelter.miui.action.HELLO";
    public static final String EXTRA_KEY = "key";
    /** 配合 ACTION_TOGGLE：显式指定目标状态；不带该 extra 时表示取反。 */
    public static final String EXTRA_VALUE = "value";
    public static final String EXTRA_MODE = "mode";

    // ---- 模块存活记录（由应用侧在收到 ACTION_HELLO 时写入）----
    public static final String LAST_SEEN = "module_last_seen";
    public static final String LAST_SEEN_MODE = "module_last_seen_mode";

    private Keys() {
    }
}