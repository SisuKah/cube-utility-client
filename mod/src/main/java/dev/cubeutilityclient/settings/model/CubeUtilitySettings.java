package dev.cubeutilityclient.settings.model;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Cube Utility's settings, read from the mod itself: a chip for each of its categories, and on
 * each, every feature with its on/off switch and its own settings under it, the way its own
 * screen groups them. General has the settings for the whole mod and Edit HUD. The mod saves
 * every change itself; this only asks it to write the file straight away.
 */
final class CubeUtilitySettings implements SettingsModel {
    private static final String PACKAGE = "dev.cubeutility.";

    private final List<Page> pages;
    private final boolean complete;
    private final Runnable save;

    private CubeUtilitySettings(List<Page> pages, boolean complete, Runnable save) {
        this.pages = pages;
        this.complete = complete;
        this.save = save;
    }

    /** The mod's classes and the methods this reads, found once. */
    private static final class Api {
        final Method label, description, get, set, visible, display, on, min, max, step, value, choices, accepts;
        final Method name, featureDescription, enabled, setEnabled, blockedBy, settings, actions, all;
        final Method actionLabel, actionDescription, actionButton, actionClick;
        final Class<?> bool, integer, choice, text;

        Api() throws ReflectiveOperationException {
            Class<?> setting = type("common.settings.Setting");
            this.bool = type("common.settings.BoolSetting");
            this.integer = type("common.settings.IntSetting");
            this.choice = type("common.settings.EnumSetting");
            this.text = type("common.settings.StringSetting");
            this.label = setting.getMethod("label");
            this.description = setting.getMethod("description");
            this.get = setting.getMethod("get");
            this.set = setting.getMethod("set", Object.class);
            this.visible = setting.getMethod("isVisible");
            this.display = setting.getMethod("display");
            this.on = this.bool.getMethod("on");
            this.min = this.integer.getMethod("min");
            this.max = this.integer.getMethod("max");
            this.step = this.integer.getMethod("step");
            this.value = this.integer.getMethod("value");
            this.choices = this.choice.getMethod("choices");
            this.accepts = this.text.getMethod("accepts", String.class);
            Class<?> feature = type("feature.Feature");
            this.name = feature.getMethod("name");
            this.featureDescription = feature.getMethod("description");
            this.enabled = feature.getMethod("isEnabled");
            this.setEnabled = feature.getMethod("setEnabled", boolean.class);
            this.blockedBy = feature.getMethod("blockedBy");
            this.settings = feature.getMethod("settings");
            this.actions = feature.getMethod("actions");
            this.all = type("common.settings.SettingsGroup").getMethod("all");
            Class<?> action = type("feature.FeatureAction");
            this.actionLabel = action.getMethod("label");
            this.actionDescription = action.getMethod("description");
            this.actionButton = action.getMethod("button");
            this.actionClick = action.getMethod("onClick");
        }
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(PACKAGE + name);
    }

