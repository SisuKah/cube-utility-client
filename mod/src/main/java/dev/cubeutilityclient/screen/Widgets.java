package dev.cubeutilityclient.screen;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.FontDescription;

/** Small helpers shared by the title screen's widgets. */
final class Widgets {
    private Widgets() {
    }

    /** Gives a widget the GUI-unit bounds that cover a rectangle in screen pixels. */
    static void bounds(AbstractWidget widget, float x, float y, float w, float h, float guiScale) {
        int left = (int) Math.floor(x / guiScale), top = (int) Math.floor(y / guiScale);
        widget.setX(left);
        widget.setY(top);
        widget.setWidth(Math.max(1, (int) Math.ceil((x + w) / guiScale) - left));
        widget.setHeight(Math.max(1, (int) Math.ceil((y + h) / guiScale) - top));
    }

    /** The pixel size in a font's name, like 12 for cubeutilityclient:label_12. */
    static float size(FontDescription font) {
        if (font instanceof FontDescription.Resource resource) {
            String path = resource.id().getPath();
            int cut = path.lastIndexOf('_');
            if (cut >= 0) {
                try {
                    return Integer.parseInt(path.substring(cut + 1));
                } catch (NumberFormatException ignored) {
                    // Not one of ours.
                }
            }
        }
        return 9f;
    }
}
