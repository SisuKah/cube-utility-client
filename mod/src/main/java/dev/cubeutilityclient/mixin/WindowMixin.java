package dev.cubeutilityclient.mixin;

import com.mojang.blaze3d.platform.IconSet;
import com.mojang.blaze3d.platform.Window;
import dev.cubeutilityclient.window.WindowStyle;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The client's icon on the game window, and a dark title bar. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Shadow
    @Final
    private long handle;

    @Shadow
    private void setIcon(List<IoSupplier<InputStream>> iconStreams) throws IOException {
    }

    /** Minecraft sets the icon right after creating the window, the moment to style it too. */
    @Inject(method = "setIcon(Lnet/minecraft/server/packs/PackMetadataResources;Lcom/mojang/blaze3d/platform/IconSet;)V", at = @At("HEAD"), cancellable = true)
    private void cubeutilityclient$icon(PackMetadataResources resources, IconSet iconSet, CallbackInfo ci) throws IOException {
        WindowStyle.darkTitleBar(this.handle);
        this.setIcon(WindowStyle.icons());
        ci.cancel();
    }
}
