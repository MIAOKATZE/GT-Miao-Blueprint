package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.util.HatchElementBuilder;

/** Temporary GT beta bridge: older builders expose mCasingIndex without the later public getter. */
final class HologramHatchCasing {

    private static final Access UNAVAILABLE = new Access(null, null, null);
    private static final ClassValue<Access> ACCESSORS = new ClassValue<Access>() {

        @Override
        protected Access computeValue(Class<?> type) {
            try {
                Method factory = type.getEnclosingMethod();
                if (factory == null || factory.getDeclaringClass() != HatchElementBuilder.class
                    || !"build".equals(factory.getName())) return UNAVAILABLE;
                Field enclosing = type.getDeclaredField("this$0");
                if (!enclosing.isSynthetic() || enclosing.getType() != HatchElementBuilder.class) return UNAVAILABLE;
                enclosing.setAccessible(true);
                return new Access(enclosing, getter(), betaField());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailableVisual) {
                return UNAVAILABLE;
            }
        }
    };

    private HologramHatchCasing() {}

    static int index(IStructureElement<?> element) {
        for (IStructureElement<?> part : HologramElementCatalog.parts(element)) {
            int index = ACCESSORS.get(part.getClass())
                .read(part);
            if (index >= 0) return index;
        }
        return -1;
    }

    private static Method getter() {
        try {
            Method getter = HatchElementBuilder.class.getMethod("getCasingIndex");
            return !Modifier.isStatic(getter.getModifiers()) && indexType(getter.getReturnType()) ? getter : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailableVisual) {
            return null;
        }
    }

    private static Field betaField() {
        try {
            // This exact field is shared by the reviewed beta and RC builders; never guess another numeric field.
            Field field = HatchElementBuilder.class.getDeclaredField("mCasingIndex");
            if (Modifier.isStatic(field.getModifiers()) || !indexType(field.getType())) return null;
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailableVisual) {
            return null;
        }
    }

    private static boolean indexType(Class<?> type) {
        return type == int.class || type == Integer.class;
    }

    private static final class Access {

        private final Field enclosing, fallback;
        private final Method getter;

        private Access(Field enclosing, Method getter, Field fallback) {
            this.enclosing = enclosing;
            this.getter = getter;
            this.fallback = fallback;
        }

        private int read(Object element) {
            if (enclosing == null) return -1;
            Object builder;
            try {
                builder = enclosing.get(element);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailableVisual) {
                return -1;
            }
            if (builder == null) return -1;
            if (getter != null) {
                try {
                    Object value = getter.invoke(builder);
                    if (value instanceof Integer) return (Integer) value;
                } catch (InvocationTargetException failedGetter) {
                    Throwable cause = failedGetter.getCause();
                    if (cause instanceof Error && !(cause instanceof LinkageError)) throw (Error) cause;
                    // An unavailable optional getter can still expose the reviewed beta field.
                } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailableVisual) {}
            }
            if (fallback != null) {
                try {
                    Object value = fallback.get(builder);
                    if (value instanceof Integer) return (Integer) value;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailableVisual) {}
            }
            return -1;
        }
    }
}
