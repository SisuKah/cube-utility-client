package dev.cubeutilityclient.mixin;

import dev.cubeutilityclient.render.Paint;
import dev.cubeutilityclient.space.SpaceScene;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Menus outside a world (Options, the server and world lists, ...) show the client's space scene
 * where Minecraft shows its panorama. Minecraft still blurs and dims it behind them.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
    private void cubeutilityclient$space(GuiGraphicsExtractor graphics, float a, CallbackInfo ci) {
        SpaceScene.get().draw(new Paint(graphics), 0f, 0f);
        ci.cancel();
    }
}
