package dev.cubeutilityclient.render;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.cubeutilityclient.CubeUtilityClient;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * GUI pipelines that add light instead of covering what is behind: overlapping stars and glows
 * brighten each other, as they do in the night sky.
 */
public final class Pipelines {
    /** Coloured quads, added (source alpha, one). */
    public static final RenderPipeline GLOW = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                    .withLocation(CubeUtilityClient.id("pipeline/gui_glow"))
                    .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
                    .build());

    /** Textured quads, added (source alpha, one). */
    public static final RenderPipeline GLOW_TEXTURED = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(CubeUtilityClient.id("pipeline/gui_glow_textured"))
                    .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
                    .build());

    private Pipelines() {
    }

    /** Registers the pipelines before the first frame. */
    public static void init() {
    }
}
