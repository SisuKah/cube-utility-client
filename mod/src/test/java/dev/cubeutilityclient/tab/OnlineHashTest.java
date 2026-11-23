package dev.cubeutilityclient.tab;

import static org.junit.Assert.assertEquals;

import java.util.UUID;
import org.junit.Test;

/** The same examples as the stats site's test/client.test.ts: if one side changes, both must. */
public class OnlineHashTest {
    @Test
    public void hashesAccountsLikeTheSite() {
        assertEquals("7d2d17b6e5a78816", OnlineHash.of(UUID.fromString("3f0c1d2e-4b5a-4c6d-8e7f-9a0b1c2d3e4f")));
        assertEquals("07eb62fcab9ea165", OnlineHash.of(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")));
    }

    @Test
    public void writesServerHashesLikeMinecraft() {
        // Minecraft's own examples, also in the site's lib/digest.ts.
        assertEquals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48", OnlineHash.digest("Notch"));
        assertEquals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1", OnlineHash.digest("jeb_"));
        assertEquals(OnlineHash.digest("cubeutility-client:abc"), OnlineHash.serverId("abc"));
    }
}
