package dev.cubeutilityclient.tab;

import dev.cubeutilityclient.render.Fonts;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

/**
 * The launcher's mark, a gold outline cube with a sparkle, in front of the names in the tab list
 * of players who play with Cube Utility Client too (OnlinePlayers), the way other clients show
 * their logo there. PlayerTabOverlayMixin calls in while the list is drawn.
 *
 * <p>The mark is a glyph in its own font. Minecraft draws font glyphs without smoothing, so each
 * GUI scale has its own font (assets/cubeutilityclient/font/badge_N.json) whose picture has
 * exactly one pixel per screen pixel (drawn by tools/GenTextures.java). U+E000 is the mark and
 * U+E001 a narrow space between it and the name.
 */
public final class TabMarker {
    private static final String MARK = "";
    /** The names in the list being drawn that get the mark, as the very objects the list holds. */
    private static final Set<Component> MARKED = Collections.newSetFromMap(new IdentityHashMap<>());

    private TabMarker() {
    }

    /** A new list is being drawn. */
    public static void begin() {
        MARKED.clear();
    }

    /** The list got this player's name: remember it when they play with the client. */
    public static void note(PlayerInfo info, Component name) {
        if (name != null && OnlinePlayers.isMarked(info.getProfile().id())) {
            MARKED.add(name);
        }
    }

    /** The room the mark needs in front of this text, if it's a marked name; otherwise 0. */
    public static int extraWidth(Font font, FormattedText text) {
        return text instanceof Component name && MARKED.contains(name) ? font.width(mark()) : 0;
    }

    /**
     * Draws the mark where this text is about to be drawn, if it's a marked name.
     *
     * @return how far to move the name right to make room, or 0 when it gets no mark
     */
    public static int drawMark(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color) {
        if (!MARKED.contains(text)) {
            return 0;
        }
        Component mark = mark();
        graphics.text(font, mark, x, y, color, false);
        return font.width(mark);
    }

    private static Component mark() {
        int guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        // White keeps the picture's own colours; the colour passed in still fades it for spectators.
        Style style = Style.EMPTY.withFont(Fonts.badge(guiScale)).withColor(0xFFFFFF).withShadowColor(Style.NO_SHADOW);
        return Component.literal(MARK).withStyle(style);
    }
}
