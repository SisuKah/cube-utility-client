package dev.cubeutilityclient.compat;

import com.terraformersmc.modmenu.ModMenu;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.terraformersmc.modmenu.config.ModMenuConfig;
import dev.cubeutilityclient.CubeUtilityClient;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.gui.screens.Screen;

/** Mod Menu: its list of mods, and every mod's settings screen. Only called when it's installed. */
public final class ModMenuCompat {
    private ModMenuCompat() {
    }

    public static Screen modsScreen(Screen parent) {
        return ModMenuApi.createModsScreen(parent);
    }

    public static boolean hasSettings(String modId) {
        return ModMenu.hasConfigScreen(modId);
    }

    /** A new copy of a mod's settings screen, or null. */
    public static Screen settings(String modId, Screen parent) {
        return ModMenu.getConfigScreen(modId, parent);
    }

    /**
     * Mod Menu lists only Cube Utility Client. Every other mod stays installed and working; its
     * settings are on the client's settings page. Kept in memory only, each time the list opens.
     */
    public static void showOnlyThisClient() {
        Set<String> hidden = new HashSet<>(ModMenuConfig.HIDDEN_MODS.getValue());
        boolean changed = false;
        for (String id : ModMenu.MODS.keySet()) {
            if (!id.equals(CubeUtilityClient.ID) && hidden.add(id)) {
                changed = true;
            }
        }
        if (changed) {
            ModMenuConfig.HIDDEN_MODS.setValue(hidden);
        }
    }
}
