package com.cameomcp.plugin.tools.artifacts;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Late-bound calls for Cameo API members whose exact signature is not documented or differs
 * between 2024x and 2026x (see stubs/API-DIFFERENCES.md). Methods are matched by name, arity and
 * argument compatibility (boxing aware), so int/Integer or supertype parameter drift still resolves.
 */
public final class Reflect {

    private Reflect() {
    }

    /** Invoke a public instance method; NoSuchMethodException when no compatible overload exists. */
    public static Object invoke(Object target, String name, Object... args) throws Exception {
        Method m = find(target.getClass(), name, false, args);
        return call(m, target, args);
    }

    /** Invoke a public static method. */
    public static Object invokeStatic(Class<?> type, String name, Object... args) throws Exception {
        Method m = find(type, name, true, args);
        return call(m, null, args);
    }

    /** Instance method result, or null when the method is missing or fails. */
    public static Object tryInvoke(Object target, String name, Object... args) {
        try {
            return invoke(target, name, args);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    public static boolean hasMethod(Class<?> type, String name, int arity) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity) {
                return true;
            }
        }
        return false;
    }

    private static Object call(Method m, Object target, Object[] args) throws Exception {
        try {
            m.setAccessible(true);
        } catch (RuntimeException ignored) {
            // public API method; accessibility only matters for non-public declaring classes
        }
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) {
                throw (Exception) c;
            }
            if (c instanceof Error) {
                throw (Error) c;
            }
            throw e;
        }
    }

    static Method find(Class<?> type, String name, boolean wantStatic, Object[] args) throws NoSuchMethodException {
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length
                    || Modifier.isStatic(m.getModifiers()) != wantStatic) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < params.length && ok; i++) {
                ok = compatible(params[i], args[i]);
            }
            if (ok) {
                return m;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name + " with " + args.length + " argument(s)");
    }

    static boolean compatible(Class<?> param, Object arg) {
        if (arg == null) {
            return !param.isPrimitive();
        }
        Class<?> boxed = box(param);
        if (boxed.isInstance(arg)) {
            return true;
        }
        // widening of numbers (an Integer passed to a long parameter, ...)
        if (param.isPrimitive() && arg instanceof Number && (boxed == Long.class || boxed == Double.class || boxed == Float.class)) {
            return arg instanceof Integer || arg instanceof Short || arg instanceof Byte;
        }
        return false;
    }

    static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == int.class) {
            return Integer.class;
        }
        if (c == boolean.class) {
            return Boolean.class;
        }
        if (c == long.class) {
            return Long.class;
        }
        if (c == double.class) {
            return Double.class;
        }
        if (c == float.class) {
            return Float.class;
        }
        if (c == short.class) {
            return Short.class;
        }
        if (c == byte.class) {
            return Byte.class;
        }
        if (c == char.class) {
            return Character.class;
        }
        return Void.class;
    }
}
