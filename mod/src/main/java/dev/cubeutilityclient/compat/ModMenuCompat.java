package dev.cubeutilityclient.compat;

import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;

/** Mod Menu's list of mods. Only called when Mod Menu is installed. */
public final class ModMenuCompat {
    private ModMenuCompat() {
    }

    public static Screen modsScreen(Screen parent) {
        return ModMenuApi.createModsScreen(parent);
    }
}
