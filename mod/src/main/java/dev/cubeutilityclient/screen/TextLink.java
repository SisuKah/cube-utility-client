package dev.cubeutilityclient.screen;

import dev.cubeutilityclient.render.Motion;
import dev.cubeutilityclient.render.Paint;
import dev.cubeutilityclient.render.Rgb;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;

/** Quiet clickable text: Mojang's copyright line, which opens the credits. */
public final class TextLink extends AbstractButton {
    private final Runnable action;
    private final Motion motion = new Motion();
    private float x, baseline;
    private FontDescription font;

    public TextLink(Component message, Runnable action) {
        super(0, 0, 1, 1, message);
        this.action = action;
    }

    /** Places the text with its right edge at {@code right} and its baseline at {@code baseline}. */
    public void place(float right, float baseline, FontDescription font, float guiScale) {
        this.font = font;
        float width = Paint.measure(this.getMessage().getString(), font, 0f);
        float size = Widgets.size(font);
        this.x = Math.round(right - width);
        this.baseline = Math.round(baseline);
        Widgets.bounds(this, this.x, this.baseline - size, width, size * 1.4f, guiScale);
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (this.font == null) {
            return;
        }
        float hover = this.motion.towards(this.isHoveredOrFocused() ? 1f : 0f, 0.3f);
        int color = Rgb.lerp(0xFF8A9696, 0xFFE9EFEE, hover);
        new Paint(graphics).text(this.getMessage().getString(), this.font, this.x, this.baseline, color, 0f);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
