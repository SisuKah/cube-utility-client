package dev.cubeutilityclient.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.cubeutilityclient.settings.SettingsScreen;

/** Cube Utility Client in Mod Menu: its settings button opens the settings page for every mod. */
public final class ModMenuEntry implements ModMenuApi {
    @Override
    public ConfigScreenFactory<SettingsScreen> getModConfigScreenFactory() {
        return SettingsScreen::new;
    }
}
