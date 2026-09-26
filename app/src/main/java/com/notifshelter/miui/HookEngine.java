package com.notifshelter.miui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 配置驱动的 hook 引擎。
 *
 * 为什么不做成「硬编码类名」：HyperOS 4 / Android 17 的 SystemUI 属于未知目标，
 * 折叠逻辑的类名与方法签名必须先在设备上探测出来。因此这里把 hook 抽象成三种可配置策略，
 * 探测出签名后只要往配置里填一行 JSON 就能生效，不需要改代码重新编译。
 *
 * 配置项（均为 JSON 字符串，写在模块 SharedPreferences 里，见 {@link Keys}）：
 *
 * 【1】hook_bool —— 强制某个判定方法返回指定布尔值（核心折叠手段）
 * [{
 *    "cls":    "com.android.systemui....Foo",
 *    "method": "shouldHide",
 *    "params": ["com.android.systemui....NotificationEntry"], // 可省略/null = 同名全部重载
 *    "ret":    true,
 *    "gate":   "shelved",       // shelved = 仅对已收纳通知生效；always = 一律生效
 *    "argKeyIndex": 0,          // 哪个参数携带通知 key；-1 = 不按 key 判断
 *    "keyGetter": "getKey",     // 参数不是 String 时，用这个 getter 取 key
 *    "keyField":  "",           // 或者用字段取 key
 *    "log": false
 * }]
 *
 * 【2】hook_field —— 在某方法执行后，把对象上的某个字段改成指定值（另一种折叠手段）
 * [{
 *    "cls":..,"method":..,"params":[..],
 *    "field": "mIsUncommon", "value": true,
 *    "onResult": false,         // true = 改返回值对象；false = 改 this
 *    "gate": "shelved", "argKeyIndex": 0
 * }]
 *
 * 【3】hook_entry —— 长按通知时的入口，弹出自带菜单或直接切换收纳状态
 * [{
 *    "cls":..,"method":..,"params":[..],
 *    "argKeyIndex": 0,          // -1 = 参数里没有 key，改从 this 对象上取（长按入口常用 -1）
 *    "keyGetter":"getKey",
 *    "action": "menu",          // menu = 弹自带菜单；toggle = 直接切换 + Toast
 *    "title":  "通知收纳"
 * }]
 */
public final class HookEngine {

    private static final AtomicInteger sHitLog = new AtomicInteger();
    private static final int HIT_LOG_LIMIT = 20;

    private final Prefs prefs;
    private ClassLoader cl;
    private int okCount;
    private int failCount;

    public HookEngine(Prefs prefs) {
        this.prefs = prefs;
    }

    public void applyAll(ClassLoader classLoader) {
        this.cl = classLoader;
        applyBoolHooks(prefs.string(Keys.HOOK_BOOL, ""));
        applyFieldHooks(prefs.string(Keys.HOOK_FIELD, ""));
        applyEntryHooks(prefs.string(Keys.HOOK_MENU, ""));
        XLog.i("hook 应用完成：成功 " + okCount + " 个，失败 " + failCount + " 个");
        if (okCount == 0) {
            XLog.w("没有任何 hook 生效。若这是首次运行，请先切到「探测模式」并把报告发回来定位类名。");
        }
    }

    // ==================== 1. bool hooks ====================

