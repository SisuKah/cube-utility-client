package dev.cubeutilityclient.mixin;

import dev.cubeutilityclient.background.BackgroundStart;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No menu music or clicks from a game that's waiting, hidden, for PLAY (see BackgroundStart). */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"), cancellable = true)
    private void cubeutilityclient$quietUntilShown(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (BackgroundStart.silent()) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }

    @Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
    private void cubeutilityclient$quietUntilShownLater(SoundInstance instance, int delay, CallbackInfo ci) {
        if (BackgroundStart.silent()) {
            ci.cancel();
        }
    }
}
