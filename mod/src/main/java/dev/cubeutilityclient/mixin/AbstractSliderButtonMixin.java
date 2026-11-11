package dev.cubeutilityclient.mixin;

import dev.cubeutilityclient.render.MenuStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sliders (FOV, volume, render distance...) as a glass pill filled with gold up to the value. */
@Mixin(AbstractSliderButton.class)
public abstract class AbstractSliderButtonMixin extends AbstractWidget {
    @Shadow
    protected double value;

    protected AbstractSliderButtonMixin(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"), cancellable = true)
    private void cubeutilityclient$slider(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        MenuStyle.slider(graphics, this, (float) this.value);
        this.extractScrollingStringOverContents(
                graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE), MenuStyle.label(this.getMessage(), false), 2);
        this.handleCursor(graphics);
        ci.cancel();
    }
}
