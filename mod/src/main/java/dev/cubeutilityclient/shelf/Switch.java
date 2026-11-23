package dev.cubeutilityclient.shelf;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * One on/off setting inside another mod, named by the path to it: a class, then fields and
 * no-argument methods, ending at a boolean field, like
 * {@code com.keyshow.KeyShowConfig.get().enabled}. The class is everything up to the first part
 * that starts with a capital letter, as Java names go.
 *
 * <p>The path is followed again on every read and write, never cached, so a mod that swaps in a
 * new settings object when it reloads them is still reached. That only happens when a mod is got,
 * put away, or the game starts or joins a world, never while drawing.
 */
final class Switch {
    final String path;
    private final String className;
    private final String[] members;

    Switch(String path) {
        this.path = path;
        String[] parts = path.split("\\.");
        int classEnd = -1;
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty() && Character.isUpperCase(parts[i].charAt(0))) {
                classEnd = i;
                break;
            }
        }
        if (classEnd < 0 || classEnd == parts.length - 1) {
            throw new IllegalArgumentException("Not a path to a setting: " + path);
        }
        this.className = String.join(".", Arrays.copyOfRange(parts, 0, classEnd + 1));
        this.members = Arrays.copyOfRange(parts, classEnd + 1, parts.length);
    }

    boolean get() throws ReflectiveOperationException {
        Place place = this.locate();
        return place.field.getBoolean(place.owner);
    }

    void set(boolean value) throws ReflectiveOperationException {
        Place place = this.locate();
        place.field.setBoolean(place.owner, value);
    }

    private record Place(Field field, Object owner) {
    }

    private Place locate() throws ReflectiveOperationException {
        Class<?> type = Class.forName(this.className, true, Switch.class.getClassLoader());
        Object owner = null;
        for (int i = 0; i < this.members.length - 1; i++) {
            String member = this.members[i];
            if (member.endsWith("()")) {
                Method method = method(type, member.substring(0, member.length() - 2));
                owner = method.invoke(Modifier.isStatic(method.getModifiers()) ? null : owner);
            } else {
                Field field = field(type, member);
                owner = field.get(Modifier.isStatic(field.getModifiers()) ? null : owner);
            }
            if (owner == null) {
                // The mod hasn't made its settings yet.
                throw new IllegalStateException(member + " is not there yet");
            }
            type = owner.getClass();
        }
        Field field = field(type, this.members[this.members.length - 1]);
        if (field.getType() != boolean.class || Modifier.isFinal(field.getModifiers())) {
            throw new IllegalStateException("not an on/off setting");
        }
        return new Place(field, Modifier.isStatic(field.getModifiers()) ? null : owner);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Look in the parent class.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method method(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Look in the parent class.
            }
        }
        throw new NoSuchMethodException(name + "()");
    }

    @Override
    public String toString() {
        return this.path;
    }
}
