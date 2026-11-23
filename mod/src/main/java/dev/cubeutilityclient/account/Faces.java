package dev.cubeutilityclient.account;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.util.UndashedUuid;
import dev.cubeutilityclient.CubeUtilityClient;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;

/**
 * Players' faces for the account button and the Accounts screen: the front of the head with the
 * hat layer over it, as an 8 x 8 texture drawn with sharp pixels. Skins come from the launcher,
 * which keeps every account's; an account without one shows its default skin, as in a world.
 */
public final class Faces {
    /** Faces made so far, by account and skin; null for a skin that couldn't be read. */
    private static final Map<String, Identifier> FACES = new HashMap<>();

    private Faces() {
    }

    /** The face texture for an account. Call on Minecraft's main thread. */
    public static Identifier get(UUID uuid, byte[] skin) {
        String key = UndashedUuid.toString(uuid) + "_" + (skin == null ? "default" : Integer.toHexString(Arrays.hashCode(skin)));
        if (FACES.containsKey(key)) {
            return FACES.get(key);
        }
        Identifier id = make(key, uuid, skin);
        FACES.put(key, id);
        return id;
    }

    private static Identifier make(String key, UUID uuid, byte[] png) {
        Minecraft minecraft = Minecraft.getInstance();
        NativeImage skin = read(png);
        if (skin == null) {
            skin = defaultSkin(minecraft, uuid);
        }
        if (skin == null) {
            return null;
        }
        try (NativeImage source = skin) {
            if (source.getWidth() < 64 || source.getHeight() < 32) {
                return null;
            }
            // Old 64 x 32 skins sometimes fill the hat with a solid colour; Minecraft hides such a hat.
            boolean hat = source.getHeight() >= 64 || hasClearPixel(source);
            NativeImage face = new NativeImage(8, 8, true);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int base = source.getPixel(8 + x, 8 + y) | 0xFF000000;
                    face.setPixel(x, y, hat ? over(source.getPixel(40 + x, 8 + y), base) : base);
                }
            }
            Identifier id = CubeUtilityClient.id("account_faces/" + key);
            minecraft.getTextureManager().register(id, new DynamicTexture(id::toString, face));
            return id;
        }
    }

    private static NativeImage read(byte[] png) {
        if (png == null) {
            return null;
        }
        try {
            return NativeImage.read(png);
        } catch (IOException | RuntimeException error) {
            CubeUtilityClient.LOGGER.debug("A skin from the launcher couldn't be read", error);
            return null;
        }
    }

    private static NativeImage defaultSkin(Minecraft minecraft, UUID uuid) {
        Identifier texture = DefaultPlayerSkin.get(uuid).body().texturePath();
        try (InputStream in = minecraft.getResourceManager().open(texture)) {
            return NativeImage.read(in);
        } catch (IOException | RuntimeException error) {
            CubeUtilityClient.LOGGER.debug("The default skin {} couldn't be read", texture, error);
            return null;
        }
    }

    /** Whether the hat area of an old 64 x 32 skin has see-through pixels at all. */
    private static boolean hasClearPixel(NativeImage skin) {
        for (int y = 0; y < 16; y++) {
            for (int x = 32; x < 64; x++) {
                if (skin.getPixel(x, y) >>> 24 < 128) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code top} drawn over an opaque {@code bottom}. */
    private static int over(int top, int bottom) {
        int a = top >>> 24;
        if (a == 0) {
            return bottom;
        }
        if (a == 255) {
            return top;
        }
        float k = a / 255f;
        int r = Math.round((top >> 16 & 255) * k + (bottom >> 16 & 255) * (1f - k));
        int g = Math.round((top >> 8 & 255) * k + (bottom >> 8 & 255) * (1f - k));
        int b = Math.round((top & 255) * k + (bottom & 255) * (1f - k));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
