package dev.cubeutilityclient.render;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.TextColor;

/**
 * The client's look for Minecraft's own buttons and sliders, in every menu: glass pills with a
 * hairline border that turns gold on hover, gold slider fills, and Manrope labels.
 */
public final class MenuStyle {
    private static final Map<AbstractWidget, Motion> HOVER = new WeakHashMap<>();

    private MenuStyle() {
    }

    private static float hover(AbstractWidget widget) {
        return HOVER.computeIfAbsent(widget, key -> new Motion()).towards(widget.isActive() && widget.isHoveredOrFocused() ? 1f : 0f, 0.25f);
    }

    /** A button's background. */
    public static void button(GuiGraphicsExtractor graphics, AbstractWidget widget) {
        Paint paint = new Paint(graphics);
        float g = paint.guiScale;
        float alpha = widget.getAlpha();
        float hover = hover(widget);
        int fill, border;
        if (widget.isActive()) {
            fill = Rgb.alpha(0xFFFFFF, (0.045f + 0.045f * hover) * alpha);
            border = Rgb.lerp(Rgb.alpha(0xFFFFFF, 0.16f * alpha), Rgb.alpha(0xE4C084, 0.8f * alpha), hover);
        } else {
            fill = Rgb.alpha(0xFFFFFF, 0.02f * alpha);
            border = Rgb.alpha(0xFFFFFF, 0.07f * alpha);
        }
        Paint.capsule(paint.solid(), widget.getX() * g, widget.getY() * g, widget.getWidth() * g, widget.getHeight() * g, 1f, (px, py) -> fill, border, false);
    }

    /** A slider: the same pill, gold up to the value, and a glowing knob. */
    public static void slider(GuiGraphicsExtractor graphics, AbstractWidget widget, float value) {
        Paint paint = new Paint(graphics);
        float g = paint.guiScale;
        float x = widget.getX() * g, y = widget.getY() * g, w = widget.getWidth() * g, h = widget.getHeight() * g;
        float alpha = widget.getAlpha();
        float hover = hover(widget);
        QuadBatch body = paint.solid();
        int fill = Rgb.alpha(0xFFFFFF, (0.035f + 0.035f * hover) * alpha);
        int border = Rgb.lerp(Rgb.alpha(0xFFFFFF, 0.14f * alpha), Rgb.alpha(0xE4C084, 0.7f * alpha), hover);
        Paint.capsule(body, x, y, w, h, 1f, (px, py) -> fill, border, false);
        float knob = x + h / 2f + Math.max(0f, Math.min(1f, value)) * (w - h);
        float filled = knob - x + h / 2f;
        if (value > 0.01f) {
            float x0 = x + 1f;
            Paint.capsule(body, x0, y + 1f, Math.max(h - 2f, filled - 1f), h - 2f, 0f,
                    (px, py) -> Rgb.lerp(Rgb.alpha(0xE4C084, 0.03f * alpha), Rgb.alpha(0xE4C084, 0.26f * alpha), (px - x0) / Math.max(1f, filled)), 0, true);
        }
        // A slim gold handle, so the label over it stays readable.
        float kw = Math.max(3f, Math.round(h * 0.11f)), kh = Math.round(h * 0.56f);
        float kx = Math.round(knob - kw / 2f), ky = Math.round(y + (h - kh) / 2f);
        paint.texture(SpaceTextures.HAZE, true).rect(knob - h * 0.55f, y, knob + h * 0.55f, y + h, Rgb.alpha(0xE4C084, (0.22f + 0.18f * hover) * alpha));
        Paint.capsule(paint.solid(), kx, ky, kw, kh, 0f, (px, py) -> Rgb.alpha(0xF2DDB2, 0.95f * alpha), 0, false);
    }

    /**
     * The font for text in Minecraft's default font. Outside a world, menus use Manrope. In a
     * world it stays Minecraft's own: servers like CubeCraft draw their menus with extra glyphs in
     * the default font, and those must keep lining up.
     */
    public static FontDescription menuFont(FontDescription font) {
        if (!FontDescription.DEFAULT.equals(font)) {
            return font;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level != null || minecraft.gui == null || minecraft.gui.overlay() != null) {
            return font;
        }
        return Fonts.ui(minecraft.getWindow().getGuiScale());
    }

    /**
     * A label in Manrope (and gold while hovered). Labels that already use a special font, like
     * icon fonts, are left alone.
     */
    public static Component label(Component message, boolean hovered) {
        if (!message.getStyle().getFont().equals(FontDescription.DEFAULT)) {
            return message;
        }
        FontDescription font = Fonts.ui(Minecraft.getInstance().getWindow().getGuiScale());
        return message.copy().withStyle(style -> {
            style = style.withFont(font);
            return hovered ? style.withColor(TextColor.fromRgb(0xF6E3BC)) : style;
        });
    }
}
