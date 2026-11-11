package dev.cubeutilityclient.settings;

import com.mojang.blaze3d.platform.NativeImage;
import dev.cubeutilityclient.CubeUtilityClient;
import dev.cubeutilityclient.compat.ModMenuCompat;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/**
 * One tab on the settings page: a mod that has settings, named for what it does rather than
 * what it's called ("Video", not "Sodium"), with a line saying what it changes. Mods with
 * nothing to change don't get a tab; they stay installed and working.
 */
final class ModEntry {
    enum Group {
        CUBECRAFT("CUBECRAFT"),
        VIDEO("VIDEO AND PERFORMANCE"),
        EXTRAS("WINDOW AND EXTRAS"),
        TOOLS("TOOLS"),
        OTHER("OTHER MODS");

        final String label;

        Group(String label) {
            this.label = label;
        }
    }

    private record Label(String title, String blurb, Group group) {
    }

    /** The pack's mods with settings, named for what they do. Listed in this order. */
    private static final Map<String, Label> LABELS = Map.ofEntries(
            Map.entry("cubeutility", new Label("Cube Utility", "Timers, map layout, zoom, freelook, HUDs and many more CubeCraft helpers.", Group.CUBECRAFT)),
            Map.entry("ewoptimizer", new Label("Eggwars", "Fewer particles, tidier chat and no ad bars in Eggwars.", Group.CUBECRAFT)),
            Map.entry("cubeautovote", new Label("Auto vote", "Votes for your favourite game options in the lobby.", Group.CUBECRAFT)),
            Map.entry("lvl_rank", new Label("Levels and ranks", "Shows each player's CubeCraft level and rank next to their name.", Group.CUBECRAFT)),
            Map.entry("sodium", new Label("Video", "Graphics quality, render distance, frame rate and much more.", Group.VIDEO)),
            Map.entry("dynamic_fps", new Label("Background frame rate", "Saves power while Minecraft is in the background or idle.", Group.VIDEO)),
            Map.entry("entityculling", new Label("Hidden mobs", "Skips drawing mobs and chests you can't see, for more frames.", Group.VIDEO)),
            Map.entry("moreculling", new Label("Hidden blocks", "Skips drawing leaves, signs and item frames you can't see.", Group.VIDEO)),
            Map.entry("badoptimizations", new Label("Extra speed-ups", "Optimizations for things other than drawing, kept in a text file.", Group.VIDEO)),
            Map.entry("cwb", new Label("Fullscreen", "Borderless fullscreen that's quick to switch in and out of.", Group.EXTRAS)),
            Map.entry("spectatorinside", new Label("Spectator view", "In spectator mode, watch from inside another player's head.", Group.EXTRAS)),
            Map.entry("noxesium", new Label("Server features", "Extras servers can switch on, plus sizes and positions for the boss bar, chat and more.", Group.EXTRAS)),
            Map.entry("packetlogger", new Label("Packet logger", "Logs network packets, for tracking down problems.", Group.TOOLS)));
    private static final List<String> ORDER = List.of("cubeutility", "ewoptimizer", "cubeautovote", "lvl_rank", "sodium", "dynamic_fps", "entityculling", "moreculling",
            "badoptimizations", "cwb", "spectatorinside", "noxesium", "packetlogger");
    /** Never a tab: the game itself, libraries, and mods whose settings sit inside another tab. */
    private static final Set<String> HIDDEN = Set.of("minecraft", "java", "fabricloader", "mixinextras", "cubeutilityclient", "sodium-extra", "reeses-sodium-options",
            "modmenu", "cloth-config", "cloth-config2", "yet_another_config_lib_v3", "fabric-api", "fabric-language-kotlin", "placeholder-api");
    /** Sodium's video settings, which Reese's Sodium Options and Sodium Extra add their pages to. */
    private static final String SODIUM = "sodium";

    private static List<ModEntry> all;

    final String id;
    final String title;
    final String blurb;
    /** The mod's own name and version, for the small print. */
    final String modName;
    final String version;
    final Group group;
    private final ModContainer container;
    /** Its settings button opens a file or another window instead of a screen. */
    private boolean opensElsewhere;
    private Identifier icon;
    private boolean iconLoaded;

