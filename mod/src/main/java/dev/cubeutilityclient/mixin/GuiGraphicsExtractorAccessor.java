package dev.cubeutilityclient.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The tooltip a screen asks for during a frame. The settings page shows mods' own settings
 * screens inside its panel, shifted; their tooltips have to move with them.
 */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {
    @Accessor("deferredTooltip")
    Runnable cubeutilityclient$deferredTooltip();

    @Accessor("deferredTooltip")
    void cubeutilityclient$setDeferredTooltip(Runnable tooltip);
}
