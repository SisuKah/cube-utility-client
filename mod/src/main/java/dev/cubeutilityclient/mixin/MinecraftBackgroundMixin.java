package dev.cubeutilityclient.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cubeutilityclient.background.BackgroundStart;
import net.minecraft.client.FramerateLimiter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A loaded game waiting, hidden, for PLAY draws nothing: nobody can see its window, and the space
 * title screen keeps a core busy even at 15 frames a second (Dynamic FPS's floor for a window
 * without focus, which a frame-rate limit can't go under). The game loop still runs a few times
 * a second, so PLAY's command, the game's ticks and the window's events go on as usual. Dynamic
 * FPS skips frames for a minimized window the same way.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftBackgroundMixin {
    @WrapMethod(method = "renderFrame")
    private void cubeutilityclient$drawNothingWhileWaiting(boolean advanceGameTime, Operation<Void> original) {
        if (BackgroundStart.waitingHidden()) {
            FramerateLimiter.limitDisplayFPS(BackgroundStart.WAITING_LOOPS_PER_SECOND);
        } else {
            original.call(advanceGameTime);
        }
    }
}
