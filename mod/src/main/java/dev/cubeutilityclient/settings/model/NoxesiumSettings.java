package dev.cubeutilityclient.settings.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.OptionInstance;

/**
 * Noxesium's settings: Minecraft-style options kept in two classes, which save themselves when
 * changed. Its own screen shows the interface options and a developer tab; the interface page
 * here also has its two options that otherwise sit in Minecraft's own menus.
 */
final class NoxesiumSettings implements SettingsModel {
    private static final String OPTIONS = "com.noxcrew.noxesium.core.fabric.config.NoxesiumOptions";
    private static final String VANILLA = "com.noxcrew.noxesium.core.fabric.config.VanillaOptions";

    private final List<Page> pages;

    private NoxesiumSettings(List<Page> pages) {
        this.pages = pages;
    }

    static SettingsModel load() {
        List<Section> interfaceSections = new ArrayList<>();
        add(interfaceSections, "", List.of(option(VANILLA, "RENDER_MAPS_AS_UI"), option(VANILLA, "MAP_UI_LOCATION")));
        List<OptionInstance<?>> positions = List.of(option(OPTIONS, "BOSS_BAR_POSITION"), option(OPTIONS, "SCOREBOARD_POSITION"), option(OPTIONS, "MAP_POSITION"));
        add(interfaceSections, "Positions", positions, reset("Reset positions", "Puts the boss bar, scoreboard and map back where they start.", positions));
        List<OptionInstance<?>> sizes = scales();
        add(interfaceSections, "Sizes", sizes, reset("Reset sizes", "Puts every size above back to 100%.", sizes));
        add(interfaceSections, "Controls", List.of(option(VANILLA, "RESET_TOGGLE_KEYS")));
        List<Section> developerSections = new ArrayList<>();
        add(developerSections, "", List.of(option(OPTIONS, "GAME_TIME_OVERLAY"), option(OPTIONS, "DUMP_INCOMING_PACKETS"), option(OPTIONS, "DUMP_OUTGOING_PACKETS"),
                option(OPTIONS, "DEBUG_SCOREBOARD_TEAMS"), option(OPTIONS, "EXTENDED_PACKET_LOGGING"), option(OPTIONS, "QIB_SYSTEM_VISUAL_DEBUG"),
                option(OPTIONS, "SHOW_CULLING_HITBOXES")));
        if (interfaceSections.isEmpty()) {
            return null;
        }
        List<Page> pages = new ArrayList<>();
        pages.add(new Page("Interface", List.copyOf(interfaceSections)));
        if (!developerSections.isEmpty()) {
            pages.add(new Page("Developer", List.copyOf(developerSections)));
        }
        return new NoxesiumSettings(List.copyOf(pages));
    }

    private static OptionInstance<?> option(String type, String name) {
        return Ref.staticField(type, name) instanceof OptionInstance<?> option ? option : null;
    }

    /** The size options for each part of the interface, in the order Noxesium lists them. */
    private static List<OptionInstance<?>> scales() {
        if (!(Ref.staticField(OPTIONS, "GUI_SCALES") instanceof Map<?, ?> map)) {
            return List.of();
        }
        List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparingInt(entry -> entry.getKey() instanceof Enum<?> key ? key.ordinal() : 0));
        List<OptionInstance<?>> options = new ArrayList<>();
        for (Map.Entry<?, ?> entry : entries) {
            if (entry.getValue() instanceof OptionInstance<?> option) {
                options.add(option);
            }
        }
        return options;
    }

    private static Setting reset(String name, String description, List<OptionInstance<?>> options) {
        return new Setting.Action(name, description, null, "RESET", () -> options.forEach(option -> {
            if (option != null) {
                VanillaOption.reset(option);
            }
        }));
    }

    private static void add(List<Section> sections, String title, List<OptionInstance<?>> options, Setting... extra) {
        List<Setting> settings = new ArrayList<>();
        for (OptionInstance<?> option : options) {
            Setting setting = option == null ? null : VanillaOption.of(option);
            if (setting != null) {
                settings.add(setting);
            }
        }
        if (!settings.isEmpty()) {
            settings.addAll(List.of(extra));
            sections.add(new Section(title, List.copyOf(settings)));
        }
    }

    @Override
    public List<Page> pages() {
        return this.pages;
    }

    /** Noxesium saves each option as it changes. */
    @Override
    public void save() {
    }

    @Override
    public boolean complete() {
        return true;
    }
}
