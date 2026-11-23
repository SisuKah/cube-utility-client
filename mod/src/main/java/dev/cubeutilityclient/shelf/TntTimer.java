package dev.cubeutilityclient.shelf;

import java.util.Locale;
import net.minecraft.client.renderer.entity.state.TntRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.item.PrimedTnt;

/**
 * The client's TNT Timer: the seconds left on lit TNT, drawn above it like a name tag, green
 * turning to red as it runs out. Minecraft draws the name tag itself, so this only fills one in.
 */
public final class TntTimer {
    /** Like name tags: TNT further away than this shows no time. */
    private static final double MAX_DISTANCE_SQ = 32.0 * 32.0;

    private TntTimer() {
    }

    public static void label(PrimedTnt tnt, TntRenderState state, float partialTick) {
        if (state.distanceToCameraSq > MAX_DISTANCE_SQ || state.isInvisible) {
            return;
        }
        float seconds = Math.max(0f, state.fuseRemainingInTicks / 20f);
        state.nameTag = Component.literal(String.format(Locale.ROOT, "%.1f", seconds)).withColor(color(seconds));
        state.nameTagAttachment = tnt.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, tnt.getYRot(partialTick));
    }

    /** Green with time to spare, yellow at two seconds, red in the last one. */
    private static int color(float seconds) {
        if (seconds >= 2f) {
            return 0x7CE38B;
        }
        if (seconds >= 1f) {
            return 0xF4D35E;
        }
        return 0xFF6B5E;
    }
}
