package com.miaokatze.gtmb.compat.internal;

import java.lang.reflect.Field;

/** Internal lookup for audited native schemas; subclasses take precedence and missing fields fail closed. */
public final class NativeFields {

    private NativeFields() {}

    public static Field find(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) {}
        }
        throw new NoSuchFieldException(name);
    }
}