    private void applyBoolHooks(String json) {
        JSONArray arr = parseArray(json, Keys.HOOK_BOOL);
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String cls = o.optString("cls", "");
            String method = o.optString("method", "");
            if (cls.isEmpty() || method.isEmpty()) {
                XLog.w("hook_bool[" + i + "] 缺少 cls/method，跳过");
                continue;
            }
            final boolean ret = o.optBoolean("ret", true);
            final int argKeyIndex = o.optInt("argKeyIndex", -1);
            final boolean gateShelved = !"always".equals(o.optString("gate", "shelved"));
            final boolean log = o.optBoolean("log", false);
            final String keyGetter = o.optString("keyGetter", "getKey");
            final String keyField = o.optString("keyField", "");

            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (gateShelved) {
                            String key = resolveKey(param.args, argKeyIndex, keyField, keyGetter, param.thisObject);
                            if (key == null || !prefs.isShelved(key)) {
                                return;
                            }
                        }
                        param.setResult(Boolean.valueOf(ret));
                        if (log) {
                            hit("hook_bool", param.method.getName(), ret);
                        }
                    } catch (Throwable t) {
                        XLog.e("hook_bool 执行异常", t);
                    }
                }
            };
            hookTarget(cls, method, jsonParams(o), hook);
        }
    }

    // ==================== 2. field hooks ====================

    private void applyFieldHooks(String json) {
        JSONArray arr = parseArray(json, Keys.HOOK_FIELD);
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String cls = o.optString("cls", "");
            String method = o.optString("method", "");
            final String field = o.optString("field", "");
            if (cls.isEmpty() || method.isEmpty() || field.isEmpty()) {
                XLog.w("hook_field[" + i + "] 缺少 cls/method/field，跳过");
                continue;
            }
            final Object value = jsonValue(o.opt("value"));
            final boolean onResult = o.optBoolean("onResult", false);
            final int argKeyIndex = o.optInt("argKeyIndex", -1);
            final boolean gateShelved = !"always".equals(o.optString("gate", "shelved"));
            final String keyGetter = o.optString("keyGetter", "getKey");
            final String keyField = o.optString("keyField", "");

            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (gateShelved) {
                            String key = resolveKey(param.args, argKeyIndex, keyField, keyGetter, param.thisObject);
                            if (key == null || !prefs.isShelved(key)) {
                                return;
                            }
                        }
                        Object target = onResult ? param.getResult() : param.thisObject;
                        if (target == null) {
                            return;
                        }
                        setField(target, field, value);
                        hit("hook_field", field, value);
                    } catch (Throwable t) {
                        XLog.e("hook_field 执行异常", t);
                    }
                }
            };
            hookTarget(cls, method, jsonParams(o), hook);
        }
    }

    // ==================== 3. entry hooks ====================

    private void applyEntryHooks(String json) {
        JSONArray arr = parseArray(json, Keys.HOOK_MENU);
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String cls = o.optString("cls", "");
            String method = o.optString("method", "");
            if (cls.isEmpty() || method.isEmpty()) {
                XLog.w("hook_entry[" + i + "] 缺少 cls/method，跳过");
                continue;
            }
            final int argKeyIndex = o.optInt("argKeyIndex", 0);
            final String keyGetter = o.optString("keyGetter", "getKey");
            final String keyField = o.optString("keyField", "");
            final String action = o.optString("action", "menu");
            final String title = o.optString("title", "通知收纳");

            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String key = resolveKey(param.args, argKeyIndex, keyField, keyGetter, param.thisObject);
                        if (key == null) {
                            XLog.w("hook_entry: 无法从 " + param.method.getName() + " 取出通知 key，"
                                    + "请调整 argKeyIndex/keyGetter/keyField");
                            return;
                        }
                        hit("hook_entry", param.method.getName(), key);
                        if ("toggle".equals(action)) {
                            ShelterPopup.toggleDirect(key);
                        } else {
                            ShelterPopup.showMenu(key, title);
                        }
                    } catch (Throwable t) {
                        XLog.e("hook_entry 执行异常", t);
                    }
                }
            };
            hookTarget(cls, method, jsonParams(o), hook);
        }
    }

    // ==================== 基础设施 ====================

    private void hookTarget(String cls, String method, String[] params, XC_MethodHook hook) {
        if (cl == null) {
            return;
        }
        Class<?> c = XposedHelpers.findClassIfExists(cls, cl);
        if (c == null) {
            failCount++;
            XLog.w("找不到类，hook 跳过: " + cls);
            return;
        }
        try {
            if (params == null) {
                Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(c, method, hook);
                if (unhooks.isEmpty()) {
                    failCount++;
                    XLog.w("方法不存在，hook 跳过: " + cls + "#" + method);
                } else {
                    okCount++;
                    XLog.i("已 hook（全部重载）: " + cls + "#" + method + " x" + unhooks.size());
                }
            } else {
                Class<?>[] types = paramTypes(params);
                // 注意：findAndHookMethod 是可变参数，必须把参数类型数组展开，
                // 不能把 Class[] 当成单个参数传进去
                Object[] varargs = new Object[types.length + 1];
                System.arraycopy(types, 0, varargs, 0, types.length);
                varargs[types.length] = hook;
                XposedHelpers.findAndHookMethod(c, method, varargs);
                okCount++;
                XLog.i("已 hook: " + cls + "#" + method + ClassCatalog.params(types));
            }
        } catch (Throwable t) {
            failCount++;
            XLog.e("hook 失败: " + cls + "#" + method, t);
        }
    }

    private Class<?>[] paramTypes(String[] names) {
        Class<?>[] out = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = typeOf(names[i]);
        }
        return out;
    }

    private Class<?> typeOf(String name) {
        switch (name) {
            case "boolean":
                return boolean.class;
            case "byte":
                return byte.class;
            case "char":
                return char.class;
            case "short":
                return short.class;
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "void":
                return void.class;
            default:
                return XposedHelpers.findClass(name, cl);
        }
    }

    private static String[] jsonParams(JSONObject o) {
        if (!o.has("params") || o.isNull("params")) {
            return null; // null 表示 hook 同名全部重载
        }
        JSONArray a = o.optJSONArray("params");
        if (a == null) {
            return new String[0];
        }
        String[] out = new String[a.length()];
        for (int i = 0; i < a.length(); i++) {
            out[i] = a.optString(i);
        }
        return out;
    }

    private static Object jsonValue(Object v) {
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Double || v instanceof String) {
            return v;
        }
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 从 hook 上下文里取出通知 key。
     *
     * 依次尝试：指定参数 → 接收者对象(this)；
     * 每个候选对象再依次尝试：本身是字符串 → keyField → keyGetter → 兜底字段 "key"。
     *
     * argKeyIndex 传 -1 表示只看接收者对象——长按入口这类方法参数里通常没有 key，
     * key 挂在 row/view 控制器对象上，这时就用 -1 + keyGetter。
     */
    static String resolveKey(Object[] args, int index, String keyField, String keyGetter,
                             Object thisObject) {
        if (index >= 0 && args != null && index < args.length) {
            String k = extract(args[index], keyField, keyGetter);
            if (k != null) {
                return k;
            }
        }
        return extract(thisObject, keyField, keyGetter);
    }

    private static String extract(Object o, String keyField, String keyGetter) {
        if (o == null) {
            return null;
        }
        if (o instanceof String) {
            return (String) o;
        }
        if (o instanceof CharSequence) {
            return o.toString();
        }
        if (keyField != null && !keyField.isEmpty()) {
            try {
                Object v = XposedHelpers.getObjectField(o, keyField);
                if (v instanceof String) {
                    return (String) v;
                }
            } catch (Throwable ignored) {
                // 继续尝试下一种
            }
        }
        if (keyGetter != null && !keyGetter.isEmpty()) {
            // 支持链式 getter，例如 "getEntry.getKey"：
            // 长按入口这类对象上往往没有直接的 getKey()，key 藏在下层对象里
            Object cur = o;
            String[] segs = keyGetter.split("\\.");
            for (String seg : segs) {
                if (cur == null) {
                    break;
                }
                try {
                    cur = XposedHelpers.callMethod(cur, seg);
                } catch (Throwable t) {
                    cur = null;
                }
            }
            if (cur instanceof CharSequence) {
                return cur.toString();
            }
        }
        try {
            Object v = XposedHelpers.getObjectField(o, "key");
            if (v instanceof String) {
                return (String) v;
            }
        } catch (Throwable ignored) {
            // 放弃
        }
        return null;
    }

    static void setField(Object target, String name, Object value) {
        if (value instanceof Boolean) {
            XposedHelpers.setBooleanField(target, name, (Boolean) value);
        } else if (value instanceof Integer) {
            XposedHelpers.setIntField(target, name, (Integer) value);
        } else if (value instanceof Long) {
            XposedHelpers.setLongField(target, name, (Long) value);
        } else {
            XposedHelpers.setObjectField(target, name, value);
        }
    }

    private static void hit(String kind, String what, Object value) {
        int n = sHitLog.incrementAndGet();
        if (n <= HIT_LOG_LIMIT) {
            XLog.i(kind + " 命中: " + what + " -> " + value);
            if (n == HIT_LOG_LIMIT) {
                XLog.i("（命中日志已达上限 " + HIT_LOG_LIMIT + " 条，后续静默）");
            }
        }
    }

    private static JSONArray parseArray(String json, String key) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return new JSONArray(json);
        } catch (Throwable t) {
            XLog.e("配置 " + key + " 不是合法 JSON 数组，已忽略", t);
            return null;
        }
    }
}