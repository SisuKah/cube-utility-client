package dev.cubeutilityclient.settings.model;

import java.lang.reflect.Method;
import java.util.List;

/**
 * CubeCraft AutoVote's settings, read from the mod: on or off, whether the voting menus show,
 * and its timing. What to vote for in each game stays on its own screen, behind a button, since
 * that's a list of games each with its own choices (and a layout editor).
 */
final class AutoVoteSettings implements SettingsModel {
    private static final String CLIENT = "dev.cubeautovote.CubeAutoVoteClient";

    private final List<Page> pages;
    private final Method save;

    private AutoVoteSettings(List<Page> pages, Method save) {
        this.pages = pages;
        this.save = save;
    }

    /** @param ownScreen opens the mod's own settings screen, which comes back to the page */
    static SettingsModel load(Runnable ownScreen) throws ReflectiveOperationException {
        Class<?> client = Class.forName(CLIENT);
        Object manager = client.getMethod("manager").invoke(null);
        Object config = manager.getClass().getMethod("config").invoke(manager);
        Class<?> type = config.getClass();
        Method enabled = type.getMethod("isEnabled"), setEnabled = type.getMethod("setEnabled", boolean.class);
        Method headless = type.getMethod("isHeadless"), setHeadless = type.getMethod("setHeadless", boolean.class);
        Method delay = type.getMethod("getDelayMs"), setDelay = type.getMethod("setDelayMs", int.class);
        Method timeout = type.getMethod("getMenuTimeoutMs"), setTimeout = type.getMethod("setMenuTimeoutMs", int.class);
        int maxDelay = constant(type, "MAX_DELAY_MS", 5000), minTimeout = constant(type, "MIN_MENU_TIMEOUT_MS", 500), maxTimeout = constant(type, "MAX_MENU_TIMEOUT_MS", 10000);
        Method save = client.getMethod("saveConfig");

        List<Setting> rows = List.of(
                new Setting.Toggle("Auto vote", "Votes for your choices as soon as a game's voting item shows up in your hotbar.", false, null,
                        () -> (boolean) call(enabled, config), on -> call(setEnabled, config, on)),
                new Setting.Toggle("Vote without showing the menus", "Votes in the background: the voting menus never pop up on your screen. Off, they "
                        + "open and close by themselves, as if you clicked through them.", false, null, () -> (boolean) call(headless, config),
                        on -> call(setHeadless, config, on)),
                new Setting.Slider("Wait before voting", "How long to wait after the voting item shows up before voting starts.", false, null, 0, maxDelay, 50,
                        () -> (int) call(delay, config), value -> call(setDelay, config, (int) Math.round(value)), () -> call(delay, config) + " ms"),
                new Setting.Slider("Menu timeout", "How long to wait for a voting menu to open before trying again.", false, null, minTimeout, maxTimeout, 250,
                        () -> (int) call(timeout, config), value -> call(setTimeout, config, (int) Math.round(value)), () -> call(timeout, config) + " ms"),
                new Setting.Action("Choices for each game", "What to vote for in each game: map size, health, time of day and the rest. The voting "
                        + "layout editor is there too, for when CubeCraft changes its menus.", null, ownScreen));
        return new AutoVoteSettings(List.of(new Page("", List.of(new Section("", rows)))), save);
    }

    private static int constant(Class<?> type, String name, int fallback) {
        try {
            return type.getField(name).getInt(null);
        } catch (ReflectiveOperationException error) {
            return fallback;
        }
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(method.getName(), error);
        }
    }

    @Override
    public List<Page> pages() {
        return this.pages;
    }

    @Override
    public void save() {
        call(this.save, null);
    }

    @Override
    public boolean complete() {
        return true;
    }
}
