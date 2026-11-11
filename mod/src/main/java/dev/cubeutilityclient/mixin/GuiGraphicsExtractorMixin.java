package dev.cubeutilityclient.mixin;

import dev.cubeutilityclient.settings.EmbeddedScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft allows one background blur per frame, and the settings page has already used it.
 * Settings screens shown inside the page (Cloth Config ones blur in their own drawing) skip theirs.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {
    @Inject(method = "blurBeforeThisStratum", at = @At("HEAD"), cancellable = true)
    private void cubeutilityclient$oneBlur(CallbackInfo ci) {
        if (EmbeddedScreen.isDrawing()) {
            ci.cancel();
        }
    }
}
