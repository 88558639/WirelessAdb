package dev.wirelessadb.autostart;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** libxposed API 102 不再提供 XposedHelpers，framework 侧一律用标准反射。 */
final class SysReflect {
    private SysReflect() {}

    static Class<?> findClass(ClassLoader loader, String... names) {
        for (String name : names) {
            try {
                return Class.forName(name, false, loader);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    static Object getField(Object target, String name) {
        if (target == null) return null;
        Field f = findField(target.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** AMS 在不同 ROM 上 Context 字段名可能变化：先试 mContext，再按类型扫描整条继承链。 */
    static Context findContext(Object target) {
        if (target == null) return null;
        Object primary = getField(target, "mContext");
        if (primary instanceof Context) return (Context) primary;
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(target);
                    if (value instanceof Context) return (Context) value;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    /**
     * 取 system_server 的系统 Context，不依赖 AMS 实例。
     * onSystemServerStarting 阶段 AMS 还没 ready，此时只有 ActivityThread 这条路。
     */
    static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = callStatic(at, "currentActivityThread", new Class<?>[]{});
            if (thread == null) return null;
            Object ctx = call(thread, "getSystemContext", new Class<?>[]{});
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 包含父类声明的所有同名方法（hookAllMethods 的等价物）。 */
    static List<Method> findMethods(Class<?> type, String name) {
        List<Method> out = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (name.equals(m.getName())) out.add(m);
                }
            } catch (Throwable ignored) { }
        }
        return out;
    }

    static Object callStatic(Class<?> type, String name, Class<?>[] signature, Object... args) {
        if (type == null) return null;
        try {
            Method m = type.getDeclaredMethod(name, signature);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Object call(Object target, String name, Class<?>[] signature, Object... args) {
        if (target == null) return null;
        Method m = findMethod(target.getClass(), name, signature);
        if (m == null) return null;
        try {
            return m.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] signature) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, signature);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    static String getSystemProperty(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object value = callStatic(sp, "get", new Class<?>[]{String.class, String.class}, key, def);
            return value instanceof String ? (String) value : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    static int getSystemPropertyInt(String key, int def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object value = callStatic(sp, "getInt", new Class<?>[]{String.class, int.class}, key, def);
            return value instanceof Integer ? (Integer) value : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    static void setSystemProperty(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method set = sp.getDeclaredMethod("set", String.class, String.class);
            set.setAccessible(true);
            set.invoke(null, key, value);
        } catch (Throwable t) {
            throw new IllegalStateException("SystemProperties.set(" + key + ") 失败: " + t, t);
        }
    }

    /** 诊断用：列出类里含关键字的方法签名，避免在未验证的 ROM 上臆造方法名。 */
    static String describeMethods(Class<?> type, String keyword) {
        if (type == null) return "<class not found>";
        StringBuilder out = new StringBuilder();
        String lower = keyword == null ? "" : keyword.toLowerCase();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }
            for (Method m : methods) {
                if (!lower.isEmpty() && !m.getName().toLowerCase().contains(lower)) continue;
                if (out.length() > 0) out.append(", ");
                out.append(m.getName()).append('(');
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) out.append(',');
                    out.append(params[i].getSimpleName());
                }
                out.append(')');
            }
        }
        return out.length() == 0 ? "<none>" : out.toString();
    }

    static String describeFields(Class<?> type, String keyword) {
        if (type == null) return "<class not found>";
        StringBuilder out = new StringBuilder();
        String lower = keyword == null ? "" : keyword.toLowerCase();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field f : fields) {
                if (!lower.isEmpty() && !f.getName().toLowerCase().contains(lower)) continue;
                if (out.length() > 0) out.append(", ");
                out.append(f.getName()).append(':').append(f.getType().getSimpleName());
                if (Modifier.isStatic(f.getModifiers())) out.append("[s]");
            }
        }
        return out.length() == 0 ? "<none>" : out.toString();
    }
}
