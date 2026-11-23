package dev.cubeutilityclient.shelf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.KeyMapping;

/**
 * A mod on the shelf: it ships with the client and loads with the game, asleep until the player
 * gets it. Asleep means its own on/off settings are off and its keys are unbound, so it draws
 * nothing and reacts to nothing. Built-in ones (the TNT timer) are part of this client.
 */
public final class ShelfMod {
    /** The mod's Fabric id, or a built-in feature's name. */
    public final String id;
    /** Its name in the client, for what it does: "Keystrokes", not "KeyCps". */
    public final String title;
    /** What it does, in one line. */
    public final String about;
    public final boolean builtIn;
    /** Its settings that are off while it sleeps, each with its asleep value. */
    final List<Setting> switches = new ArrayList<>();
    /** Settings held at one value whether it sleeps or not, like a welcome message already shown. */
    final List<Setting> always = new ArrayList<>();
    /** The start of its key bindings' names, like "key.keycps.". */
    final List<String> keys = new ArrayList<>();
    /** Its settings couldn't be reached, so it can't sleep: it stays an ordinary mod. */
    boolean broken;

    /** A setting and a value for it. */
    record Setting(Switch target, boolean value) {
    }

    private ShelfMod(String id, String title, String about, boolean builtIn) {
        this.id = id;
        this.title = title;
        this.about = about;
        this.builtIn = builtIn;
    }

    /**
     * One entry of assets/cubeutilityclient/shelf.json. A switch that starts with "!" is a
     * "disable" setting: true while the mod sleeps.
     */
    static ShelfMod parse(JsonObject json) {
        ShelfMod mod = new ShelfMod(json.get("id").getAsString(), json.get("title").getAsString(), json.get("about").getAsString(),
                json.has("builtIn") && json.get("builtIn").getAsBoolean());
        for (String spec : strings(json, "switches")) {
            boolean inverted = spec.startsWith("!");
            mod.switches.add(new Setting(new Switch(inverted ? spec.substring(1) : spec), inverted));
        }
        if (json.has("always")) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("always").entrySet()) {
                mod.always.add(new Setting(new Switch(entry.getKey()), entry.getValue().getAsBoolean()));
            }
        }
        mod.keys.addAll(strings(json, "keys"));
        if (!mod.builtIn && mod.switches.isEmpty()) {
            throw new IllegalArgumentException(mod.id + " has no switches to put it to sleep");
        }
        return mod;
    }

    private static List<String> strings(JsonObject json, String name) {
        List<String> out = new ArrayList<>();
        if (json.has(name)) {
            JsonArray array = json.getAsJsonArray(name);
            array.forEach(element -> out.add(element.getAsString()));
        }
        return out;
    }

    boolean ownsKey(KeyMapping key) {
        for (String prefix : this.keys) {
            if (key.getName().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The values its switches have now, by path. */
    Map<String, Boolean> read() throws ReflectiveOperationException {
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (Setting setting : this.switches) {
            values.put(setting.target().path, setting.target().get());
        }
        return values;
    }

    /** Every switch has its asleep value in {@code values}. */
    boolean asleepIn(Map<String, Boolean> values) {
        for (Setting setting : this.switches) {
            if (!Boolean.valueOf(setting.value()).equals(values.get(setting.target().path))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return this.id;
    }
}
