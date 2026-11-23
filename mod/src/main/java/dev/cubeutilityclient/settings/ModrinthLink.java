package dev.cubeutilityclient.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.cubeutilityclient.CubeUtilityClient;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Browse Modrinth's way to the launcher. While the launcher runs the game it answers search and
 * install on 127.0.0.1, at the port and with the token it leaves in
 * config/cubeutilityclient/launcher.json (game_api.rs in the launcher). The launcher does the
 * actual installing, with the same checks and the same list as its Mods screen. Every answer
 * arrives on Minecraft's main thread.
 */
final class ModrinthLink {
    /** One mod in the search results. Status is "available", "installed" or "inPack". */
    record Hit(String projectId, String title, String author, String description, String iconUrl, long downloads, String status) {
    }

    record Page(List<Hit> hits, int total, int nextOffset, String minecraft) {
    }

    /** A mod installed from Modrinth; libraries came along with a picked mod. */
    record Installed(String projectId, String title, String version, String iconUrl, boolean picked, boolean removing) {
    }

    /** An answer the player should read, like "Can't reach Modrinth." */
    static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message, null, false, false);
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(4)).build();
    /** Icons only come from Modrinth's own image server. */
    private static final String ICON_ORIGIN = "https://cdn.modrinth.com/";
    /** Icons already fetched, by URL; null means there's none we can show. */
    private static final Map<String, Identifier> ICONS = new HashMap<>();
    private static final List<String> LOADING = new ArrayList<>();

    private final String base, token;

    private ModrinthLink(int port, String token) {
        this.base = "http://127.0.0.1:" + port;
        this.token = token;
    }

    /** The launcher's doorway, or null when Minecraft wasn't started by the launcher. */
    static ModrinthLink find() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("cubeutilityclient").resolve("launcher.json");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            int port = json.get("port").getAsInt();
            String token = json.get("token").getAsString();
            return port > 0 && port < 65536 && !token.isBlank() ? new ModrinthLink(port, token) : null;
        } catch (Exception error) {
            return null;
        }
    }

    CompletableFuture<Page> search(String query, int offset) {
        String path = "/modrinth/search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&offset=" + offset;
        return this.call("GET", path, json -> {
            List<Hit> hits = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("hits")) {
                JsonObject hit = element.getAsJsonObject();
                hits.add(new Hit(text(hit, "projectId"), text(hit, "title"), text(hit, "author"), text(hit, "description"), text(hit, "iconUrl"),
                        hit.has("downloads") ? hit.get("downloads").getAsLong() : 0L, text(hit, "status")));
            }
            return new Page(hits, json.get("total").getAsInt(), json.get("nextOffset").getAsInt(), text(json, "minecraft"));
        });
    }

    CompletableFuture<List<Installed>> installed() {
        return this.call("GET", "/modrinth/installed", ModrinthLink::installedList);
    }

    /** Installs a mod and the libraries it needs. Returns how many libraries came along. */
    CompletableFuture<Integer> install(String projectId) {
        return this.call("POST", "/modrinth/install?project=" + projectId, json -> Math.max(0, json.getAsJsonArray("installed").size() - 1));
    }

    /** Marks a mod to go when the game closes. */
    CompletableFuture<List<Installed>> remove(String projectId) {
        return this.call("POST", "/modrinth/remove?project=" + projectId, ModrinthLink::installedList);
    }

    /** Takes back a remove. */
    CompletableFuture<List<Installed>> keep(String projectId) {
        return this.call("POST", "/modrinth/keep?project=" + projectId, ModrinthLink::installedList);
    }

    private static List<Installed> installedList(JsonObject json) {
        List<Installed> out = new ArrayList<>();
        JsonArray mods = json.getAsJsonArray("mods");
        for (JsonElement element : mods) {
            JsonObject mod = element.getAsJsonObject();
            out.add(new Installed(text(mod, "projectId"), text(mod, "title"), text(mod, "version"), text(mod, "iconUrl"), mod.get("picked").getAsBoolean(),
                    mod.has("removing") && mod.get("removing").getAsBoolean()));
        }
        return out;
    }

    private static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private <T> CompletableFuture<T> call(String method, String path, Function<JsonObject, T> read) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(this.base + path))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + this.token)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        // Handled on Minecraft's main thread, failures too, so the panel can just use the answer.
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).handleAsync((response, error) -> {
            if (error != null) {
                throw new Failure("The launcher isn't answering. Add mods in its Mods screen instead.");
            }
            JsonObject json;
            try {
                json = JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (Exception bad) {
                throw new Failure("The launcher sent something unexpected. Add mods in its Mods screen instead.");
            }
            if (response.statusCode() != 200) {
                throw new Failure(json.has("error") ? json.get("error").getAsString() : "That didn't work. Try again in a moment.");
            }
            return read.apply(json);
        }, Minecraft.getInstance());
    }

    /** The player-readable reason a call failed. */
    static String reason(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof Failure failure) {
            return failure.getMessage();
        }
        CubeUtilityClient.LOGGER.warn("Browse Modrinth failed", cause);
        return "That didn't work. Try again in a moment.";
    }

    // ------------------------------------------------------------------ icons

    /**
     * A mod's icon from Modrinth, once it has arrived; null until then, and for icons that
     * aren't PNG (Minecraft only reads PNG).
     */
    static Identifier icon(String projectId, String url) {
        if (url == null || !url.startsWith(ICON_ORIGIN) || !url.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            return null;
        }
        if (ICONS.containsKey(url)) {
            return ICONS.get(url);
        }
        if (!LOADING.contains(url)) {
            LOADING.add(url);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", "cube-utility-client").build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).whenCompleteAsync((response, error) -> {
                LOADING.remove(url);
                Identifier id = null;
                if (error == null && response.statusCode() == 200 && response.body().length < 4 * 1024 * 1024) {
                    id = CubeUtilityClient.id("modrinth_icons/" + projectId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "_"));
                    try (NativeImage image = NativeImage.read(new ByteArrayInputStream(response.body()))) {
                        Minecraft.getInstance().getTextureManager().register(id, new IconTexture(id, image));
                    } catch (Exception bad) {
                        id = null;
                    }
                }
                ICONS.put(url, id);
            }, Minecraft.getInstance());
        }
        return null;
    }
}
