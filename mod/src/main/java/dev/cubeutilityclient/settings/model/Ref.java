package dev.cubeutilityclient.settings.model;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Reflection for reading settings libraries the client doesn't build against (YACL, Cloth
 * Config, Sodium): it works with whichever version is installed, and fails softly.
 */
final class Ref {
    private Ref() {
    }

    static boolean isA(Object value, String className) {
        if (value == null) {
            return false;
        }
        return isA(value.getClass(), className);
    }

    private static boolean isA(Class<?> type, String className) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(className)) {
                return true;
            }
            for (Class<?> face : c.getInterfaces()) {
                if (isA(face, className)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Calls a method by name with these arguments; public ones first, then any declared one. */
    static Object call(Object target, String name, Object... args) {
        try {
            Method method = find(target.getClass(), name, args);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("can't call " + name + " on " + target.getClass().getName(), error);
        }
    }

    static boolean has(Object target, String name, int arguments) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arguments) {
                    return true;
                }
            }
        }
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments) {
                return true;
            }
        }
        return false;
    }

    private static Method find(Class<?> type, String name, Object[] args) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && fits(method, args)) {
                return accessible(method);
            }
        }
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals(name) && fits(method, args)) {
                    return accessible(method);
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Method accessible(Method method) {
        if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            method.setAccessible(true);
        }
        return method;
    }

    private static boolean fits(Method method, Object[] args) {
        Class<?>[] types = method.getParameterTypes();
        if (types.length != args.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            if (args[i] != null && !box(types[i]).isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return type == int.class ? Integer.class : type == long.class ? Long.class : type == double.class ? Double.class : type == float.class ? Float.class
                : type == boolean.class ? Boolean.class : type == short.class ? Short.class : type == byte.class ? Byte.class : Character.class;
    }

    /** A field by name anywhere up the class chain, private ones included. */
    static Object field(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Keep looking higher up.
            } catch (IllegalAccessException error) {
                throw new IllegalStateException(error);
            }
        }
        throw new IllegalStateException("no field " + name + " on " + target.getClass().getName());
    }

    static Object staticField(String className, String name) {
        try {
            Field field = Class.forName(className).getField(name);
            return field.get(null);
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    /** A text component as plain text, without the § colour and style codes some mods write in. */
    static String text(Object component) {
        String text = component instanceof Component c ? c.getString() : component == null ? "" : String.valueOf(component);
        String plain = ChatFormatting.stripFormatting(text);
        return plain == null ? "" : plain;
    }

    /** Whether a collection the library returned (which may be null) holds the value. */
    static boolean contains(Object collection, Object value) {
        return value != null && collection instanceof java.util.Collection<?> items && items.contains(value);
    }
}
