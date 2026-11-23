package dev.cubeutilityclient.tab;

import dev.cubeutilityclient.render.Fonts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * The launcher's mark, a gold outline cube with a sparkle, in front of the names in the tab list
 * of players who play with Cube Utility Client too (OnlinePlayers), the way other clients show
 * their logo there.
 *
 * <p>The mark is a glyph in its own font, so the tab list sizes its columns with it like any
 * other text. Minecraft draws font glyphs without smoothing, so each GUI scale has its own font
 * (assets/cubeutilityclient/font/badge_N.json) whose picture has exactly one pixel per screen
 * pixel (drawn by tools/GenTextures.java). U+E000 is the mark and U+E001 a narrow space between
 * it and the name.
 */
public final class TabMarker {
    private static final String MARK = "";

    private TabMarker() {
    }

    /** The name the tab list shows for this player, with the mark in front when they play with the client. */
    public static Component decorate(PlayerInfo info, Component name) {
        if (!OnlinePlayers.isMarked(info.getProfile().id())) {
            return name;
        }
        return Component.empty().append(mark()).append(name);
    }

    private static Component mark() {
        int guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        // White keeps the picture's own colours under team colours; no shadow under a picture.
        Style style = Style.EMPTY.withFont(Fonts.badge(guiScale)).withColor(0xFFFFFF).withShadowColor(Style.NO_SHADOW);
        return Component.literal(MARK).withStyle(style);
    }
}
