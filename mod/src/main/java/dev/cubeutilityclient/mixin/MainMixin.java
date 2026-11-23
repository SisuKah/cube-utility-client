package dev.cubeutilityclient.mixin;

import net.minecraft.CrashReport;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A faster start. Before anything else Minecraft fills in a practice crash report, so the code
 * for a real one is loaded while memory is still free. That report asks Windows about the
 * hardware and the game's own process, which takes one to three seconds on some PCs. It now runs
 * on its own thread while the game carries on starting.
 */
@Mixin(Main.class)
public abstract class MainMixin {
    @Redirect(method = "main", at = @At(value = "INVOKE", target = "Lnet/minecraft/CrashReport;preload()V"))
    private static void cubeutilityclient$preloadAlongside() {
        Thread preload = new Thread(CrashReport::preload, "Crash report preload");
        preload.setDaemon(true);
        preload.start();
    }
}
