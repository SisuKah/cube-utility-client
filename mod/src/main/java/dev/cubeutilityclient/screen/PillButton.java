package dev.cubeutilityclient.screen;

import dev.cubeutilityclient.CubeUtilityClient;
import dev.cubeutilityclient.render.LineIcon;
import dev.cubeutilityclient.render.Motion;
import dev.cubeutilityclient.render.Paint;
import dev.cubeutilityclient.render.QuadBatch;
import dev.cubeutilityclient.render.Rgb;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/** The title screen's pill buttons: gold for Play CubeCraft, glass for the others. */
public final class PillButton extends AbstractButton {
    public enum Kind {
        GOLD,
        GLASS
    }

    private static final Identifier HAZE = CubeUtilityClient.id("textures/gui/haze.png");

    private final Kind kind;
    private final String label;
    private final boolean arrow;
    private final Runnable action;
    private final Motion motion = new Motion();
    private float x, y, w, h, s, spacing;
    private FontDescription font;
    private float appear = 1f;

    public PillButton(Kind kind, Component message, String label, boolean arrow, Runnable action) {
        super(0, 0, 1, 1, message);
        this.kind = kind;
        this.label = label != null ? label : message.getString().toUpperCase(Locale.ROOT);
        this.arrow = arrow;
        this.action = action;
    }

    /** Places the button in screen pixels; its clickable area follows in GUI units. */
    public void place(float x, float y, float height, float s, float guiScale, FontDescription font, float spacing) {
        this.x = x;
        this.y = y;
        this.h = height;
        this.s = s;
        this.font = font;
        this.spacing = spacing;
        float padLeft = (this.kind == Kind.GOLD ? 30f : 26f) * s, padRight = (this.kind == Kind.GOLD ? 24f : 26f) * s;
        this.w = Math.round(padLeft + Paint.measure(this.label, font, spacing) + (this.arrow ? 30f * s : 0f) + padRight);
        Widgets.bounds(this, x, y, this.w, height, guiScale);
    }

    public float right() {
        return this.x + this.w;
    }

    /** 0 while the screen fades in, 1 once it is there. */
    public void appear(float k) {
        this.appear = k;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (this.font == null || this.appear <= 0f) {
            return;
        }
        float hover = this.motion.towards(this.isHoveredOrFocused() && this.active ? 1f : 0f, 0.3f);
        Paint paint = new Paint(graphics);
        float rise = (1f - this.appear) * 16f * this.s;
        float top = Math.round(this.y + rise - (this.kind == Kind.GOLD ? hover * this.s : 0f));
        float x = this.x, w = this.w, h = this.h;
        float k = this.appear;
        int text;
        if (this.kind == Kind.GOLD) {
            if (hover > 0.01f) {
                float cx = x + w / 2f, cy = top + h / 2f + 12f * this.s;
                paint.texture(HAZE, false).rect(cx - w / 2f - 44f * this.s, cy - h / 2f - 44f * this.s, cx + w / 2f + 44f * this.s, cy + h / 2f + 44f * this.s,
                        Rgb.alpha(0xE4C084, 0.38f * hover * k));
            }
            QuadBatch body = paint.solid();
            if (hover > 0.01f) {
                Paint.capsule(body, x - 1f, top - 1f, w + 2f, h + 2f, 1f, (px, py) -> 0, Rgb.alpha(0xFFEECE, 0.55f * hover * k), false);
            }
            Paint.capsule(body, x, top, w, h, 0f, (px, py) -> gold(px - x, py - top, w, h, k), 0, true);
            text = Rgb.alpha(0x15100A, k);
        } else {
            QuadBatch body = paint.solid();
            int fill = Rgb.alpha(0xFFFFFF, (0.04f + 0.05f * hover) * k);
            int border = Rgb.lerp(Rgb.alpha(0xFFFFFF, 0.16f * k), Rgb.alpha(0xE4C084, 0.75f * k), hover);
            Paint.capsule(body, x, top, w, h, 1f, (px, py) -> fill, border, false);
            text = Rgb.lerp(Rgb.alpha(0xE9EFEE, k), Rgb.alpha(0xF6E3BC, k), hover);
        }
        float fontPx = Widgets.size(this.font);
        float padLeft = (this.kind == Kind.GOLD ? 30f : 26f) * this.s;
        float baseline = Math.round(top + h / 2f + fontPx * 0.36f);
        paint.text(this.label, this.font, Math.round(x + padLeft), baseline, text, this.spacing);
        if (this.arrow) {
            float size = Math.round(Math.max(11f, 16f * this.s));
            float ax = Math.round(x + padLeft + Paint.measure(this.label, this.font, this.spacing) + 14f * this.s);
            float ay = Math.round(top + (h - size) / 2f);
            Paint.icon(paint.solid(), LineIcon.ARROW, ax, ay, size, text);
        }
    }

    /** The gold gradient at 135 degrees: #F7E6C2, #E4C084 at 46%, #C29756. */
    private static int gold(float x, float y, float w, float h, float alpha) {
        float k = Math.max(0f, Math.min(1f, (x + y) / (w + h)));
        int light = Rgb.alpha(0xF7E6C2, alpha), mid = Rgb.alpha(0xE4C084, alpha), deep = Rgb.alpha(0xC29756, alpha);
        return k < 0.46f ? Rgb.lerp(light, mid, k / 0.46f) : Rgb.lerp(mid, deep, (k - 0.46f) / 0.54f);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
