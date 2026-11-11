package dev.cubeutilityclient.settings.model;

import dev.cubeutilityclient.CubeUtilityClient;
import dev.cubeutilityclient.compat.ModMenuCompat;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

/**
 * Finds a mod's settings in a form the settings page can draw itself. Mods whose settings are
 * built with a settings library (YACL, Cloth Config, Sodium's) or kept in a plain text file are
 * read this way; for the rest this gives null and the page shows the mod's own screen.
 */
public final class Models {
    private Models() {
    }

    private static final Set<String> SKIPPED = new HashSet<>();

    /**
     * Settings read without opening the mod's screen: Sodium's video settings, the pages other
     * mods add to them (MoreCulling's), Noxesium's and EntityCulling's options, and
     * BadOptimizations' text file (its screen opens the file in Notepad).
     *
     * @param otherTabs the other tabs' mod ids, whose pages are left out of Sodium's
     * @param screen the screen to come back to from settings that open their own screen
     */
    public static SettingsModel forMod(String id, Set<String> otherTabs, Supplier<Screen> screen) {
        try {
            if (SodiumSettings.available()) {
                if ("sodium".equals(id)) {
                    return SodiumSettings.load(configId -> !otherTabs.contains(configId), screen, false);
                }
                if (SodiumSettings.has(id)) {
                    // MoreCulling's own screen also has its mod compatibility switches.
                    boolean ownScreen = FabricLoader.getInstance().isModLoaded("modmenu") && ModMenuCompat.hasSettings(id);
                    return SodiumSettings.load(id::equals, screen, ownScreen);
                }
            }
            switch (id) {
                case "noxesium" -> {
                    return NoxesiumSettings.load();
                }
                case "entityculling" -> {
                    return EntityCullingSettings.load();
                }
                default -> {
                }
            }
            if ("badoptimizations".equals(id)) {
                // Its comments say all of these need a restart. The sky colour interval only
                // matters to sky colour caching, which does nothing in this version.
                return TextFileSettings.load(FabricLoader.getInstance().getConfigDir().resolve("badoptimizations.txt"), true,
                        Set.of("skycolor_time_change_needed_for_update"));
            }
            return null;
        } catch (Throwable error) {
            CubeUtilityClient.LOGGER.warn("Couldn't read the settings of {}", id, error);
            return null;
        }
    }

    /** Notes once which kinds of settings only show on a mod's own screen. */
    static void skipped(String mod, String setting, Object kind) {
        String type = kind == null ? "?" : kind.getClass().getName();
        if (SKIPPED.add(mod + "/" + type)) {
            CubeUtilityClient.LOGGER.info("Settings page: {}'s \"{}\" ({}) only shows on the mod's own screen", mod, setting, type);
        }
    }

    /** Settings read from a mod's own settings screen, before it is shown; null when it isn't one we can read. */
    public static SettingsModel fromScreen(Screen screen) {
        try {
            SettingsModel model = YaclSettings.from(screen);
            return model != null ? model : ClothSettings.from(screen);
        } catch (Throwable error) {
            CubeUtilityClient.LOGGER.warn("Couldn't read the settings in {}", screen.getClass().getName(), error);
            return null;
        }
    }
}
