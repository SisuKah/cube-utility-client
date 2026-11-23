package dev.cubeutilityclient.settings;

import com.mojang.blaze3d.platform.NativeImage;
import dev.cubeutilityclient.CubeUtilityClient;
import dev.cubeutilityclient.compat.ModMenuCompat;
import dev.cubeutilityclient.shelf.Shelf;
import dev.cubeutilityclient.shelf.ShelfMod;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/**
 * One installed mod on the Mods page, under its own name, with a short line saying what it does.
 * Every mod is listed, settings or not, so the page shows everything that's installed. What the
 * page says about the pack's mods is in {@link ModInfo}; a mod that isn't there (a new one) is
 * described from its own fabric.mod.json and listed under Extras, or Libraries for a library.
 */
final class ModEntry {
    enum Group {
        /** Mods the player got from Get more, at the top, just under it. */
        ADDED("ADDED MODS"),
        CUBECRAFT("CUBECRAFT"),
        VIDEO("VIDEO AND PERFORMANCE"),
        EXTRAS("EXTRAS"),
        TOOLS("TOOLS"),
        /** What other mods need to run. Nothing to set up. */
        LIBRARIES("LIBRARIES");

        final String label;

        Group(String label) {
            this.label = label;
        }
    }

    /** Never listed: the game itself, Java and the loader. */
    private static final Set<String> HIDDEN = Set.of("minecraft", "java", "fabricloader", "mixinextras");
    /** Libraries the pack has or often brings, besides mods that call themselves one (Mod Menu's "library" badge). */
    private static final Set<String> LIBRARIES = Set.of("fabric-api", "cloth-config", "cloth-config2", "yet_another_config_lib_v3", "modmenu", "placeholder-api",
            "fabric-language-kotlin", "architectury", "owo", "geckolib", "midnightlib", "fzzy_config", "forgeconfigapiport", "collective", "balm", "puzzleslib");
    /** Sodium's video settings, which Reese's Sodium Options and Sodium Extra add their pages to. */
    private static final String SODIUM = "sodium";

    private static List<ModEntry> all;
    /** Mods without a row whose icon was asked for (Get more's cards), by id. */
    private static final Map<String, ModEntry> OTHERS = new HashMap<>();

    final String id;
    /** The name shown in the list and at the top of its page. */
    final String title;
    /** A few words for the list. */
    final String line;
    /** One sentence for the top of its page. */
    final String summary;
    /** What it does, a point each, above its settings (may be empty). */
    final List<String> about;
    /** The version and who made it, in small print. */
    final String credit;
    final Group group;
    /** The mod whose page holds this one's settings, or null. */
    final String settingsIn;
    private final String searchText;
    private final int order;
    private final ModContainer container;
    /** Its settings button opens a file or another window instead of a screen. */
    private boolean opensElsewhere;
    private Identifier icon;
    private boolean iconLoaded;

    private ModEntry(ModContainer container) {
        ModMetadata meta = container.getMetadata();
        this.container = container;
        this.id = meta.getId();
        ModInfo info = ModInfo.of(this.id);
        // Mods from Get more are named as Get more names them (assets/cubeutilityclient/shelf.json).
        ShelfMod shelf = Shelf.find(this.id);
        String description = clean(meta.getDescription());
        String first = firstSentence(description);
        if (shelf != null) {
            this.title = shelf.title;
            this.line = stripEnd(shelf.about);
            this.summary = shelf.about;
            this.about = description.isEmpty() || description.equals(shelf.about) ? List.of() : List.of(description);
            this.group = Group.ADDED;
        } else if (info != null) {
            this.title = info.name != null ? info.name : meta.getName();
            this.line = info.line;
            this.summary = info.summary;
            this.about = info.about;
            this.group = info.group;
        } else {
            // Not one the page knows: its own words.
            this.title = meta.getName();
            this.line = first.isEmpty() ? "No description" : stripEnd(first);
            this.summary = first;
            this.about = description.length() > first.length() + 1 ? List.of(description) : List.of();
            this.group = isLibrary(meta) ? Group.LIBRARIES : Group.EXTRAS;
        }
        this.settingsIn = info == null ? null : info.settingsIn;
        this.order = info == null ? Integer.MAX_VALUE : info.order;
        String version = meta.getVersion().getFriendlyString();
        List<String> authors = meta.getAuthors().stream().map(Person::getName).filter(name -> !name.isBlank()).toList();
        this.credit = (this.title.equals(meta.getName()) ? "Version " : meta.getName() + " ") + version + (authors.isEmpty() ? "" : " · by " + String.join(", ", authors));
        this.searchText = String.join(" ", this.title, meta.getName(), this.id, this.line, this.summary, String.join(" ", this.about), info == null ? "" : info.keywords,
                this.group.label).toLowerCase(Locale.ROOT);
        this.opensElsewhere = "badoptimizations".equals(this.id);
    }

