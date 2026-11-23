package dev.cubeutilityclient.shelf;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.cubeutilityclient.CubeUtilityClient;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Mods players get in the game, with no restart: "Get more" on the settings page. Minecraft
 * can't load a mod while it runs, so these ship with the client and load with the game, asleep:
 * their own on/off settings are off and their keys are unbound, so they draw nothing and react
 * to nothing. Getting one switches it on there and then; removing it puts it back to sleep.
 *
 * <p>An asleep mod costs no frame time: the mods on the shelf check their own on/off setting
 * first thing when they would draw, and stop there. The shelf itself does nothing per frame; it
 * only flips settings when the game starts, when a world is joined, and when the player gets or
 * removes a mod.
 *
 * <p>Which mods are on the shelf, their names, and which of their settings put them to sleep is
 * in assets/cubeutilityclient/shelf.json. What the player got is kept in
 * config/cubeutilityclient/shelf.json.
 */
public final class Shelf {
    /** The client's own TNT timer. */
    public static final String TNT_TIMER = "tnt_timer";
    private static final String CATALOG = "/assets/cubeutilityclient/shelf.json";

    /** The TNT timer is on. Read for every lit TNT while drawing, so it's a plain field. */
    public static boolean tntTimer;

    /** The catalog's mods that are here: built in, or installed. Read once. */
    private static List<ShelfMod> here;
    private static ShelfState state = new ShelfState();
    private static Path file;
    private static boolean started;
    /** The shelf is changing key bindings itself, so the reset that follows isn't the player's. */
    private static boolean changingKeys;

    private Shelf() {
    }

    // ------------------------------------------------------------------ the game's side

    /** When the game has started: every mod has made its settings and keys by then. */
    public static void start() {
        file = FabricLoader.getInstance().getConfigDir().resolve(CubeUtilityClient.ID).resolve("shelf.json");
        state = ShelfState.load(file);
        started = true;
        for (ShelfMod mod : here()) {
            if (state.on.contains(mod.id)) {
                // On, with its own settings as the player left them. Keys left unbound by a
                // game that closed halfway through come back.
                setBuiltIn(mod, true);
                setKeys(mod, true);
                hold(mod);
            } else {
                sleep(mod);
            }
        }
        state.save(file);
        CubeUtilityClient.LOGGER.info("Mods to get in the game: {} ({} on)", mods().stream().map(m -> m.id).toList(), mods().size() - available());
    }

    /**
     * When a world or server is joined. A mod that read its settings from disk again would wake
     * up, so asleep mods are put back to sleep. A few field writes; nothing when all are on.
     */
    public static void joined() {
        if (!started) {
            return;
        }
        boolean slept = false;
        for (ShelfMod mod : here()) {
            if (!mod.builtIn && !mod.broken && !state.on.contains(mod.id)) {
                sleep(mod);
                slept = true;
            }
        }
        if (slept) {
            state.save(file);
        }
    }

    // ------------------------------------------------------------------ what the screens use

    /** The mods on the shelf, in the shelf's order. */
    public static List<ShelfMod> mods() {
        return here().stream().filter(mod -> !mod.broken).toList();
    }

    /** How many mods on the shelf are still asleep, there to get. */
    public static int available() {
        int count = 0;
        for (ShelfMod mod : here()) {
            if (!mod.broken && !state.on.contains(mod.id)) {
                count++;
            }
        }
        return count;
    }

    public static boolean isOn(ShelfMod mod) {
        return state.on.contains(mod.id);
    }

    /** The mod with this id is on the shelf and asleep, so lists of mods leave it out. */
    public static boolean asleep(String id) {
        if (!started) {
            return false;
        }
        ShelfMod mod = find(id);
        return mod != null && !state.on.contains(id);
    }

    /** The shelf's entry for a mod, or null when it isn't on the shelf. */
    public static ShelfMod find(String id) {
        for (ShelfMod mod : here()) {
            if (mod.id.equals(id) && !mod.broken) {
                return mod;
            }
        }
        return null;
    }

    /** Wakes a mod up, right away. False when it couldn't be switched on. */
    public static boolean get(ShelfMod mod) {
        if (state.on.contains(mod.id)) {
            return true;
        }
        if (!started || mod.broken || !wake(mod)) {
            return false;
        }
        state.on.add(mod.id);
        state.save(file);
        CubeUtilityClient.LOGGER.info("Got {} from the shelf", mod.id);
        return true;
    }

    /** Puts a mod back to sleep, right away. */
    public static void remove(ShelfMod mod) {
        if (!started || !state.on.remove(mod.id)) {
            return;
        }
        sleep(mod);
        state.save(file);
        CubeUtilityClient.LOGGER.info("Put {} back on the shelf", mod.id);
    }

    // ------------------------------------------------------------------ sleeping and waking

    private static void sleep(ShelfMod mod) {
        if (mod.builtIn) {
            setBuiltIn(mod, false);
            return;
        }
        try {
            // Read first: a setting that can't be reached stops this before anything changes.
            Map<String, Boolean> now = mod.read();
            if (!mod.asleepIn(now)) {
                // How the player had it, for when it's got again.
                state.remembered.put(mod.id, now);
            }
            for (ShelfMod.Setting setting : mod.switches) {
                setting.target().set(setting.value());
            }
            hold(mod);
            setKeys(mod, false);
        } catch (ReflectiveOperationException | RuntimeException error) {
            broken(mod, error);
        }
    }

