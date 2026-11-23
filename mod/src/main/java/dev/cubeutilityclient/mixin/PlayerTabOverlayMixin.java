package dev.cubeutilityclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeutilityclient.tab.TabMarker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The launcher's mark in front of the tab list's names of players who play with the client too
 * (TabMarker). The names themselves are left as they are, because other mods recognise them:
 * CubeCraft Level Rank keeps the very name objects it decorated, to draw its rank bar in the space
 * it put at their start. So the mark gets room where the list measures a name, and is drawn where
 * it draws one, with the name moved right after it.
 *
 * <p>Priority 1500 puts these wrappers outside other mods' default-priority ones on the same
 * calls, so Level Rank draws its bar where the name now starts, not under the mark.
 */
@Mixin(value = PlayerTabOverlay.class, priority = 1500)
public abstract class PlayerTabOverlayMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void cubeutilityclient$newList(GuiGraphicsExtractor graphics, int screenWidth, Scoreboard scoreboard, Objective objective, CallbackInfo ci) {
        TabMarker.begin();
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;"))
    private Component cubeutilityclient$noteName(PlayerTabOverlay overlay, PlayerInfo info, Operation<Component> original) {
        Component name = original.call(overlay, info);
        TabMarker.note(info, name);
        return name;
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"))
    private int cubeutilityclient$roomForMark(Font font, FormattedText text, Operation<Integer> original) {
        return original.call(font, text) + TabMarker.extraWidth(font, text);
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
    private void cubeutilityclient$drawMark(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color, Operation<Void> original) {
        int shift = TabMarker.drawMark(graphics, font, text, x, y, color);
        original.call(graphics, font, text, x + shift, y, color);
    }
}
