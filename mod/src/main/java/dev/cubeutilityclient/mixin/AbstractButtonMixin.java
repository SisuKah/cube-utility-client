package dev.cubeutilityclient.mixin;

import dev.cubeutilityclient.render.MenuStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every standard button, vanilla's and other mods', in the client's look: the glass pill instead
 * of the grey sprite, and a Manrope label. Buttons that draw their own background keep it.
 */
@Mixin(AbstractButton.class)
public abstract class AbstractButtonMixin extends AbstractWidget {
    protected AbstractButtonMixin(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    @Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true)
    private void cubeutilityclient$background(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        MenuStyle.button(graphics, this);
        ci.cancel();
    }

    @ModifyArg(
            method = "extractDefaultLabel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/AbstractButton;extractScrollingStringOverContents(Lnet/minecraft/client/gui/ActiveTextCollector;Lnet/minecraft/network/chat/Component;I)V"),
            index = 1)
    private Component cubeutilityclient$label(Component message) {
        return MenuStyle.label(message, this.isActive() && this.isHoveredOrFocused());
    }
}
