package dev.cubeutilityclient.screen;

import dev.cubeutilityclient.render.LineIcon;
import dev.cubeutilityclient.render.Motion;
import dev.cubeutilityclient.render.Paint;
import dev.cubeutilityclient.render.QuadBatch;
import dev.cubeutilityclient.render.Rgb;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** A round glass button with a gold line icon: Mods, Settings and Quit. */
public final class IconButton extends AbstractButton {
    private final LineIcon icon;
    private final Runnable action;
    private final Motion motion = new Motion();
    private float x, y, size, iconSize;
    private float appear = 1f;

    public IconButton(LineIcon icon, Component message, Runnable action) {
        super(0, 0, 1, 1, message);
        this.icon = icon;
        this.action = action;
    }

    public void place(float x, float y, float size, float iconSize, float guiScale) {
        this.x = x;
        this.y = y;
        this.size = size;
        this.iconSize = iconSize;
        Widgets.bounds(this, x, y, size, size, guiScale);
    }

    public void appear(float k) {
        this.appear = k;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (this.size <= 0f || this.appear <= 0f) {
            return;
        }
        float hover = this.motion.towards(this.isHoveredOrFocused() && this.active ? 1f : 0f, 0.3f);
        float k = this.appear;
        Paint paint = new Paint(graphics);
        QuadBatch body = paint.solid();
        int fill = Rgb.alpha(0xFFFFFF, (0.04f + 0.05f * hover) * k);
        int border = Rgb.lerp(Rgb.alpha(0xFFFFFF, 0.16f * k), Rgb.alpha(0xE4C084, 0.75f * k), hover);
        Paint.capsule(body, this.x, this.y, this.size, this.size, 1f, (px, py) -> fill, border, false);
        float ix = Math.round(this.x + (this.size - this.iconSize) / 2f), iy = Math.round(this.y + (this.size - this.iconSize) / 2f);
        int tint = Rgb.lerp(Rgb.alpha(0xE4C084, k), Rgb.alpha(0xF6E3BC, k), hover);
        Paint.icon(body, this.icon, ix, iy, this.iconSize, tint);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
