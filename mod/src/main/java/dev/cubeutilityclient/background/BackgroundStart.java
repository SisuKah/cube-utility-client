package dev.cubeutilityclient.background;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.language.I18n;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;

/**
 * Ready in the background: the launcher starts Minecraft before the player presses PLAY, so PLAY
 * only has to show it. Until then the window is hidden, nothing makes a sound, and once loading
 * is done nothing is drawn at all. Only in a game the launcher started that way
 * ({@code -Dcubeutilityclient.background=true}); every other game is untouched.
 */
public final class BackgroundStart implements ClientModInitializer {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** SDL_WINDOW_HIDDEN. */
    private static final long SDL_WINDOW_HIDDEN = 0x8L;
    /** How often the game loop runs while the loaded game waits, hidden (it draws nothing). */
    public static final int WAITING_LOOPS_PER_SECOND = 4;

    private static volatile boolean shown = !LauncherLink.ENABLED;
    private static volatile long window;

    @Override
    public void onInitializeClient() {
        if (!LauncherLink.ENABLED) {
            return;
        }
        LauncherLink.quitter = () -> Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
        ClientTickEvents.END_CLIENT_TICK.register(BackgroundStart::joinWhenReady);
    }

    /** The game window's creation flags: hidden, unless PLAY came first. */
    public static long windowFlags(long flags) {
        if (!LauncherLink.ENABLED || shown) {
            return flags;
        }
        if (LauncherLink.showRequested) {
            shown = true;
            return flags;
        }
        return flags | SDL_WINDOW_HIDDEN;
    }

    /** Right after the game window is made (render thread). */
    public static void windowMade(long handle) {
        if (shown || handle == 0L) {
            return;
        }
        window = handle;
        Runnable show = () -> Minecraft.getInstance().execute(BackgroundStart::show);
        // Set before reading showRequested: see LauncherLink.request.
        LauncherLink.shower = show;
        if (LauncherLink.showRequested) {
            show.run();
        }
    }

    /** No sounds or music while the player can't see the game. */
    public static boolean silent() {
        return !shown;
    }

    /**
     * Loaded and still hidden: nothing needs drawing. While loading, frames go on, since the
     * loading screen finishes the load from its own drawing.
     */
    public static boolean waitingHidden() {
        if (shown) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.gui != null && minecraft.gui.overlay() == null;
    }

    /** PLAY: the window comes up in front, at full speed and with sound (render thread). */
    private static void show() {
        if (shown) {
            return;
        }
        shown = true;
        SDLVideo.SDL_ShowWindow(window);
        // PLAY was pressed, so the game belongs in front. Windows only lets the program the player
        // is using take the foreground; the launcher passes that on, but SDL's forceful raise
        // also covers a launcher that has already lost it (Play from the tray, say).
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_FORCE_RAISEWINDOW, "1");
        SDLVideo.SDL_RaiseWindow(window);
        SDLHints.SDL_ResetHint(SDLHints.SDL_HINT_FORCE_RAISEWINDOW);
        LOGGER.info("Shown for PLAY");
    }

    /** "join" from PLAY: once the title screen is up, the same as Quick Play does it. */
    private static void joinWhenReady(Minecraft minecraft) {
        String address = LauncherLink.joinAddress;
        if (address == null || !shown || minecraft.gui.overlay() != null || !(minecraft.gui.screen() instanceof TitleScreen)) {
            return;
        }
        LauncherLink.joinAddress = null;
        LOGGER.info("Joining {} for PLAY", address);
        ServerList servers = new ServerList(minecraft);
        servers.load();
        ServerData server = servers.get(address);
        if (server == null) {
            server = new ServerData(I18n.get("selectServer.defaultName"), address, ServerData.Type.OTHER);
            servers.add(server, true);
            servers.save();
        }
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), minecraft, ServerAddress.parseString(address), server, true, null);
    }
}
