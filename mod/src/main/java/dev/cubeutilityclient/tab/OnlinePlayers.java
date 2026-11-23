package dev.cubeutilityclient.tab;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cubeutilityclient.CubeUtilityClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who else plays with Cube Utility Client, for the mark in the tab list.
 *
 * <p>While the game is on a server, it tells the Cube Utility stats site so every 10 minutes,
 * proving the account the way the game does when it joins an online-mode server: it joins a
 * "server" at Mojang whose id is a digest of our prefix and the site's nonce (OnlineHash), and the
 * site asks Mojang whether it did. The login token only ever goes to Mojang. Every 2 minutes it
 * downloads who did so in the last 25 minutes, as short hashes of their UUIDs, and checks the
 * players it sees against them. The site's side is web/src/lib/client.ts in the Cube Utility
 * repository.
 *
 * <p>Nothing is sent from the title screen or a singleplayer world (unless it is open to LAN),
 * and the player's own name is marked without asking anyone.
 */
public final class OnlinePlayers {
    private static final Logger LOGGER = LoggerFactory.getLogger("Cube Utility Client");

    /** The stats site's API. {@code -Dcubeutilityclient.tabMarkerApi=http://localhost:3000/api/v1} tries a local copy. */
    private static final String API = System.getProperty("cubeutilityclient.tabMarkerApi",
            "https://cubecraft-utility-web.vercel.app/api/v1").replaceAll("/+$", "");
    /** {@code -Dcubeutilityclient.tabMarker=off} turns the marker off: no requests and no marks. */
    static final boolean ENABLED = !"off".equalsIgnoreCase(System.getProperty("cubeutilityclient.tabMarker", ""));

    /** The site counts a game as online for 25 minutes after it last proved itself. */
    private static final long ANNOUNCE_EVERY = Duration.ofMinutes(10).toMillis();
    /** After a failure; doubles each time up to MAX_RETRY (an offline account never succeeds). */
    private static final long FIRST_RETRY = Duration.ofMinutes(2).toMillis();
    private static final long MAX_RETRY = Duration.ofMinutes(40).toMillis();
    /** The site's answer is cached for a minute, so asking more often gains nothing. */
    private static final long FETCH_EVERY = Duration.ofMinutes(2).toMillis();
    private static final long FETCH_RETRY = Duration.ofMinutes(1).toMillis();

    /** One background thread: every step is a blocking web request. */
    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Cube Utility Client tab marker");
        t.setDaemon(true);
        return t;
    });
    /** HTTP/1.1: a few small requests gain nothing from HTTP/2, and its upgrade over plain http trips up some servers. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final String USER_AGENT = "CubeUtilityClient/" + FabricLoader.getInstance().getModContainer(CubeUtilityClient.ID)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("dev");

    /** The hashes of everyone online with the client, as last downloaded. */
    private static volatile Set<String> online = Set.of();
    private static final Map<UUID, String> HASHES = new ConcurrentHashMap<>();

    // Written by the game thread, and by the background thread when a step ends.
    private static volatile boolean busy;
    private static volatile long nextAnnounce;
    private static volatile long nextFetch;
    private static volatile long lastFetched;
    private static volatile long retry = FIRST_RETRY;
    private static volatile boolean warned;
    private static ClientPacketListener lastConnection;

    private OnlinePlayers() {
    }

    /** Whether the player with this UUID plays with the client too: you always do. */
    public static boolean isMarked(UUID id) {
        if (!ENABLED || id == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && id.equals(minecraft.player.getUUID())) {
            return true;
        }
        Set<String> hashes = online;
        if (hashes.isEmpty()) {
            return false;
        }
        if (HASHES.size() > 20_000) {
            HASHES.clear();
        }
        return hashes.contains(HASHES.computeIfAbsent(id, OnlineHash::of));
    }

    /** Called at the end of every game tick: starts the next step when one is due. */
    public static void tick(Minecraft minecraft) {
        if (!ENABLED) {
            return;
        }
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null || minecraft.level == null || !withOthers(minecraft)) {
            lastConnection = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (connection != lastConnection) {
            // Just joined: a fresh list, unless the last one is only seconds old.
            lastConnection = connection;
            if (now - lastFetched > 30_000) {
                nextFetch = now;
            }
        }
        if (busy) {
            return;
        }
        if (now >= nextAnnounce) {
            busy = true;
            User user = minecraft.getUser();
            THREAD.execute(() -> announce(minecraft, user));
        } else if (now >= nextFetch) {
            busy = true;
            THREAD.execute(OnlinePlayers::fetch);
        }
    }

    /** On a server, or hosting a world open to LAN: where other players can be in the tab list. */
    private static boolean withOthers(Minecraft minecraft) {
        IntegratedServer own = minecraft.getSingleplayerServer();
        return !minecraft.isLocalServer() || own != null && own.isPublished();
    }

    /** Proves the account to the site, which then counts it as online. */
    private static void announce(Minecraft minecraft, User user) {
        try {
            JsonObject ask = new JsonObject();
            ask.addProperty("uuid", user.getProfileId().toString());
            ask.addProperty("name", user.getName());
            JsonObject challenge = post("/client/challenge", ask);
            String challengeId = challenge.get("challengeId").getAsString();
            String nonce = challenge.get("nonce").getAsString();
            minecraft.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), OnlineHash.serverId(nonce));
            JsonObject done = new JsonObject();
            done.addProperty("challengeId", challengeId);
            post("/client/online", done);
            nextAnnounce = System.currentTimeMillis() + ANNOUNCE_EVERY;
            retry = FIRST_RETRY;
            warned = false;
            // The list now has this game too; download it again for anyone who joined meanwhile.
            nextFetch = 0;
        } catch (Exception e) {
            nextAnnounce = System.currentTimeMillis() + retry;
            retry = Math.min(MAX_RETRY, retry * 2);
            if (!warned) {
                warned = true;
                LOGGER.warn("Tab marker: couldn't tell the stats site this game runs the client: {}", describe(e));
            }
        } finally {
            busy = false;
        }
    }

    /** Downloads who is online with the client. */
    private static void fetch() {
        try {
            HttpResponse<String> response = HTTP.send(request("/client/online").GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonObject body = parse(response);
            Set<String> hashes = new HashSet<>();
            for (JsonElement player : body.getAsJsonArray("players")) {
                String hash = player.getAsString();
                if (hash.length() == 16) {
                    hashes.add(hash);
                }
            }
            online = Set.copyOf(hashes);
            lastFetched = System.currentTimeMillis();
            nextFetch = lastFetched + FETCH_EVERY;
        } catch (Exception e) {
            nextFetch = System.currentTimeMillis() + FETCH_RETRY;
            LOGGER.debug("Tab marker: couldn't download who is online: {}", describe(e));
        } finally {
            busy = false;
        }
    }

    private static JsonObject post(String path, JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = request(path)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return parse(HTTP.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(10)).header("user-agent", USER_AGENT);
    }

    /** The answer as JSON, or an exception with the site's message when it refused. */
    private static JsonObject parse(HttpResponse<String> response) throws IOException {
        JsonObject body;
        try {
            body = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("the site answered " + response.statusCode() + " without JSON");
        }
        if (response.statusCode() / 100 != 2) {
            String message = body.has("message") ? body.get("message").getAsString() : "";
            throw new IOException("the site answered " + response.statusCode() + (message.isEmpty() ? "" : ": " + message));
        }
        return body;
    }

    private static String describe(Exception e) {
        String type = e.getClass().getSimpleName();
        if (type.contains("InvalidCredentials")) {
            return "Mojang says the game's login isn't valid (an offline account?)";
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? type : message;
    }
}
