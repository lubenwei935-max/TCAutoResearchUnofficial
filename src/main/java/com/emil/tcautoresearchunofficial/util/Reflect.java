package com.emil.tcautoresearchunofficial.util;

import java.lang.reflect.Field;

/**
 * 极小的反射工具，只用于读取神秘时代 / 原版 GUI 的尺寸字段。
 *
 * <p>之所以不用 Mixin：本模组现在完全靠 Forge 事件工作，不再往别人的类里插字节码，
 * 也就不会和 NEI / NEA / 其它 Mixin 模组抢同一个目标类。</p>
 */
public final class Reflect {

    private Reflect() {
    }

    public static Field find(Class<?> type, String... candidates) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (String name : candidates) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    // 试下一个候选名（MCP 名 / SRG 名）
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public static int getInt(Object target, int fallback, String... candidates) {
        if (target == null) {
            return fallback;
        }
        Field field = find(target.getClass(), candidates);
        if (field == null) {
            return fallback;
        }
        try {
            return field.getInt(target);
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static Object get(Object target, String... candidates) {
        if (target == null) {
            return null;
        }
        Field field = find(target.getClass(), candidates);
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
