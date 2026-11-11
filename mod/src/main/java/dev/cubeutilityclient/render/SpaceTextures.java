package dev.cubeutilityclient.render;

import dev.cubeutilityclient.CubeUtilityClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

/**
 * The mod's textures (made by tools/GenTextures.java). Most are drawn much smaller than they are
 * stored (a 64 px glow as a 3 px star), so they get mipmaps: without them moving stars shimmer.
 */
public final class SpaceTextures {
    public static final Identifier HAZE = texture("haze");
    private static final String[] ALL = {"glow", "haze", "core", "dust", "vignette", "shade"};
    private static boolean registered;

    private SpaceTextures() {
    }

    public static Identifier texture(String name) {
        return CubeUtilityClient.id("textures/gui/" + name + ".png");
    }

    /**
     * Loads every texture with mipmaps, once, before the first one is drawn. The texture manager
     * keeps them and reloads them with resource packs.
     */
    static void ensureRegistered() {
        if (registered) {
            return;
        }
        registered = true;
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        for (String name : ALL) {
            Identifier location = texture(name);
            textures.registerAndLoad(location, new SmoothTexture(location));
        }
    }
}
