package dev.cubeutilityclient.mixin;

import com.mojang.realmsclient.client.RealmsClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Realms keeps one client with the account the game started with; a switched account needs a new one. */
@Mixin(RealmsClient.class)
public interface RealmsClientAccessor {
    @Accessor("realmsClientInstance")
    static void cubeutilityclient$setInstance(RealmsClient client) {
        throw new AssertionError();
    }
}
