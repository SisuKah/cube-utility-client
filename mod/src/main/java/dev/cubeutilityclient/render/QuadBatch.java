package dev.cubeutilityclient.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Arrays;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/**
 * Many quads in one GUI element, at sub-pixel positions and with a colour per corner: thousands
 * of stars cost one element. Quads are added after the element was submitted; Minecraft builds
 * the vertices once the frame is complete.
 */
public final class QuadBatch implements GuiElementRenderState {
    private final RenderPipeline pipeline;
    private final TextureSetup textureSetup;
    private final Matrix3x2f pose;
    private final ScreenRectangle bounds;
    private final boolean textured;
    private float[] xy = new float[512];
    private float[] uv;
    private int[] colors = new int[256];
    private int vertices;

    QuadBatch(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2fc pose, ScreenRectangle bounds, boolean textured) {
        this.pipeline = pipeline;
        this.textureSetup = textureSetup;
        this.pose = new Matrix3x2f(pose);
        this.bounds = bounds;
        this.textured = textured;
        this.uv = textured ? new float[512] : null;
    }

    private void vertex(float x, float y, float u, float v, int color) {
        if (this.vertices == this.colors.length) {
            int size = this.colors.length * 2;
            this.colors = Arrays.copyOf(this.colors, size);
            this.xy = Arrays.copyOf(this.xy, size * 2);
            if (this.textured) {
                this.uv = Arrays.copyOf(this.uv, size * 2);
            }
        }
        int i = this.vertices++;
        this.xy[i * 2] = x;
        this.xy[i * 2 + 1] = y;
        if (this.textured) {
            this.uv[i * 2] = u;
            this.uv[i * 2 + 1] = v;
        }
        this.colors[i] = color;
    }

    /** A rectangle with the whole texture; left and right colours make a horizontal gradient. */
    public void rect(float x0, float y0, float x1, float y1, int left, int right) {
        this.vertex(x0, y0, 0, 0, left);
        this.vertex(x0, y1, 0, 1, left);
        this.vertex(x1, y1, 1, 1, right);
        this.vertex(x1, y0, 1, 0, right);
    }

    public void rect(float x0, float y0, float x1, float y1, int color) {
        this.rect(x0, y0, x1, y1, color, color);
    }

    /** A free quad. Corners in order: top-left, bottom-left, bottom-right and top-right of the texture. */
    public void quad(float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy, int ca, int cb, int cc, int cd) {
        this.vertex(ax, ay, 0, 0, ca);
        this.vertex(bx, by, 0, 1, cb);
        this.vertex(cx, cy, 1, 1, cc);
        this.vertex(dx, dy, 1, 0, cd);
    }

    @Override
    public void buildVertices(final VertexConsumer consumer) {
        for (int i = 0; i < this.vertices; i++) {
            VertexConsumer vertex = consumer.addVertexWith2DPose(this.pose, this.xy[i * 2], this.xy[i * 2 + 1]);
            if (this.textured) {
                vertex = vertex.setUv(this.uv[i * 2], this.uv[i * 2 + 1]);
            }
            vertex.setColor(this.colors[i]);
        }
    }

    @Override
    public RenderPipeline pipeline() {
        return this.pipeline;
    }

    @Override
    public TextureSetup textureSetup() {
        return this.textureSetup;
    }

    @Override
    public ScreenRectangle scissorArea() {
        return null;
    }

    @Override
    public ScreenRectangle bounds() {
        return this.bounds;
    }
}
