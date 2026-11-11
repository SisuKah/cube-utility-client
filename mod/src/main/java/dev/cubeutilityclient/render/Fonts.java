package dev.cubeutilityclient.render;

import dev.cubeutilityclient.CubeUtilityClient;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.chat.FontDescription;

/**
 * Manrope at exact pixel sizes. Minecraft samples font glyphs without smoothing, so every size
 * is its own font (assets/cubeutilityclient/font) drawn 1:1 on screen pixels.
 */
public final class Fonts {
    private static final int[] TITLE = {32, 36, 40, 44, 48, 52, 56, 60, 64, 70, 76, 80, 88, 96, 104, 112, 120, 128, 140, 152, 160, 176};
    private static final Map<String, FontDescription> CACHE = new HashMap<>();

    private Fonts() {
    }

    /** ExtraLight, for the big title. */
    public static FontDescription title(float px) {
        int best = TITLE[0];
        for (int size : TITLE) {
            if (Math.abs(size - px) < Math.abs(best - px)) {
                best = size;
            }
        }
        return named("title_" + best);
    }

    /** SemiBold, for labels. */
    public static FontDescription label(float px) {
        return named("label_" + Math.max(8, Math.min(32, Math.round(px))));
    }

    /** ExtraBold, for the gold button. */
    public static FontDescription strong(float px) {
        return named("strong_" + Math.max(8, Math.min(32, Math.round(px))));
    }

    /**
     * SemiBold for labels inside Minecraft's own menus, which are drawn in GUI units: rendered
     * at {@code guiScale} times its size, so each glyph pixel is one screen pixel.
     */
    public static FontDescription ui(int guiScale) {
        return named("ui_" + Math.max(1, Math.min(8, guiScale)));
    }

    private static FontDescription named(String name) {
        return CACHE.computeIfAbsent(name, key -> new FontDescription.Resource(CubeUtilityClient.id(key)));
    }
}