    /** Calls a method the mod has; anything that goes wrong is the caller's to catch. */
    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(method.getName(), error);
        }
    }

    /** @param page the screen to come back to from Edit HUD */
    static SettingsModel load(Supplier<Screen> page) throws ReflectiveOperationException {
        Api api = new Api();
        Class<?> main = type("CubeUtility");
        Object mod = main.getMethod("get").invoke(null);
        Object features = main.getMethod("features").invoke(mod);
        Object config = main.getMethod("config").invoke(mod);
        Method in = features.getClass().getMethod("in", type("feature.Category"));
        Class<?> category = type("feature.Category");
        Method categoryLabel = category.getMethod("label"), categoryDescription = category.getMethod("description");
        boolean[] complete = {true};

        List<Page> pages = new ArrayList<>();
        for (Object kind : category.getEnumConstants()) {
            List<?> found = (List<?>) in.invoke(features, kind);
            if (found.isEmpty()) {
                continue;
            }
            List<Section> sections = new ArrayList<>();
            String about = (String) categoryDescription.invoke(kind);
            sections.add(new Section("", List.of(new Setting.Note(about.endsWith(".") ? about : about + "."))));
            for (Object feature : found) {
                sections.add(feature(api, feature, complete));
            }
            pages.add(new Page((String) categoryLabel.invoke(kind), List.copyOf(sections)));
        }

        // General: the settings for the whole mod, and Edit HUD for its widgets.
        List<Setting> general = new ArrayList<>();
        Class<?> hudEditor = type("hud.HudEditorScreen");
        general.add(new Setting.Action("Edit HUD", "Move and resize the widgets the features draw on your screen.", null, () -> {
            try {
                Minecraft.getInstance().gui.setScreen((Screen) hudEditor.getConstructor(Screen.class).newInstance(page.get()));
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        }));
        Object generalSettings = main.getMethod("general").invoke(mod);
        Object group = generalSettings.getClass().getField("group").get(generalSettings);
        for (Object setting : (List<?>) api.all.invoke(group)) {
            // The one-time welcome line isn't a setting players change.
            if (!"welcomed".equals(setting.getClass().getMethod("key").invoke(setting))) {
                add(general, setting(api, setting, () -> true), complete);
            }
        }
        pages.add(new Page("General", List.of(new Section("", List.copyOf(general)))));

        Method write = config.getClass().getMethod("save");
        return new CubeUtilitySettings(List.copyOf(pages), complete[0], () -> call(write, config));
    }

    /** A feature: its switch, then its buttons and settings, which only matter while it's on. */
    private static Section feature(Api api, Object feature, boolean[] complete) {
        List<Setting> rows = new ArrayList<>();
        String name = (String) call(api.name, feature);
        boolean blocked = call(api.blockedBy, feature) != null;
        rows.add(new Setting.Toggle(name, (String) call(api.featureDescription, feature), false, () -> !blocked, () -> (boolean) call(api.enabled, feature),
                on -> call(api.setEnabled, feature, on)));
        BooleanSupplier on = () -> (boolean) call(api.enabled, feature);
        for (Object action : (List<?>) call(api.actions, feature)) {
            Supplier<?> label = (Supplier<?>) call(api.actionLabel, action), description = (Supplier<?>) call(api.actionDescription, action);
            Supplier<?> button = (Supplier<?>) call(api.actionButton, action);
            Runnable click = (Runnable) call(api.actionClick, action);
            rows.add(new Setting.Action(String.valueOf(label.get()), String.valueOf(description.get()), on, () -> String.valueOf(button.get()), click).asChild());
        }
        for (Object setting : (List<?>) call(api.all, call(api.settings, feature))) {
            Setting row = setting(api, setting, on);
            if (row == null) {
                complete[0] = false;
            } else {
                rows.add(row.asChild());
            }
        }
        return new Section("", List.copyOf(rows));
    }

    private static void add(List<Setting> rows, Setting row, boolean[] complete) {
        if (row == null) {
            complete[0] = false;
        } else {
            rows.add(row);
        }
    }

    /** One of the mod's settings as the page's own kind, or null for a kind this doesn't know. */
    private static Setting setting(Api api, Object setting, BooleanSupplier parent) {
        String label = (String) call(api.label, setting), description = (String) call(api.description, setting);
        // Settings that only apply to some of a feature's choices are greyed out the rest of the time.
        BooleanSupplier enabled = () -> parent.getAsBoolean() && (boolean) call(api.visible, setting);
        if (api.bool.isInstance(setting)) {
            return new Setting.Toggle(label, description, false, enabled, () -> (boolean) call(api.on, setting), value -> call(api.set, setting, value));
        }
        if (api.integer.isInstance(setting)) {
            int min = (int) call(api.min, setting), max = (int) call(api.max, setting), step = (int) call(api.step, setting);
            return new Setting.Slider(label, description, false, enabled, min, max, step, () -> (int) call(api.value, setting),
                    value -> call(api.set, setting, (int) Math.round(value)), () -> (String) call(api.display, setting));
        }
        if (api.choice.isInstance(setting)) {
            Object[] choices = (Object[]) call(api.choices, setting);
            return new Setting.Choice(label, description, false, enabled, () -> indexOf(choices, call(api.get, setting)), index -> call(api.set, setting, choices[index]),
                    () -> choices.length, () -> (String) call(api.display, setting));
        }
        if (api.text.isInstance(setting)) {
            return new Setting.Text(label, description, false, enabled, () -> String.valueOf(call(api.get, setting)), value -> {
                if (!(boolean) call(api.accepts, setting, value)) {
                    return false;
                }
                call(api.set, setting, value);
                return true;
            });
        }
        return null;
    }

    private static int indexOf(Object[] values, Object value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public List<Page> pages() {
        return this.pages;
    }

    /** The mod saves a moment after every change by itself; this writes it straight away. */
    @Override
    public void save() {
        this.save.run();
    }

    @Override
    public boolean complete() {
        return this.complete;
    }
}
