package com.notifshelter.miui;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 候选类归类与筛选。纯逻辑，不依赖 Android API，应用侧和 SystemUI 侧共用。
 *
 * 关键词按「用途分组」，因为定位收纳逻辑要分几步走：
 * 先找到通知的数据集合（组②），再找到「决定某条通知进不进折叠区」的判定点（组①/④），
 * 最后找到长按菜单的构建点（组⑤）。
 */
public final class ClassCatalog {

    public static final class Group {
        public final String title;
        public final String[] keywords;

        Group(String title, String... keywords) {
            this.title = title;
            this.keywords = keywords;
        }
    }

    public static final Group[] GROUPS = new Group[]{
            new Group("① 收纳 / 折叠 / 分区  —— 最可能藏着「不常用通知」判定",
                    "uncommon", "shelter", "fold", "collapse", "collapsed", "section",
                    "bundle", "overflow", "silent", "minimi", "summar", "archive",
                    "drawer", "tidy", "expand"),
            new Group("② 通知集合与管线",
                    "notifcollection", "notifpipeline", "coordinator", "notifstub",
                    "notifevent", "notifsummary", "notifcoordinat", "notifsection"),
            new Group("③ 排序 / 重要性 / 打断",
                    "ranking", "rankingmap", "importance", "interruption",
                    "sbnkey", "notificationkey"),
            new Group("④ 分组与堆叠 / 视图",
                    "groupmanager", "grouping", "sectionmanager", "sectionheader",
                    "stackscroll", "stacklayout", "shelf", "notifrow", "notifview",
                    "notifstack", "notiflist", "notifpanel", "notifinbox", "notifupdate"),
            new Group("⑤ 长按菜单 / 手势",
                    "menuro", "menuitem", "menurow", "longclick", "longpress",
                    "guts", "rowmenu", "menupresenter", "notifmenu", "touchhandler"),
            new Group("⑥ MIUI 定制类",
                    "miui"),
    };

    private ClassCatalog() {
    }

    /** 把 DEX descriptor（Lcom/a/B;）转成二进制类名（com.a.B）。 */
    public static String toBinaryName(String descriptor) {
        if (descriptor == null) {
            return null;
        }
        String s = descriptor;
        if (s.startsWith("L") && s.endsWith(";") && s.length() > 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace('/', '.');
    }

    public static String simpleName(String binary) {
        int i = binary.lastIndexOf('.');
        return i < 0 ? binary : binary.substring(i + 1);
    }

    /** 只关心 SystemUI / MIUI 自己的类，过滤掉 android.* / kotlin.* 等噪音。 */
    public static boolean isCandidatePackage(String binary) {
        return binary.startsWith("com.android.systemui")
                || binary.startsWith("com.miui")
                || binary.startsWith("miui.systemui")
                || binary.startsWith("com.android.internal.systemui");
    }

    public static boolean matches(String binary, String[] keywords) {
        String lower = binary.toLowerCase(Locale.ROOT);
        String simple = simpleName(binary).toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            if ("miui".equals(k)) {
                if (lower.contains("miui")) {
                    return true;
                }
                continue;
            }
            if (simple.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把全量类名按组分桶。
     *
     * @param maxPerGroup 每组上限，避免报告爆炸
     */
    public static Map<String, List<String>> bucket(Set<String> allBinaries, int maxPerGroup) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        List<String> candidates = new ArrayList<>();
        for (String b : allBinaries) {
            if (isCandidatePackage(b)) {
                candidates.add(b);
            }
        }
        java.util.Collections.sort(candidates);

        for (Group g : GROUPS) {
            List<String> hits = new ArrayList<>();
            for (String b : candidates) {
                if (matches(b, g.keywords)) {
                    if (hits.size() >= maxPerGroup) {
                        hits.add("… 已截断，仅显示前 " + maxPerGroup + " 个（可调大 recon_max_classes）");
                        break;
                    }
                    hits.add(b);
                }
            }
            result.put(g.title, hits);
        }
        return result;
    }

    /** 反射打印某个类的方法签名。必须在能加载该类进程里调用（即 SystemUI 进程）。 */
    public static List<String> dumpMethods(String binaryName, ClassLoader cl) {
        List<String> out = new ArrayList<>();
        Class<?> c;
        try {
            c = Class.forName(binaryName, false, cl);
        } catch (Throwable t) {
            out.add("  <无法加载: " + XLog.describe(t) + ">");
            return out;
        }
        try {
            out.add("  父类: " + (c.getSuperclass() == null ? "-" : c.getSuperclass().getName()));
            Class<?>[] ifaces = c.getInterfaces();
            if (ifaces.length > 0) {
                StringBuilder sb = new StringBuilder("  接口: ");
                for (int i = 0; i < ifaces.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(ifaces[i].getName());
                }
                out.add(sb.toString());
            }
        } catch (Throwable t) {
            out.add("  父类/接口读取失败: " + XLog.describe(t));
        }

        Method[] methods;
        try {
            methods = c.getDeclaredMethods();
        } catch (Throwable t) {
            out.add("  <方法枚举失败: " + XLog.describe(t) + ">");
            return out;
        }
        java.util.Arrays.sort(methods, (a, b) -> {
            int r = a.getName().compareTo(b.getName());
            return r != 0 ? r : Integer.compare(a.getParameterCount(), b.getParameterCount());
        });
        for (Method m : methods) {
            out.add("  " + sign(m));
        }

        Constructor<?>[] ctors;
        try {
            ctors = c.getDeclaredConstructors();
        } catch (Throwable t) {
            return out;
        }
        for (Constructor<?> ct : ctors) {
            out.add("  <init>" + params(ct.getParameterTypes()));
        }
        return out;
    }

    public static String sign(Method m) {
        StringBuilder sb = new StringBuilder();
        int mod = m.getModifiers();
        if (Modifier.isPublic(mod)) {
            sb.append("public ");
        } else if (Modifier.isProtected(mod)) {
            sb.append("protected ");
        } else if (Modifier.isPrivate(mod)) {
            sb.append("private ");
        }
        if (Modifier.isStatic(mod)) {
            sb.append("static ");
        }
        if (m.isSynthetic()) {
            sb.append("synthetic ");
        }
        sb.append(shortType(m.getReturnType().getName())).append(' ')
                .append(m.getName()).append(params(m.getParameterTypes()));
        return sb.toString();
    }

    public static String params(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types[i].getName());
        }
        return sb.append(')').toString();
    }

    private static String shortType(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(i + 1);
    }
}