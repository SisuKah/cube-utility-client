package dev.cubeutilityclient.shelf;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.cubeutilityclient.CubeUtilityClient;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the player got from the shelf, in config/cubeutilityclient/shelf.json. Sleeping mods'
 * own settings and key bindings are kept here too, so getting a mod again brings back exactly
 * how the player had it.
 */
final class ShelfState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The mods the player got. */
    Set<String> on = new LinkedHashSet<>();
    /** Sleeping mods' key bindings, by key name, as options.txt writes them. */
    Map<String, String> keys = new LinkedHashMap<>();
    /** Sleeping mods' own on/off settings as the player had them, by mod and setting. */
    Map<String, Map<String, Boolean>> remembered = new LinkedHashMap<>();

    static ShelfState load(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ShelfState state = GSON.fromJson(reader, ShelfState.class);
            if (state != null) {
                state.fill();
                return state;
            }
        } catch (NoSuchFileException missing) {
            // First start: nothing got yet.
        } catch (IOException | JsonParseException error) {
            CubeUtilityClient.LOGGER.warn("Couldn't read {}; starting with nothing got", file, error);
        }
        return new ShelfState();
    }

    /** Gson leaves out what the file doesn't mention. */
    private void fill() {
        if (this.on == null) {
            this.on = new LinkedHashSet<>();
        }
        if (this.keys == null) {
            this.keys = new LinkedHashMap<>();
        }
        if (this.remembered == null) {
            this.remembered = new LinkedHashMap<>();
        }
    }

    /** Written next to the file first and then moved over it, so a crash never leaves half a file. */
    void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            CubeUtilityClient.LOGGER.warn("Couldn't save {}", file, error);
        }
    }
}
