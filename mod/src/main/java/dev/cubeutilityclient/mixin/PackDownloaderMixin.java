package dev.cubeutilityclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Servers' resource pack downloads are sent with the player's name and UUID. Minecraft keeps the
 * account it started with for them; after a switch in Accounts they name the account that plays.
 */
@Mixin(targets = "net.minecraft.client.resources.server.DownloadedPackSource$4")
public abstract class PackDownloaderMixin {
    @ModifyExpressionValue(method = "createDownloadHeaders",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/resources/server/DownloadedPackSource$4;val$user:Lnet/minecraft/client/User;", opcode = Opcodes.GETFIELD))
    private User cubeutilityclient$currentUser(User startedWith) {
        return Minecraft.getInstance().getUser();
    }
}
