package dev.cubeutilityclient.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.device.GpuBackend;
import dev.cubeutilityclient.background.BackgroundStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A game the launcher started ahead of PLAY makes its window hidden (see BackgroundStart). */
@Mixin(Window.class)
public abstract class WindowBackgroundMixin {
    @ModifyArg(
            method = "createWindow",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"),
            index = 3)
    private long cubeutilityclient$hidden(long flags) {
        return BackgroundStart.windowFlags(flags);
    }

    @Inject(method = "createWindow", at = @At("RETURN"))
    private void cubeutilityclient$made(GpuBackend backend, int width, int height, String title, CallbackInfoReturnable<Long> cir) {
        BackgroundStart.windowMade(cir.getReturnValueJ());
    }
}
