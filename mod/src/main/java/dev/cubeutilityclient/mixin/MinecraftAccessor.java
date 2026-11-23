package dev.cubeutilityclient.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.services.ProfileResult;
import com.mojang.realmsclient.gui.RealmsDataFetcher;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Everything in Minecraft that belongs to the signed-in account, so Accounts can switch it while
 * the game runs ({@link dev.cubeutilityclient.account.SessionSwitch}). Minecraft makes these once,
 * at start, from the account the launcher started it with.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Mutable
    @Accessor("user")
    void cubeutilityclient$setUser(User user);

    @Mutable
    @Accessor("userApiService")
    void cubeutilityclient$setUserApiService(UserApiService service);

    @Mutable
    @Accessor("userPropertiesFuture")
    void cubeutilityclient$setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> properties);

    @Mutable
    @Accessor("profileFuture")
    void cubeutilityclient$setProfileFuture(CompletableFuture<ProfileResult> profile);

    @Accessor("remoteFriendListUpdateHandler")
    RemoteFriendListUpdateHandler cubeutilityclient$remoteFriendListUpdateHandler();

    @Mutable
    @Accessor("remoteFriendListUpdateHandler")
    void cubeutilityclient$setRemoteFriendListUpdateHandler(RemoteFriendListUpdateHandler handler);

    @Mutable
    @Accessor("playerSocialManager")
    void cubeutilityclient$setPlayerSocialManager(PlayerSocialManager manager);

    @Mutable
    @Accessor("telemetryManager")
    void cubeutilityclient$setTelemetryManager(ClientTelemetryManager manager);

    @Mutable
    @Accessor("profileKeyPairManager")
    void cubeutilityclient$setProfileKeyPairManager(ProfileKeyPairManager manager);

    @Accessor("reportingContext")
    void cubeutilityclient$setReportingContext(ReportingContext context);

    @Mutable
    @Accessor("realmsDataFetcher")
    void cubeutilityclient$setRealmsDataFetcher(RealmsDataFetcher fetcher);
}
