package dev.cubeutilityclient.mixin;

import dev.cubeutilityclient.render.MenuStyle;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FontDescription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Text in the default font uses Manrope in menus outside a world: titles, headers, lists and
 * tooltips match the buttons. Drawing and measuring both go through here, so layouts stay right.
 */
@Mixin(Font.class)
public abstract class FontMixin {
    @ModifyVariable(method = "getGlyphSource", at = @At("HEAD"), argsOnly = true)
    private FontDescription cubeutilityclient$menuFont(FontDescription font) {
        return MenuStyle.menuFont(font);
    }
}
