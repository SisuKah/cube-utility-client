package dev.cubeutilityclient.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** "Cube Utility Client" in the window title instead of "Minecraft* 26.3"; the part after it stays. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
    private void cubeutilityclient$title(CallbackInfoReturnable<String> cir) {
        String vanilla = cir.getReturnValue();
        int rest = vanilla.indexOf(" - ");
        cir.setReturnValue("Cube Utility Client" + (rest >= 0 ? vanilla.substring(rest) : ""));
    }
}
