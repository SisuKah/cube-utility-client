package dev.cubeutilityclient.mixin;

import dev.cubeutilityclient.shelf.Shelf;
import dev.cubeutilityclient.shelf.TntTimer;
import net.minecraft.client.renderer.entity.TntRenderer;
import net.minecraft.client.renderer.entity.state.TntRenderState;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The TNT Timer, when the player got it: one check per lit TNT, and nothing more while it's off. */
@Mixin(TntRenderer.class)
public abstract class TntRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/PrimedTnt;Lnet/minecraft/client/renderer/entity/state/TntRenderState;F)V", at = @At("TAIL"))
    private void cubeutilityclient$timer(PrimedTnt tnt, TntRenderState state, float partialTick, CallbackInfo ci) {
        if (Shelf.tntTimer) {
            TntTimer.label(tnt, state, partialTick);
        }
    }
}
