package dev.cubeutilityclient.mixin;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.PackSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No red Sodium warning for a server's resource pack. Sodium warns for about fifteen seconds
 * when a pack replaces terrain shaders, which Sodium doesn't use anyway, and CubeCraft's pack
 * does. That pack now loads with the game (the launcher switches it on, and Server Resource Pack
 * Checker keeps it on), so every start would show the warning, while a server's pack is nothing
 * a player can change. Sodium's check now leaves server packs out. Without Sodium, or with a
 * Sodium whose check has changed, this does nothing.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.checks.ResourcePackScanner")
public abstract class SodiumResourcePackScannerMixin {
    @Inject(method = "isExternalResourcePack", at = @At("HEAD"), cancellable = true, require = 0)
    private static void cubeutilityclient$notServerPacks(PackResources pack, CallbackInfoReturnable<Boolean> cir) {
        if (pack.location().source() == PackSource.SERVER) {
            cir.setReturnValue(false);
        }
    }
}
