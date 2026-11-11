package dev.cubeutilityclient.settings;

import dev.cubeutilityclient.mixin.GuiGraphicsExtractorAccessor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * A mod's own settings screen, shown inside a rectangle of the settings page instead of on the
 * whole window: it's laid out at the rectangle's size, drawn moved and clipped into it, and gets
 * the mouse in its own coordinates. Its widgets carry the client's style like every menu's.
 */
public final class EmbeddedScreen {
    /** How many embedded screens are drawing right now (they can nest). */
    private static int drawing;

    final Screen screen;
    private int x, y, width, height;
    private boolean placed;

    EmbeddedScreen(Screen screen) {
        this.screen = screen;
    }

    /** A settings screen is drawing inside the page: it must not blur or paint the backdrop. */
    public static boolean isDrawing() {
        return drawing > 0;
    }

    /** Lays the screen out in the rectangle (GUI units). */
    void place(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        if (!this.placed) {
            this.placed = true;
            this.width = width;
            this.height = height;
            this.screen.added();
            this.screen.init(width, height);
        } else if (width != this.width || height != this.height) {
            this.width = width;
            this.height = height;
            this.screen.resize(width, height);
        }
    }

    boolean contains(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
    }

    void extract(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        GuiGraphicsExtractorAccessor tooltips = (GuiGraphicsExtractorAccessor) graphics;
        Runnable before = tooltips.cubeutilityclient$deferredTooltip();
        graphics.enableScissor(this.x, this.y, this.x + this.width, this.y + this.height);
        graphics.pose().pushMatrix();
        graphics.pose().translate(this.x, this.y);
        drawing++;
        try {
            this.screen.extractRenderState(graphics, mouseX - this.x, mouseY - this.y, a);
        } finally {
            drawing--;
            graphics.pose().popMatrix();
            graphics.disableScissor();
        }
        // The screen asked for a tooltip at its own coordinates: draw it moved like the screen.
        Runnable tooltip = tooltips.cubeutilityclient$deferredTooltip();
        if (tooltip != null && tooltip != before) {
            int dx = this.x, dy = this.y;
            tooltips.cubeutilityclient$setDeferredTooltip(() -> {
                graphics.pose().pushMatrix();
                graphics.pose().translate(dx, dy);
                try {
                    tooltip.run();
                } finally {
                    graphics.pose().popMatrix();
                }
            });
        }
    }

    void tick() {
        this.screen.tick();
    }

    /** It goes away: screens save what they keep on close here. */
    void removed() {
        this.screen.removed();
    }

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return this.screen.mouseClicked(this.shift(event), doubleClick);
    }

    boolean mouseReleased(MouseButtonEvent event) {
        return this.screen.mouseReleased(this.shift(event));
    }

    boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return this.screen.mouseDragged(this.shift(event), dx, dy);
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return this.screen.mouseScrolled(mouseX - this.x, mouseY - this.y, scrollX, scrollY);
    }

    void mouseMoved(double mouseX, double mouseY) {
        this.screen.mouseMoved(mouseX - this.x, mouseY - this.y);
    }

    boolean keyPressed(KeyEvent event) {
        return this.screen.keyPressed(event);
    }

    boolean keyReleased(KeyEvent event) {
        return this.screen.keyReleased(event);
    }

    boolean charTyped(CharacterEvent event) {
        return this.screen.charTyped(event);
    }

    private MouseButtonEvent shift(MouseButtonEvent event) {
        return new MouseButtonEvent(event.x() - this.x, event.y() - this.y, event.buttonInfo());
    }
}
