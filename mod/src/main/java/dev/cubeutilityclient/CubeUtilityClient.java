package dev.cubeutilityclient;

import dev.cubeutilityclient.render.Pipelines;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.resources.Identifier;

/**
 * The Cube Utility Client look inside Minecraft: a space title screen, the client's colours in
 * every menu, and a dark game window with the client's name and icon.
 */
public final class CubeUtilityClient implements ClientModInitializer {
    public static final String ID = "cubeutilityclient";

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ID, path);
    }

    @Override
    public void onInitializeClient() {
        Pipelines.init();
    }
}
