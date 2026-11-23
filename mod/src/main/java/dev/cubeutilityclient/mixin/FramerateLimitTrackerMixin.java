package dev.cubeutilityclient.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.cubeutilityclient.background.BackgroundStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A loaded game waiting, hidden, for PLAY draws only a few frames a second (see BackgroundStart). */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void cubeutilityclient$slowWhileWaiting(CallbackInfoReturnable<Integer> cir) {
        int limit = cir.getReturnValueI();
        int waiting = BackgroundStart.waitingFramerate(limit);
        if (waiting != limit) {
            cir.setReturnValue(waiting);
        }
    }
}
