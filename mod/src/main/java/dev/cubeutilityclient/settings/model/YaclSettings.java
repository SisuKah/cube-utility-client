package dev.cubeutilityclient.settings.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Settings made with YACL (Yet Another Config Lib), read from the screen the mod builds. */
final class YaclSettings implements SettingsModel {
    private static final String API = "dev.isxander.yacl3.api.";
    private static final String CONTROLLERS = "dev.isxander.yacl3.gui.controllers.";

    private final Object config;
    private final List<Page> pages;
    private final List<Object> options;
    private final boolean complete;

    private YaclSettings(Object config, List<Page> pages, List<Object> options, boolean complete) {
        this.config = config;
        this.pages = pages;
        this.options = options;
        this.complete = complete;
    }

    static SettingsModel from(Screen screen) {
        if (!Ref.isA(screen, "dev.isxander.yacl3.gui.YACLScreen")) {
            return null;
        }
        Object config = Ref.field(screen, "config");
        Object restart = Ref.staticField(API + "OptionFlag", "GAME_RESTART");
        List<Page> pages = new ArrayList<>();
        List<Object> options = new ArrayList<>();
        boolean complete = true;
        for (Object category : (Iterable<?>) Ref.call(config, "categories")) {
            List<Section> sections = new ArrayList<>();
            boolean whole = true;
            for (Object group : (Iterable<?>) Ref.call(category, "groups")) {
                if (Ref.isA(group, API + "ListOption")) {
                    // Lists of entries the player adds to: only the mod's own screen has those.
                    whole = false;
                    continue;
                }
                List<Setting> settings = new ArrayList<>();
                for (Object option : (Iterable<?>) Ref.call(group, "options")) {
                    Setting setting = setting(option, restart);
                    if (setting == null) {
                        whole = false;
                        Models.skipped(Ref.text(Ref.call(config, "title")), Ref.text(Ref.call(option, "name")), Ref.call(option, "controller"));
                    } else {
                        settings.add(setting);
                        options.add(option);
                    }
                }
                if (!settings.isEmpty()) {
                    boolean root = (boolean) Ref.call(group, "isRoot");
                    sections.add(new Section(root ? "" : Ref.text(Ref.call(group, "name")), List.copyOf(settings)));
                }
            }
            complete &= whole;
            if (!sections.isEmpty()) {
                pages.add(new Page(Ref.text(Ref.call(category, "name")), List.copyOf(sections), whole));
            }
        }
        return pages.isEmpty() ? null : new YaclSettings(config, List.copyOf(pages), options, complete);
    }

    private static Setting setting(Object option, Object restartFlag) {
        if (Ref.isA(option, API + "LabelOption")) {
            return new Setting.Note(Ref.text(Ref.call(option, "label")));
        }
        if (Ref.isA(option, API + "ButtonOption")) {
            return null;
        }
        String name = Ref.text(Ref.call(option, "name"));
        String description = Ref.text(Ref.call(Ref.call(option, "description"), "text"));
        boolean restart = Ref.contains(Ref.call(option, "flags"), restartFlag);
        BooleanSupplier enabled = () -> (boolean) Ref.call(option, "available");
        Object controller = Ref.call(option, "controller");
        if (Ref.isA(controller, CONTROLLERS + "BooleanController") || Ref.isA(controller, CONTROLLERS + "TickBoxController")) {
            return new Setting.Toggle(name, description, restart, enabled, () -> Boolean.TRUE.equals(Ref.call(option, "pendingValue")),
                    on -> Ref.call(option, "requestSet", on));
        }
        if (Ref.isA(controller, CONTROLLERS + "slider.ISliderController")) {
            double min = (double) Ref.call(controller, "min"), max = (double) Ref.call(controller, "max"), step = (double) Ref.call(controller, "interval");
            return new Setting.Slider(name, description, restart, enabled, min, max, step, () -> (double) Ref.call(controller, "pendingValue"),
                    value -> Ref.call(controller, "setPendingValue", value), () -> Ref.text(Ref.call(controller, "formatValue")));
        }
        if (Ref.isA(controller, CONTROLLERS + "cycling.ICyclingController")) {
            return new Setting.Choice(name, description, restart, enabled, () -> (int) Ref.call(controller, "getPendingValue"),
                    index -> Ref.call(controller, "setPendingValue", index), () -> (int) Ref.call(controller, "getCycleLength"),
                    () -> Ref.text(Ref.call(controller, "formatValue")));
        }
        if (Ref.isA(controller, CONTROLLERS + "dropdown.EnumDropdownController")) {
            Object value = Ref.call(option, "pendingValue");
            if (!(value instanceof Enum<?> current)) {
                return null;
            }
            Object[] constants = current.getDeclaringClass().getEnumConstants();
            return new Setting.Choice(name, description, restart, enabled, () -> ((Enum<?>) Ref.call(option, "pendingValue")).ordinal(),
                    index -> Ref.call(option, "requestSet", constants[index]), () -> constants.length, () -> Ref.text(Ref.call(controller, "formatValue")));
        }
        if (Ref.isA(controller, CONTROLLERS + "dropdown.AbstractDropdownController") && !(boolean) Ref.field(controller, "allowAnyValue")) {
            @SuppressWarnings("unchecked")
            List<String> allowed = List.copyOf((List<String>) Ref.call(controller, "getAllowedValues"));
            if (allowed.isEmpty()) {
                return null;
            }
            return Setting.Choice.ofNames(name, description, restart, enabled, () -> Math.max(0, allowed.indexOf((String) Ref.call(controller, "getString"))),
                    index -> Ref.call(controller, "setFromString", allowed.get(index)), allowed::get, allowed.size());
        }
        if (Ref.isA(controller, CONTROLLERS + "string.IStringController")) {
            return new Setting.Text(name, description, restart, enabled, () -> (String) Ref.call(controller, "getString"), text -> {
                if (!(boolean) Ref.call(controller, "isInputValid", text)) {
                    return false;
                }
                Ref.call(controller, "setFromString", text);
                return true;
            });
        }
        return null;
    }

    @Override
    public List<Page> pages() {
        return this.pages;
    }

    /** Applies every changed option and runs the config's save, as YACL's Save button does. */
    @Override
    @SuppressWarnings("unchecked")
    public void save() {
        Object restart = Ref.staticField(API + "OptionFlag", "GAME_RESTART");
        Set<Object> flags = new LinkedHashSet<>();
        boolean changed = false;
        for (Object option : this.options) {
            if ((boolean) Ref.call(option, "changed") && (boolean) Ref.call(option, "applyValue")) {
                changed = true;
                if (Ref.call(option, "flags") instanceof Set<?> set) {
                    flags.addAll(set);
                }
            }
        }
        if (!changed) {
            return;
        }
        ((Runnable) Ref.call(this.config, "saveFunction")).run();
        // Reloading chunks or resources as the option asks. A restart is left to the player:
        // YACL would show a screen asking for one, which would take over the page.
        for (Object flag : flags) {
            if (flag != restart && flag instanceof Consumer<?> consumer) {
                ((Consumer<Minecraft>) consumer).accept(Minecraft.getInstance());
            }
        }
    }

    @Override
    public boolean complete() {
        return this.complete;
    }
}
