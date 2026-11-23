package dev.cubeutilityclient.mixin;

import dev.cubeutilityclient.shelf.Shelf;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft rebuilds its key table after any change to key bindings. Controls' "Reset Keys" binds
 * every key again, sleeping mods' too; the shelf unbinds those again here.
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {
    @Inject(method = "resetMapping", at = @At("TAIL"))
    private static void cubeutilityclient$keepSleepingKeysUnbound(CallbackInfo ci) {
        Shelf.keysChanged();
    }
}
