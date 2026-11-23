package dev.cubeutilityclient.mixin;

import dev.cubeutilityclient.screen.CubeTitleScreen;
import dev.cubeutilityclient.settings.SettingsScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wherever the game opens its title screen (at start, after leaving a world or a server), the
 * client's own opens instead: both when a title screen is passed in, and when setScreen makes one
 * itself because it was given none. setScreen keeps using its parameter after storing it, so the
 * parameter itself is replaced.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    /**
     * While the Mods page reads a mod's settings from its screen, set up out of sight, a switch to
     * another screen (Reese's Sodium Options swaps its own in for Sodium's) is read instead of
     * taking over the window; and a mod's screen the page closes to save it doesn't go anywhere.
     */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void cubeutilityclient$keepInsidePage(Screen screen, CallbackInfo ci) {
        if (SettingsScreen.adopt(screen)) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen cubeutilityclient$givenTitleScreen(Screen screen) {
        return swap(screen);
    }

    @ModifyVariable(method = "setScreen", at = @At("STORE"), argsOnly = true)
    private Screen cubeutilityclient$madeTitleScreen(Screen screen) {
        return swap(screen);
    }

    private static Screen swap(Screen screen) {
        return screen instanceof TitleScreen && !(screen instanceof CubeTitleScreen) ? new CubeTitleScreen() : screen;
    }
}
