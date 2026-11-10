package com.miaokatze.gtmb.main;

/** Optional test variant bootstrap. Missing implementation lookup is cached to keep production hooks inert. */
public final class OptionalTestLab {

    private static final java.util.Set<String> ABSENT = java.util.Collections
        .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final java.util.Map<String, java.lang.reflect.Method> METHODS = new java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method>();

    private OptionalTestLab() {}

    public static Object call(String owner, String method, Object... arguments) {
        if (ABSENT.contains(owner)) return null;
        String key = owner + "#" + method + "#" + arguments.length;
        try {
            java.lang.reflect.Method target = METHODS.get(key);
            if (target == null) {
                Class<?> type = Class.forName(owner);
                for (java.lang.reflect.Method candidate : type.getDeclaredMethods()) {
                    if (!candidate.getName()
                        .equals(method) || candidate.getParameterTypes().length != arguments.length) continue;
                    candidate.setAccessible(true);
                    target = candidate;
                    METHODS.put(key, target);
                    break;
                }
                if (target == null) throw new NoSuchMethodException(owner + "." + method);
            }
            return target.invoke(null, arguments);
        } catch (ClassNotFoundException absent) {
            ABSENT.add(owner);
            return null;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Optional testlab hook failed: " + owner + "." + method, failure);
        }
    }
}
