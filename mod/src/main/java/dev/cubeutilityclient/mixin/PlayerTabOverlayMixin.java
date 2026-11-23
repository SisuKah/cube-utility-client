package dev.cubeutilityclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeutilityclient.tab.TabMarker;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The tab list's names get the launcher's mark in front for players who play with the client too
 * (TabMarker). Only where the list is drawn: the name is measured and drawn from this one call, so
 * the columns make room for the mark, and the narrator still reads the plain names.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;"))
    private Component cubeutilityclient$marker(PlayerTabOverlay overlay, PlayerInfo info, Operation<Component> original) {
        return TabMarker.decorate(info, original.call(overlay, info));
    }
}
