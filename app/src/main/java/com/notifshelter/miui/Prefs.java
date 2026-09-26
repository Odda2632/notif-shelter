package com.notifshelter.miui;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;

/**
 * SystemUI 进程侧读取模块配置。
 *
 * 依赖 Manifest 里的 {@code xposedsharedprefs=true}：LSPosed 会把模块的
 * SharedPreferences 文件设为被 hook 进程可读，因此这里用 XSharedPreferences 跨进程读取，
 * 不需要 root，也不需要 world-readable 的 hack。
 */
public final class Prefs {

    /** 多次 reload 之间做节流，避免每个方法调用都去读文件。 */
    private static final long RELOAD_MIN_INTERVAL_MS = 1500L;

    private final XSharedPreferences sp;
    private volatile long lastReload;
    private volatile Set<String> shelvedCache = Collections.emptySet();

    public Prefs() {
        XSharedPreferences p = null;
        try {
            p = new XSharedPreferences(Keys.PKG, Keys.PREF_FILE);
        } catch (Throwable t) {
            XLog.e("XSharedPreferences 初始化失败，配置将全部走默认值", t);
        }
        this.sp = p;
        reload(true);
    }

    public boolean available() {
        return sp != null;
    }

    public void reload() {
        reload(false);
    }

    public void reload(boolean force) {
        if (sp == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastReload < RELOAD_MIN_INTERVAL_MS) {
            return;
        }
        try {
            sp.reload();
            lastReload = now;
        } catch (Throwable t) {
            XLog.w("配置 reload 失败：" + XLog.describe(t));
        }
    }

    // ---- 基础读取 ----

    public String mode() {
        String v = getString(Keys.MODE, Keys.MODE_OFF);
        return v == null ? Keys.MODE_OFF : v;
    }

    public boolean verbose() {
        return getBoolean(Keys.VERBOSE, true);
    }

    public boolean isActive() {
        return Keys.MODE_ACTIVE.equals(mode());
    }

    public boolean isRecon() {
        return Keys.MODE_RECON.equals(mode());
    }

    public String string(String key, String def) {
        return getString(key, def);
    }

    public boolean bool(String key, boolean def) {
        return getBoolean(key, def);
    }

    public int intValue(String key, int def) {
        if (sp == null) {
            return def;
        }
        try {
            return sp.getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public Set<String> stringSet(String key) {
        if (sp == null) {
            return null;
        }
        try {
            Set<String> s = sp.getStringSet(key, null);
            return s == null ? null : new LinkedHashSet<>(s);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- 已收纳通知 ----

    /** 已收纳的通知 key 集合。读文件，带 1.5s 节流。 */
    public Set<String> shelvedKeys() {
        return shelvedKeys(false);
    }

    public Set<String> shelvedKeys(boolean force) {
        Set<String> cached = shelvedCache;
        if (sp == null) {
            return cached;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastReload < RELOAD_MIN_INTERVAL_MS) {
            return cached;
        }
        reload(force);
        try {
            Set<String> s = sp.getStringSet(Keys.SHELVED_KEYS, null);
            shelvedCache = s == null ? Collections.<String>emptySet() : new LinkedHashSet<>(s);
        } catch (Throwable t) {
            XLog.w("读取已收纳列表失败：" + XLog.describe(t));
        }
        return shelvedCache;
    }

    public boolean isShelved(String key) {
        if (key == null) {
            return false;
        }
        return shelvedKeys().contains(key);
    }

    // ---- 内部 ----

    private String getString(String key, String def) {
        if (sp == null) {
            return def;
        }
        try {
            String v = sp.getString(key, def);
            return v == null ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    private boolean getBoolean(String key, boolean def) {
        if (sp == null) {
            return def;
        }
        try {
            return sp.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }
}