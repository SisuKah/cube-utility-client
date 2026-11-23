package dev.cubeutilityclient.tab;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The two values the tab marker shares with the stats site (web/src/lib/client.ts in the Cube
 * Utility repository); both sides must compute them the same way. OnlineHashTest checks the same
 * examples as the site's test/client.test.ts.
 */
public final class OnlineHash {
    static final String PREFIX = "cubeutility-client:";

    private OnlineHash() {
    }

    /**
     * An account as the site's list of who is online holds it: the first 16 hex digits of SHA-256
     * over the prefix and the UUID's 32 lowercase hex digits.
     */
    public static String of(UUID id) {
        byte[] digest = sha("SHA-256", PREFIX + id.toString().replace("-", ""));
        return HexFormat.of().formatHex(digest, 0, 8);
    }

    /**
     * The "server" the game joins at Mojang to prove its account: Minecraft's server hash of the
     * prefix and the site's nonce. Derived here, so the site can never get the game to join with a
     * real server's login hash.
     */
    public static String serverId(String nonce) {
        return digest(PREFIX + nonce);
    }

    /** Minecraft's server hash: SHA-1 written as a signed hexadecimal number. */
    static String digest(String text) {
        return new BigInteger(sha("SHA-1", text)).toString(16);
    }

    private static byte[] sha(String algorithm, String text) {
        try {
            return MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
