package dev.cubeutilityclient.mixin;

import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import dev.cubeutilityclient.account.SessionSwitch;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Keeps the Minecraft services Minecraft signs in with at start, so a switched account uses the same ones. */
@Mixin(Minecraft.class)
public abstract class MinecraftServicesMixin {
    @ModifyArg(method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/Services;create(Lcom/mojang/authlib/services/MinecraftServicesDiscoveryService;Ljava/io/File;)Lnet/minecraft/server/Services;"),
            index = 0)
    private MinecraftServicesDiscoveryService cubeutilityclient$keepServices(MinecraftServicesDiscoveryService services) {
        SessionSwitch.services = services;
        return services;
    }
}