    /** Every installed mod in its group, in the order {@link ModInfo} lists them, then by name. */
    static List<ModEntry> all() {
        if (all == null) {
            List<ModEntry> mods = new ArrayList<>();
            for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
                ModMetadata meta = container.getMetadata();
                // Mods packed inside another one (Fabric API's parts, a mod's own libraries) belong to it;
                // Fabric API's parts also say so themselves, for when they're loose (a test game).
                if (container.getContainingMod().isPresent() || HIDDEN.contains(meta.getId()) || "builtin".equals(meta.getType())
                        || meta.containsCustomValue("fabric-api:module-lifecycle")) {
                    continue;
                }
                mods.add(new ModEntry(container));
            }
            mods.sort(Comparator.comparing((ModEntry m) -> m.group).thenComparingInt(m -> m.order).thenComparing(m -> m.title.toLowerCase(Locale.ROOT)));
            all = List.copyOf(mods);
        }
        return all;
    }

    private static boolean isLibrary(ModMetadata meta) {
        if (LIBRARIES.contains(meta.getId())) {
            return true;
        }
        try {
            CustomValue modMenu = meta.getCustomValue("modmenu");
            if (modMenu != null && modMenu.getType() == CustomValue.CvType.OBJECT) {
                CustomValue badges = modMenu.getAsObject().get("badges");
                if (badges != null && badges.getType() == CustomValue.CvType.ARRAY) {
                    for (CustomValue badge : badges.getAsArray()) {
                        if (badge.getType() == CustomValue.CvType.STRING && "library".equals(badge.getAsString())) {
                            return true;
                        }
                    }
                }
            }
        } catch (RuntimeException unreadable) {
            // Not a library, then.
        }
        return false;
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String firstSentence(String text) {
        int end = text.indexOf(". ");
        return end > 0 ? text.substring(0, end + 1) : text;
    }

    /** A sentence as a list line: no full stop at the end. */
    private static String stripEnd(String text) {
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** Whether every word searched for is somewhere in its name, what it does, or the words it's known by. */
    boolean matches(String query) {
        if (query.isEmpty()) {
            return true;
        }
        for (String word : query.split("\\s+")) {
            if (!this.searchText.contains(word)) {
                return false;
            }
        }
        return true;
    }

    boolean isLibrary() {
        return this.group == Group.LIBRARIES;
    }

    /** It has a settings screen of its own (or, for Sodium, video settings the page reads). */
    boolean hasSettingsScreen() {
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

    /**
     * The ids of the other listed mods that show their settings on a page of their own, which a
     * reader leaves out of this one's (MoreCulling's pages aren't repeated on Sodium's). Mods whose
     * settings live on this page (Sodium Extra's) stay in.
     */
    Set<String> others(List<ModEntry> shown) {
        return shown.stream().filter(m -> !m.id.equals(this.id) && m.settingsIn == null).map(m -> m.id).collect(Collectors.toSet());
    }

    /** A mod's icon by its id, also for mods without a row, or null. */
    static Identifier iconOf(String id) {
        for (ModEntry mod : all()) {
            if (mod.id.equals(id)) {
                return mod.icon();
            }
        }
        ModEntry other = OTHERS.computeIfAbsent(id, key -> FabricLoader.getInstance().getModContainer(key).map(ModEntry::new).orElse(null));
        return other == null ? null : other.icon();
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
