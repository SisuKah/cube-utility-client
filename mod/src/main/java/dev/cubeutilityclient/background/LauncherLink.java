package dev.cubeutilityclient.background;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The launcher's line to a game it started ahead of PLAY (see {@link BackgroundStart}). The
 * launcher writes one command per line to the game's standard input:
 * <ul>
 *   <li>{@code show}: show the window and bring it to the front;</li>
 *   <li>{@code join <address>}: the same, then join that server once the title screen is up.</li>
 * </ul>
 * When the launcher's end closes before PLAY (it quit or crashed), nobody will ever see this
 * game, so it quits.
 *
 * <p>Starts before the game itself (a pre-launch entrypoint), so it only uses plain Java here:
 * Minecraft's classes aren't ready to load yet.
 */
public final class LauncherLink implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger("Cube Utility Client");
    /** The launcher started this game in the background. */
    public static final boolean ENABLED = Boolean.getBoolean("cubeutilityclient.background");

    static volatile boolean showRequested;
    static volatile String joinAddress;
    /** Shows the window on the game's render thread; set once the window exists. */
    static volatile Runnable shower;
    /** Stops the game cleanly; set once the game is up. */
    static volatile Runnable quitter;

    @Override
    public void onPreLaunch() {
        if (!ENABLED) {
            return;
        }
        LOGGER.info("Starting in the background for the launcher: hidden until PLAY");
        Thread reader = new Thread(LauncherLink::read, "Launcher link");
        reader.setDaemon(true);
        reader.start();
    }

    private static void read() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.strip();
                if (line.equals("show")) {
                    request(null);
                } else if (line.startsWith("join ") && line.length() > 5) {
                    request(line.substring(5).strip());
                }
            }
        } catch (IOException error) {
            LOGGER.warn("Lost the line to the launcher", error);
        }
        if (!showRequested) {
            LOGGER.info("The launcher closed before PLAY, so the hidden game quits");
            Runnable quit = quitter;
            if (quit != null) {
                quit.run();
            } else {
                System.exit(0);
            }
        }
    }

    private static void request(String address) {
        if (address != null) {
            joinAddress = address;
        }
        // Set before reading shower: the window, made on another thread, sets shower before it
        // reads this, so one side always sees the other.
        showRequested = true;
        Runnable show = shower;
        if (show != null) {
            show.run();
        }
    }
}
