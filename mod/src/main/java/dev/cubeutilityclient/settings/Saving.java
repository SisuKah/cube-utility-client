package dev.cubeutilityclient.settings;

import dev.cubeutilityclient.CubeUtilityClient;
import java.lang.reflect.Method;
import net.minecraft.client.gui.screens.Screen;

/**
 * Settings made with Cloth Config or YACL only save when their own Save button is pressed. On
 * the settings page the player just moves on to another mod or closes the page, so changes
 * they made are saved then. Other mods save as they go, or when their screen closes.
 */
final class Saving {
    private Saving() {
    }

    static void saveIfEdited(Screen screen) {
        try {
            if (isA(screen.getClass(), "me.shedaniel.clothconfig2.gui.AbstractConfigScreen")) {
                Class<?> type = Class.forName("me.shedaniel.clothconfig2.gui.AbstractConfigScreen");
                if ((boolean) type.getMethod("isEdited").invoke(screen)) {
                    type.getMethod("saveAll", boolean.class).invoke(screen, false);
                }
            } else if (isA(screen.getClass(), "dev.isxander.yacl3.gui.YACLScreen")) {
                saveYacl(screen);
            }
        } catch (Throwable error) {
            CubeUtilityClient.LOGGER.warn("Couldn't save the changes in {}", screen.getClass().getName(), error);
        }
    }

    /** Applies every changed option, then runs the config's save, as YACL's Save button does. */
    private static void saveYacl(Screen screen) throws ReflectiveOperationException {
        Object config = Class.forName("dev.isxander.yacl3.gui.YACLScreen").getField("config").get(screen);
        boolean changed = false;
        for (Object category : (Iterable<?>) call(config, "categories")) {
            for (Object group : (Iterable<?>) call(category, "groups")) {
                for (Object option : (Iterable<?>) call(group, "options")) {
                    if ((boolean) call(option, "changed")) {
                        call(option, "applyValue");
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            ((Runnable) call(config, "saveFunction")).run();
        }
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = findPublic(target.getClass(), name);
        return method.invoke(target);
    }

    /** The method as an interface or public class declares it, so implementation classes don't get in the way. */
    private static Method findPublic(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> face : c.getInterfaces()) {
                try {
                    return face.getMethod(name);
                } catch (NoSuchMethodException ignored) {
                    // Keep looking.
                }
            }
        }
        Method method = type.getMethod(name);
        method.setAccessible(true);
        return method;
    }

    private static boolean isA(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
