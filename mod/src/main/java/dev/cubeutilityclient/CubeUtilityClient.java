package dev.cubeutilityclient;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.cubeutilityclient.compat.ModMenuCompat;
import dev.cubeutilityclient.render.Pipelines;
import dev.cubeutilityclient.settings.SettingsScreen;
import dev.cubeutilityclient.shelf.Shelf;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * The Cube Utility Client look inside Minecraft: a space title screen, the client's colours in
 * every menu, a dark game window with the client's name and icon, and one settings page for
 * every mod, opened with Right Shift.
 */
public final class CubeUtilityClient implements ClientModInitializer {
    public static final String ID = "cubeutilityclient";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(id("main"));
    /** Opens (and closes) the settings page. Right Shift unless the player picks another key. */
    public static final KeyMapping SETTINGS_KEY = new KeyMapping("key.cubeutilityclient.settings", InputConstants.KEY_RSHIFT, CATEGORY);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ID, path);
    }

    @Override
    public void onInitializeClient() {
        Pipelines.init();
        KeyMappingHelper.registerKeyMapping(SETTINGS_KEY);

        // Get more: the mods players switch on in the game sleep until they're got.
        ClientLifecycleEvents.CLIENT_STARTED.register(minecraft -> Shelf.start());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> Shelf.joined());

        // In a world with no menu open, the key works like any other.
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            while (SETTINGS_KEY.consumeClick()) {
                if (minecraft.gui.screen() == null) {
                    minecraft.gui.setScreen(new SettingsScreen(null));
                }
            }
        });

        boolean modMenu = FabricLoader.getInstance().isModLoaded("modmenu");
        ScreenEvents.BEFORE_INIT.register((minecraft, screen, width, height) -> {
            if (modMenu && screen.getClass().getName().startsWith("com.terraformersmc.modmenu.")) {
                ModMenuCompat.showOnlyThisClient();
            }
        });
        // In menus too (the title screen, the pause menu, options), unless the player is typing.
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            if (screen instanceof SettingsScreen || screen instanceof ChatScreen) {
                return;
            }
            ScreenKeyboardEvents.afterKeyPress(screen).register((current, event) -> {
                if (SETTINGS_KEY.matches(event) && !typing(current) && Minecraft.getInstance().gui.screen() == current) {
                    Minecraft.getInstance().gui.setScreen(new SettingsScreen(current));
                }
            });
        });
    }

    /** A text box has the keyboard, where Shift is for capital letters. */
    private static boolean typing(Screen screen) {
        return screen.getFocused() instanceof EditBox || screen.getFocused() instanceof MultiLineEditBox;
    }
}