    private static boolean wake(ShelfMod mod) {
        if (mod.builtIn) {
            setBuiltIn(mod, true);
            return true;
        }
        try {
            mod.read();
            Map<String, Boolean> before = state.remembered.get(mod.id);
            if (before != null && mod.asleepIn(before)) {
                before = null;
            }
            for (ShelfMod.Setting setting : mod.switches) {
                Boolean value = before != null ? before.get(setting.target().path) : null;
                setting.target().set(value != null ? value : !setting.value());
            }
            hold(mod);
            setKeys(mod, true);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            broken(mod, error);
            return false;
        }
    }

    /** Settings held at one value whatever the mod's state, like a welcome message marked as seen. */
    private static void hold(ShelfMod mod) {
        try {
            for (ShelfMod.Setting setting : mod.always) {
                setting.target().set(setting.value());
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            CubeUtilityClient.LOGGER.debug("Couldn't hold a setting of {}", mod.id, error);
        }
    }

    private static void setBuiltIn(ShelfMod mod, boolean on) {
        if (TNT_TIMER.equals(mod.id)) {
            tntTimer = on;
        }
    }

    /**
     * Its settings can't be reached (a new version renamed them): it stays an ordinary mod, on,
     * and leaves the shelf, so nothing claims it's asleep while it isn't.
     */
    private static void broken(ShelfMod mod, Throwable error) {
        if (!mod.broken) {
            mod.broken = true;
            CubeUtilityClient.LOGGER.warn("{} can't be put to sleep, so it stays on and leaves the shelf: {}", mod.id, error.toString());
        }
    }

    // ------------------------------------------------------------------ keys

    /**
     * Unbinds a sleeping mod's keys, keeping how the player had them, or binds them again. Unbound
     * rather than ignored: some mods read keys straight from the keyboard by the bound key.
     */
    private static void setKeys(ShelfMod mod, boolean on) {
        if (mod.keys.isEmpty()) {
            return;
        }
        Options options = Minecraft.getInstance().options;
        boolean changed = false;
        for (KeyMapping key : options.keyMappings) {
            if (!mod.ownsKey(key)) {
                continue;
            }
            if (on) {
                String saved = state.keys.remove(key.getName());
                if (saved != null) {
                    key.setKey(InputConstants.getKey(saved));
                    changed = true;
                }
            } else if (!key.isUnbound()) {
                state.keys.putIfAbsent(key.getName(), key.saveString());
                key.setKey(InputConstants.UNKNOWN);
                changed = true;
            }
        }
        if (changed) {
            changingKeys = true;
            try {
                KeyMapping.resetMapping();
            } finally {
                changingKeys = false;
            }
            options.save();
        }
    }

    /** Controls leaves out sleeping mods' keys: they're unbound, and changing them would wake nothing. */
    public static KeyMapping[] visibleKeys(KeyMapping[] keys) {
        if (!started) {
            return keys;
        }
        List<KeyMapping> shown = new ArrayList<>(keys.length);
        for (KeyMapping key : keys) {
            if (!sleepingKey(key)) {
                shown.add(key);
            }
        }
        return shown.size() == keys.length ? keys : shown.toArray(KeyMapping[]::new);
    }

    /**
     * After any change to key bindings, like Controls' "Reset Keys", which binds every key again,
     * sleeping mods' included: those are unbound again.
     */
    public static void keysChanged() {
        if (!started || changingKeys) {
            return;
        }
        boolean changed = false;
        for (KeyMapping key : Minecraft.getInstance().options.keyMappings) {
            if (sleepingKey(key) && !key.isUnbound()) {
                state.keys.putIfAbsent(key.getName(), key.saveString());
                key.setKey(InputConstants.UNKNOWN);
                changed = true;
            }
        }
        if (changed) {
            changingKeys = true;
            try {
                KeyMapping.resetMapping();
            } finally {
                changingKeys = false;
            }
            state.save(file);
        }
    }

    private static boolean sleepingKey(KeyMapping key) {
        for (ShelfMod mod : here()) {
            if (!mod.broken && !state.on.contains(mod.id) && mod.ownsKey(key)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the list

    private static List<ShelfMod> here() {
        if (here == null) {
            List<ShelfMod> mods = new ArrayList<>();
            for (ShelfMod mod : catalog()) {
                if (mod.builtIn || FabricLoader.getInstance().isModLoaded(mod.id)) {
                    mods.add(mod);
                }
            }
            here = List.copyOf(mods);
        }
        return here;
    }

    private static List<ShelfMod> catalog() {
        List<ShelfMod> mods = new ArrayList<>();
        try (InputStream stream = Shelf.class.getResourceAsStream(CATALOG)) {
            if (stream == null) {
                CubeUtilityClient.LOGGER.warn("{} is missing; the shelf is empty", CATALOG);
                return mods;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement element : root.getAsJsonArray("mods")) {
                try {
                    mods.add(ShelfMod.parse(element.getAsJsonObject()));
                } catch (RuntimeException error) {
                    CubeUtilityClient.LOGGER.warn("Skipping a shelf entry: {}", element, error);
                }
            }
        } catch (Exception error) {
            CubeUtilityClient.LOGGER.warn("Couldn't read {}; the shelf is empty", CATALOG, error);
        }
        return mods;
    }
}
