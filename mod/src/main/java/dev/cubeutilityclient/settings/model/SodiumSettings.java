package dev.cubeutilityclient.settings.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Sodium's video settings, read from its option registry: Sodium's own pages, plus the pages
 * other mods add to it (Sodium Extra's). Changes apply as Sodium's Apply button applies them.
 */
final class SodiumSettings implements SettingsModel {
    private static final String STRUCTURE = "net.caffeinemc.mods.sodium.client.config.structure.";

    private final Object config;
    private final List<Page> pages;
    private final boolean complete;

    private SodiumSettings(Object config, List<Page> pages, boolean complete) {
        this.config = config;
        this.pages = pages;
        this.complete = complete;
    }

    private static Object config() {
        return Ref.staticField("net.caffeinemc.mods.sodium.client.config.ConfigManager", "CONFIG");
    }

    /** Whether a mod added its settings to Sodium's video settings (MoreCulling does). */
    static boolean has(String configId) {
        Object config = config();
        if (config == null) {
            return false;
        }
        for (Object mod : (Iterable<?>) Ref.call(config, "getModOptions")) {
            if (configId.equals(Ref.call(mod, "configId"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param include which mods' pages to show, by config id
     * @param screen the screen to return to from pages that open on their own screen
     * @param moreElsewhere the mod has more settings on its own screen than on its Sodium pages
     */
    static SettingsModel load(java.util.function.Predicate<String> include, java.util.function.Supplier<Screen> screen, boolean moreElsewhere) {
        Object config = config();
        if (config == null) {
            return null;
        }
        // Start from what's in effect: video settings can change elsewhere (F11, Minecraft's own menu).
        Ref.call(config, "resetAllOptionsFromBindings");
        Object restart = restartFlag();
        boolean complete = true;
        List<Page> pages = new ArrayList<>();
        Set<String> titles = new HashSet<>();
        for (Object mod : (Iterable<?>) Ref.call(config, "getModOptions")) {
            String configId = (String) Ref.call(mod, "configId");
            // Reese's Sodium Options only changes how Sodium's own screen looks, which isn't shown.
            if (!include.test(configId) || configId.startsWith("reeses")) {
                continue;
            }
            String modName = (String) Ref.call(mod, "name");
            for (Object page : (Iterable<?>) Ref.call(mod, "pages")) {
                String title = Ref.text(Ref.call(page, "name"));
                if (!titles.add(title)) {
                    title = modName + ": " + title;
                }
                if (Ref.isA(page, STRUCTURE + "ExternalPage")) {
                    Consumer<Screen> open = cast(Ref.call(page, "currentScreenConsumer"));
                    Setting button = new Setting.Action(title, "Opens on its own screen.", null, () -> open.accept(screen.get()));
                    pages.add(new Page(title, List.of(new Section("", List.of(button)))));
                    continue;
                }
                List<Section> sections = new ArrayList<>();
                boolean whole = true;
                for (Object group : (Iterable<?>) Ref.call(page, "groups")) {
                    List<Setting> settings = new ArrayList<>();
                    for (Object option : (Iterable<?>) Ref.call(group, "options")) {
                        Setting setting = setting(option, restart, screen);
                        if (setting == null) {
                            whole = false;
                            Models.skipped(modName, Ref.text(Ref.call(option, "getName")), option);
                        } else {
                            settings.add(setting);
                        }
                    }
                    if (!settings.isEmpty()) {
                        Object name = Ref.call(group, "name");
                        sections.add(new Section(name == null ? "" : Ref.text(name), List.copyOf(settings)));
                    }
                }
                whole &= !moreElsewhere;
                complete &= whole;
                if (!sections.isEmpty()) {
                    pages.add(new Page(title, List.copyOf(sections), whole));
                }
            }
        }
        return pages.isEmpty() ? null : new SodiumSettings(config, List.copyOf(pages), complete);
    }

    private static Object restartFlag() {
        Object flag = Ref.staticField("net.caffeinemc.mods.sodium.api.config.option.OptionFlag", "REQUIRES_GAME_RESTART");
        return flag == null ? null : Ref.call(flag, "getId");
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static Setting setting(Object option, Object restartFlag, java.util.function.Supplier<Screen> screen) {
        String name = Ref.text(Ref.call(option, "getName"));
        String description = Ref.text(Ref.call(option, "getTooltip"));
        BooleanSupplier enabled = () -> (boolean) Ref.call(option, "isEnabled");
        if (Ref.isA(option, STRUCTURE + "ExternalButtonOption")) {
            Consumer<Screen> open = cast(Ref.call(option, "getCurrentScreenConsumer"));
            return new Setting.Action(name, description, enabled, () -> open.accept(screen.get()));
        }
        if (!Ref.isA(option, STRUCTURE + "StatefulOption")) {
            return null;
        }
        description = withImpact(description, Ref.call(option, "getImpact"));
        boolean restart = Ref.contains(Ref.call(option, "getFlags"), restartFlag);
        if (Ref.isA(option, STRUCTURE + "BooleanOption")) {
            return new Setting.Toggle(name, description, restart, enabled, () -> Boolean.TRUE.equals(Ref.call(option, "getValidatedValue")),
                    on -> Ref.call(option, "modifyValue", on));
        }
        if (Ref.isA(option, STRUCTURE + "IntegerOption")) {
            Object range = Ref.call(option, "getSteppedValidator");
            int min = (int) Ref.call(range, "min"), max = (int) Ref.call(range, "max"), step = (int) Ref.call(range, "step");
            return new Setting.Slider(name, description, restart, enabled, min, max, Math.max(1, step),
                    () -> ((Integer) Ref.call(option, "getValidatedValue")).doubleValue(),
                    value -> Ref.call(option, "modifyValue", (int) Math.round(value)),
                    () -> Ref.text(Ref.call(option, "formatValue", (Integer) Ref.call(option, "getValidatedValue"))));
        }
        if (Ref.isA(option, STRUCTURE + "EnumOption")) {
            Object[] constants = ((Class<?>) Ref.call(option, "getEnumClass")).getEnumConstants();
            return new Setting.Choice(name, description, restart, enabled,
                    () -> allowed(option, constants).indexOf(Ref.call(option, "getValidatedValue")),
                    index -> {
                        List<Object> allowed = allowed(option, constants);
                        if (index >= 0 && index < allowed.size()) {
                            Ref.call(option, "modifyValue", allowed.get(index));
                        }
                    },
                    () -> allowed(option, constants).size(),
                    () -> Ref.text(Ref.call(option, "getElementName", Ref.call(option, "getValidatedValue"))));
        }
        return null;
    }

    /** The values the option takes right now; some depend on other settings or the hardware. */
    private static List<Object> allowed(Object option, Object[] constants) {
        List<Object> allowed = new ArrayList<>();
        for (Object constant : constants) {
            if ((boolean) Ref.call(option, "isValueAllowed", constant)) {
                allowed.add(constant);
            }
        }
        return allowed;
    }

    /** Sodium rates how much each option costs; the description ends with that. */
    private static String withImpact(String description, Object impact) {
        description = description.strip();
        if (!description.isEmpty() && !description.matches(".*[.!?)]$")) {
            description += ".";
        }
        if (!(impact instanceof Enum<?> level)) {
            return description;
        }
        String word = switch (level.name()) {
            case "LOW" -> "low";
            case "MEDIUM" -> "medium";
            case "HIGH" -> "high";
            case "VARIES" -> "varies";
            default -> level.name().toLowerCase(Locale.ROOT);
        };
        String line = "Performance impact: " + word + ".";
        return description.isEmpty() ? line : description + " " + line;
    }

    @Override
    public List<Page> pages() {
        return this.pages;
    }

    @Override
    public void save() {
        if ((boolean) Ref.call(this.config, "anyOptionChanged")) {
            Ref.call(this.config, "applyAllOptions");
        }
    }

    @Override
    public boolean complete() {
        return this.complete;
    }

    /** Whether Sodium is there to read from. */
    static boolean available() {
        try {
            Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager", false, Minecraft.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }
}
