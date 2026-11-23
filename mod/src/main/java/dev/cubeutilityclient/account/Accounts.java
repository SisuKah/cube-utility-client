package dev.cubeutilityclient.account;

import com.mojang.util.UndashedUuid;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The launcher's accounts as last heard, for the title screen's account button and the Accounts screen. */
public final class Accounts {
    private static AccountLink.Listing last = new AccountLink.Listing(List.of(), "", false);

    private Accounts() {
    }

    public static AccountLink.Listing last() {
        return last;
    }

    public static void remember(AccountLink.Listing listing) {
        last = listing;
    }

    /**
     * Asks the launcher for its accounts again; the answer arrives on Minecraft's main thread.
     * Without a launcher, the last answer (none) stays.
     */
    public static CompletableFuture<AccountLink.Listing> refresh() {
        AccountLink link = AccountLink.find();
        if (link == null) {
            return CompletableFuture.completedFuture(last);
        }
        return link.accounts().thenApply(listing -> {
            last = listing;
            return listing;
        });
    }

    /** The launcher's copy of an account's skin, or null. */
    public static byte[] skin(UUID uuid) {
        AccountLink.Account account = find(uuid);
        return account == null ? null : account.skin();
    }

    public static AccountLink.Account find(UUID uuid) {
        String id = UndashedUuid.toString(uuid);
        for (AccountLink.Account account : last.accounts()) {
            if (same(account.uuid(), id)) {
                return account;
            }
        }
        return null;
    }

    /** Whether two UUIDs are the same, dashes or not. */
    public static boolean same(String a, String b) {
        return a != null && b != null && a.replace("-", "").toLowerCase(Locale.ROOT).equals(b.replace("-", "").toLowerCase(Locale.ROOT));
    }
}
