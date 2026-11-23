package dev.cubeutilityclient.account;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.services.FriendsService;
import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import com.mojang.authlib.services.ProfileResult;
import com.mojang.realmsclient.client.RealmsClient;
import com.mojang.realmsclient.gui.RealmsDataFetcher;
import com.mojang.util.UndashedUuid;
import dev.cubeutilityclient.CubeUtilityClient;
import dev.cubeutilityclient.mixin.MinecraftAccessor;
import dev.cubeutilityclient.mixin.RealmsClientAccessor;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.util.Util;

/**
 * Switches the account Minecraft plays with, while it runs. Minecraft builds everything that
 * belongs to an account once, at start: the login itself, the account's settings (whether it may
 * play online, chat, see friends), its chat signing keys, friends and presence, reports,
 * telemetry and Realms. A switch builds all of them again for the new account, the way Minecraft
 * does at start, and lets the old ones go. Joining a server afterwards signs in with the new
 * account, and singleplayer worlds see the new name and skin.
 */
public final class SessionSwitch {
    /** The services Minecraft signs in with, kept as it starts (MinecraftServicesMixin). */
    public static MinecraftServicesDiscoveryService services;

    private SessionSwitch() {
    }

    /** The account can only change outside worlds and servers. */
    public static boolean possible(Minecraft minecraft) {
        return minecraft.level == null && minecraft.getConnection() == null;
    }

    /**
     * Plays as the login from now on. What needs the network happens off Minecraft's main thread;
     * the switch itself happens on it. Fails, changing nothing, when a world is open.
     */
    public static CompletableFuture<Void> apply(AccountLink.Login login) {
        Minecraft minecraft = Minecraft.getInstance();
        UUID uuid = UndashedUuid.fromStringLenient(login.uuid());
        Optional<String> xuid = login.xuid() == null || login.xuid().isBlank() ? Optional.empty() : Optional.of(login.xuid());
        User user = new User(login.name(), uuid, login.accessToken(), xuid, minecraft.getUser().getClientId());
        return CompletableFuture.supplyAsync(() -> prepare(minecraft, user), Util.nonCriticalIoPool()).thenAcceptAsync(ready -> {
            if (!possible(minecraft)) {
                throw new AccountLink.Failure("Leave the world first, then switch accounts.", false);
            }
            switchTo(minecraft, ready);
        }, minecraft);
    }

    /** The new account's services and settings, fetched before anything changes. */
    private record Ready(User user, UserApiService api, UserApiService.UserProperties properties, ProfileResult profile, FriendsService friends) {
    }

    private static Ready prepare(Minecraft minecraft, User user) {
        MinecraftServicesDiscoveryService discovery = services != null ? services : MinecraftServicesDiscoveryService.create(minecraft.getProxy(), true);
        UserApiService api = discovery.createUserApiService(user.getAccessToken());
        UserApiService.UserProperties properties;
        try {
            properties = api.fetchProperties();
        } catch (AuthenticationException error) {
            CubeUtilityClient.LOGGER.warn("Couldn't fetch {}'s account settings: {}", user.getName(), error.getMessage());
            properties = UserApiService.OFFLINE_PROPERTIES;
        }
        // The profile brings the skin and cape; without it the account still plays, as Steve or Alex.
        ProfileResult profile = null;
        try {
            profile = minecraft.services().sessionService().fetchProfile(user.getProfileId(), true);
        } catch (RuntimeException error) {
            CubeUtilityClient.LOGGER.warn("Couldn't fetch {}'s profile: {}", user.getName(), error.getMessage());
        }
        FriendsService friends = discovery.createFriendsService(user.getAccessToken());
        return new Ready(user, api, properties, profile, friends);
    }

    private static void switchTo(Minecraft minecraft, Ready ready) {
        MinecraftAccessor access = (MinecraftAccessor) minecraft;
        String before = minecraft.getUser().getName();

        // The account that played signs off, as when Minecraft closes.
        try {
            minecraft.getPlayerSocialManager().getPresenceHandler().sendOfflinePresence();
        } catch (RuntimeException error) {
            CubeUtilityClient.LOGGER.debug("Couldn't say {} went offline", before, error);
        }
        access.cubeutilityclient$remoteFriendListUpdateHandler().close();
        minecraft.getTelemetryManager().close();

        User user = ready.user();
        UserApiService api = ready.api();
        access.cubeutilityclient$setUser(user);
        access.cubeutilityclient$setUserApiService(api);
        access.cubeutilityclient$setUserPropertiesFuture(CompletableFuture.completedFuture(ready.properties()));
        access.cubeutilityclient$setProfileFuture(CompletableFuture.completedFuture(ready.profile()));

        RemoteFriendListUpdateHandler friendUpdates = new RemoteFriendListUpdateHandler(ready.friends(), minecraft);
        access.cubeutilityclient$setRemoteFriendListUpdateHandler(friendUpdates);
        PlayerSocialManager social = new PlayerSocialManager(minecraft, api, ready.friends(), friendUpdates);
        access.cubeutilityclient$setPlayerSocialManager(social);
        if (social.isFriendListEnabled()) {
            friendUpdates.start();
        }

        access.cubeutilityclient$setTelemetryManager(new ClientTelemetryManager(minecraft, api, user));
        access.cubeutilityclient$setProfileKeyPairManager(ProfileKeyPairManager.create(api, user, minecraft.gameDirectory.toPath()));
        access.cubeutilityclient$setReportingContext(ReportingContext.create(ReportEnvironment.local(), api));
        RealmsClientAccessor.cubeutilityclient$setInstance(null);
        access.cubeutilityclient$setRealmsDataFetcher(new RealmsDataFetcher(RealmsClient.getOrCreate(minecraft)));
        CubeUtilityClient.LOGGER.info("Switched account from {} to {}", before, user.getName());
    }
}
