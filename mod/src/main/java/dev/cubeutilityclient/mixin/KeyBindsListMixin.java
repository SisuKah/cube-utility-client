package dev.cubeutilityclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.cubeutilityclient.shelf.Shelf;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Controls lists no keys of mods that are still on the shelf, asleep and unbound. */
@Mixin(KeyBindsList.class)
public abstract class KeyBindsListMixin {
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/apache/commons/lang3/ArrayUtils;clone([Ljava/lang/Object;)[Ljava/lang/Object;"))
    private Object[] cubeutilityclient$hideSleepingKeys(Object[] keys) {
        return keys instanceof KeyMapping[] mappings ? Shelf.visibleKeys(mappings) : keys;
    }
}
