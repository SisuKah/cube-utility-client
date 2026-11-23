package dev.cubeutilityclient.account;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cubeutilityclient.CubeUtilityClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Accounts' way to the launcher: the doorway Browse Modrinth uses too. While the launcher runs the
 * game it answers on 127.0.0.1, at the port and with the token it leaves in
 * config/cubeutilityclient/launcher.json (game_api.rs in the launcher). The launcher keeps every
 * account's Microsoft sign-in and hands out Minecraft logins, so the game never sees a password
 * or a Microsoft token. Every answer arrives on Minecraft's main thread.
 */
public final class AccountLink {
    /** An account signed in on this PC. {@code skin} is its skin PNG, or null. */
    public record Account(String uuid, String name, byte[] skin, boolean slim) {
    }

    /**
     * The accounts on this PC. {@code launcherAccount} is the one PLAY starts the game with, and
     * {@code signingIn} says a sign-in is open in the browser.
     */
    public record Listing(List<Account> accounts, String launcherAccount, boolean signingIn) {
    }

    /** What Minecraft needs to play as an account. */
    public record Login(String uuid, String name, String accessToken, String xuid) {
    }

    /** An answer the player should read, like "Can't reach the sign-in servers." */
    public static final class Failure extends RuntimeException {
        private final boolean cancelled;

        Failure(String message, boolean cancelled) {
            super(message, null, false, false);
            this.cancelled = cancelled;
        }

        /** The player cancelled a sign-in: nothing to tell them. */
        public boolean cancelled() {
            return this.cancelled;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(4)).build();
    private static final String NOT_ANSWERING = "The launcher isn't answering. Keep it open while you play to switch accounts.";

    private final String base, token;

    private AccountLink(int port, String token) {
        this.base = "http://127.0.0.1:" + port;
        this.token = token;
    }

    /** The launcher's doorway, or null when Minecraft wasn't started by the launcher. */
    public static AccountLink find() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("cubeutilityclient").resolve("launcher.json");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            int port = json.get("port").getAsInt();
            String token = json.get("token").getAsString();
            return port > 0 && port < 65536 && !token.isBlank() ? new AccountLink(port, token) : null;
        } catch (Exception error) {
            return null;
        }
    }

    public CompletableFuture<Listing> accounts() {
        return this.call("GET", "/accounts", Duration.ofSeconds(15), AccountLink::listing);
    }

    /** The account's login, signing it in again first if needed. The launcher plays with it from now on too. */
    public CompletableFuture<Login> login(String uuid) {
        return this.call("POST", "/accounts/login?uuid=" + uuid, Duration.ofSeconds(60), AccountLink::login);
    }

    /** Opens Microsoft's sign-in page in the browser and waits for the player to finish there (5 minutes at most). */
    public CompletableFuture<Login> signIn() {
        return this.call("POST", "/accounts/sign-in", Duration.ofMinutes(6), AccountLink::login);
    }

    public CompletableFuture<Listing> cancelSignIn() {
        return this.call("POST", "/accounts/cancel-sign-in", Duration.ofSeconds(15), AccountLink::listing);
    }

    /** Signs the account out of this PC: the launcher forgets it. */
    public CompletableFuture<Listing> signOut(String uuid) {
        return this.call("POST", "/accounts/sign-out?uuid=" + uuid, Duration.ofSeconds(30), AccountLink::listing);
    }

    private static Listing listing(JsonObject json) {
        List<Account> accounts = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("accounts")) {
            JsonObject account = element.getAsJsonObject();
            byte[] skin = null;
            String base64 = text(account, "skin");
            if (!base64.isEmpty()) {
                try {
                    skin = Base64.getDecoder().decode(base64);
                } catch (IllegalArgumentException bad) {
                    skin = null;
                }
            }
            accounts.add(new Account(text(account, "uuid"), text(account, "name"), skin, account.has("slim") && account.get("slim").getAsBoolean()));
        }
        return new Listing(List.copyOf(accounts), text(json, "active"), json.has("signingIn") && json.get("signingIn").getAsBoolean());
    }

    private static Login login(JsonObject json) {
        return new Login(text(json, "uuid"), text(json, "name"), text(json, "accessToken"), text(json, "xuid"));
    }

    private static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private <T> CompletableFuture<T> call(String method, String path, Duration timeout, Function<JsonObject, T> read) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(this.base + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + this.token)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        // Handled on Minecraft's main thread, failures too, so the screen can just use the answer.
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).handleAsync((response, error) -> {
            if (error != null) {
                throw new Failure(NOT_ANSWERING, false);
            }
            JsonObject json;
            try {
                json = JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (Exception bad) {
                throw new Failure("The launcher sent something unexpected. Try again in a moment.", false);
            }
            if (response.statusCode() == 404) {
                // A launcher from before accounts in the game.
                throw new Failure("Update the Cube Utility Client launcher to switch accounts in the game.", false);
            }
            if (response.statusCode() != 200) {
                boolean cancelled = json.has("cancelled") && json.get("cancelled").getAsBoolean();
                throw new Failure(json.has("error") ? json.get("error").getAsString() : "That didn't work. Try again in a moment.", cancelled);
            }
            return read.apply(json);
        }, Minecraft.getInstance());
    }

    /** The failure behind a future's error, for the player to read. */
    public static Failure failure(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof Failure failure) {
            return failure;
        }
        CubeUtilityClient.LOGGER.warn("Switching accounts failed", cause);
        return new Failure("That didn't work. Try again in a moment.", false);
    }
}