    private ModEntry(ModContainer container) {
        ModMetadata meta = container.getMetadata();
        this.container = container;
        this.id = meta.getId();
        this.modName = meta.getName();
        this.version = meta.getVersion().getFriendlyString();
        Label label = LABELS.get(this.id);
        this.title = label != null ? label.title() : meta.getName();
        this.blurb = label != null ? label.blurb() : firstSentence(meta.getDescription());
        this.group = label != null ? label.group() : Group.OTHER;
        this.opensElsewhere = "badoptimizations".equals(this.id);
    }

    /** The tabs: every mod with settings, minus the hidden ones, in the order above. */
    static List<ModEntry> all() {
        if (all == null) {
            List<ModEntry> tabs = new ArrayList<>();
            for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
                ModMetadata meta = container.getMetadata();
                if (container.getContainingMod().isPresent() || HIDDEN.contains(meta.getId()) || "builtin".equals(meta.getType())) {
                    continue;
                }
                ModEntry entry = new ModEntry(container);
                if (entry.hasSettings()) {
                    tabs.add(entry);
                }
            }
            tabs.sort(Comparator.comparing((ModEntry m) -> m.group)
                    .thenComparingInt(m -> ORDER.contains(m.id) ? ORDER.indexOf(m.id) : Integer.MAX_VALUE)
                    .thenComparing(m -> m.title.toLowerCase(Locale.ROOT)));
            all = List.copyOf(tabs);
        }
        return all;
    }

    private static String firstSentence(String text) {
        String clean = text.replaceAll("\\s+", " ").trim();
        int end = clean.indexOf(". ");
        return end > 0 ? clean.substring(0, end + 1) : clean;
    }

    boolean matches(String query) {
        if (query.isEmpty()) {
            return true;
        }
        String all = (this.title + " " + this.blurb + " " + this.modName + " " + this.id).toLowerCase(Locale.ROOT);
        for (String word : query.split("\\s+")) {
            if (!all.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSettings() {
        try {
            return SODIUM.equals(this.id) || FabricLoader.getInstance().isModLoaded("modmenu") && ModMenuCompat.hasSettings(this.id);
        } catch (Throwable error) {
            return false;
        }
    }

    boolean opensElsewhere() {
        return this.opensElsewhere;
    }

    /** Found out the hard way: its settings switched to another screen while opening. */
    void markOpensElsewhere() {
        this.opensElsewhere = true;
    }

    /** A new copy of the mod's own settings screen, or null. */
    Screen settings(Screen parent) {
        try {
            if (SODIUM.equals(this.id)) {
                // Sodium builds its video settings here; Reese's Sodium Options swaps in its own.
                Class<?> screen = Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen");
                return (Screen) screen.getMethod("createScreen", Screen.class).invoke(null, parent);
            }
            return ModMenuCompat.settings(this.id, parent);
        } catch (Throwable error) {
            CubeUtilityClient.LOGGER.warn("Couldn't open the settings of {}", this.id, error);
            return null;
        }
    }

    /** The mod's icon as a texture, or null when it has none. Loaded the first time it's shown. */
    Identifier icon() {
        if (!this.iconLoaded) {
            this.iconLoaded = true;
            this.icon = this.loadIcon();
        }
        return this.icon;
    }

    private Identifier loadIcon() {
        Optional<String> file = this.container.getMetadata().getIconPath(IconTexture.SIZE);
        Optional<Path> path = file.flatMap(this.container::findPath);
        if (path.isEmpty()) {
            return null;
        }
        Identifier id = CubeUtilityClient.id("mod_icons/" + this.id.replaceAll("[^a-z0-9_.-]", "_"));
        try (InputStream stream = Files.newInputStream(path.get()); NativeImage image = NativeImage.read(stream)) {
            Minecraft.getInstance().getTextureManager().register(id, new IconTexture(id, image));
            return id;
        } catch (Exception error) {
            CubeUtilityClient.LOGGER.debug("No icon for {}", this.id, error);
            return null;
        }
    }
}
