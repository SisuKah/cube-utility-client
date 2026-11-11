package dev.cubeutilityclient.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;

/**
 * Draws in real screen pixels instead of Minecraft's GUI units, so hairlines stay one pixel wide
 * and text lands exactly on pixels at every GUI scale.
 */
public final class Paint {
    public final GuiGraphicsExtractor graphics;
    public final Minecraft minecraft;
    public final float guiScale;
    /** The screen size in pixels. */
    public final float width, height;
    private final Matrix3x2f pixelPose;
    private final ScreenRectangle bounds;

    public Paint(GuiGraphicsExtractor graphics) {
        this.graphics = graphics;
        this.minecraft = Minecraft.getInstance();
        this.guiScale = this.minecraft.getWindow().getGuiScale();
        this.width = graphics.guiWidth() * this.guiScale;
        this.height = graphics.guiHeight() * this.guiScale;
        this.pixelPose = new Matrix3x2f(graphics.pose()).scale(1f / this.guiScale, 1f / this.guiScale);
        this.bounds = new ScreenRectangle(0, 0, graphics.guiWidth(), graphics.guiHeight());
    }

    /** Coloured quads, blended normally. */
    public QuadBatch solid() {
        return this.submit(RenderPipelines.GUI, TextureSetup.noTexture(), false);
    }

    /** Coloured quads that add light. */
    public QuadBatch glow() {
        return this.submit(Pipelines.GLOW, TextureSetup.noTexture(), false);
    }

    /** Quads showing a texture, blended normally or added as light. */
    public QuadBatch texture(Identifier location, boolean additive) {
        SpaceTextures.ensureRegistered();
        AbstractTexture texture = this.minecraft.getTextureManager().getTexture(location);
        TextureSetup setup = TextureSetup.singleTexture(texture.getTextureView(), texture.getSampler());
        return this.submit(additive ? Pipelines.GLOW_TEXTURED : RenderPipelines.GUI_TEXTURED, setup, true);
    }

    private QuadBatch submit(RenderPipeline pipeline, TextureSetup setup, boolean textured) {
        QuadBatch batch = new QuadBatch(pipeline, setup, this.pixelPose, this.bounds, textured);
        this.graphics.guiRenderState.addGuiElement(batch);
        return batch;
    }

    // ------------------------------------------------------------------ text

    private static Component styled(String text, FontDescription font) {
        return Component.literal(text).withStyle(style -> style.withFont(font));
    }

    /** A letter's exact advance; Font.width rounds it, which makes spaced capitals uneven. */
    private static float advance(Component text) {
        return Minecraft.getInstance().font.getSplitter().stringWidth(text);
    }

    /** The width in pixels of text drawn with {@link #text}. */
    public static float measure(String text, FontDescription font, float spacing) {
        if (spacing == 0f) {
            return advance(styled(text, font));
        }
        // Whole-pixel steps, as text() takes them, so the same letters always sit the same distance apart.
        int step = Math.max(1, Math.round(spacing));
        float width = 0f;
        int letters = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            width += Math.round(advance(styled(new String(Character.toChars(codePoint)), font)));
            letters++;
            i += Character.charCount(codePoint);
        }
        return width + step * Math.max(0, letters - 1);
    }

    /** Text whose baseline sits at {@code baseline}, with {@code spacing} pixels after each letter. */
    public void text(String text, FontDescription font, float x, float baseline, int color, float spacing) {
        if (Rgb.alphaOf(color) < 0.02f) {
            return;
        }
        Matrix3x2fStack pose = this.graphics.pose();
        pose.pushMatrix();
        pose.scale(1f / this.guiScale, 1f / this.guiScale);
        // Glyphs hang from a baseline 7 units below the position they are drawn at.
        int y = Math.round(baseline) - 7;
        if (spacing == 0f) {
            this.graphics.text(this.minecraft.font, styled(text, font), Math.round(x), y, color, false);
        } else {
            int step = Math.max(1, Math.round(spacing));
            int pen = Math.round(x);
            for (int i = 0; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                Component letter = styled(new String(Character.toChars(codePoint)), font);
                this.graphics.text(this.minecraft.font, letter, pen, y, color, false);
                pen += Math.round(advance(letter)) + step;
                i += Character.charCount(codePoint);
            }
        }
        pose.popMatrix();
    }

    // ------------------------------------------------------------------ shapes

    /** The colour of a fill at a point, for gradients. */
    @FunctionalInterface
    public interface Fill {
        int at(float x, float y);
    }

    /**
     * A pill (or a circle, when it is as wide as it is tall), anti-aliased, with an optional
     * border {@code border} pixels wide drawn just inside its edge.
     */
    public static void capsule(QuadBatch batch, float x, float y, float w, float h, float border, Fill fill, int borderColor, boolean gradient) {
        // The pill's spine runs along its longer side; its ends are half circles.
        boolean upright = h > w;
        float r = Math.min(w, h) * 0.5f;
        float cx = x + w * 0.5f, cy = y + h * 0.5f;
        float ax = x + r, bx = x + w - r, ay = y + r, by = y + h - r;
        int x0 = (int) Math.floor(x), x1 = (int) Math.ceil(x + w);
        int y0 = (int) Math.floor(y), y1 = (int) Math.ceil(y + h);
        final int none = Integer.MIN_VALUE;
        for (int py = y0; py < y1; py++) {
            float yc = py + 0.5f;
            float rowDy = upright ? (yc < ay ? ay - yc : yc > by ? yc - by : 0f) : Math.abs(yc - cy);
            int fillRun = none;
            int edgeRun = none, edgeColor = 0;
            for (int px = x0; px <= x1; px++) {
                int kind = 0, color = 0;
                if (px < x1) {
                    float xc = px + 0.5f;
                    float dx = upright ? Math.abs(xc - cx) : xc < ax ? ax - xc : xc > bx ? xc - bx : 0f;
                    float dy = rowDy;
                    float d = (float) Math.sqrt(dx * dx + dy * dy) - r;
                    float outer = clamp01(0.5f - d);
                    if (outer > 0f) {
                        float inner = border > 0f ? clamp01(0.5f - d - border) : outer;
                        if (outer >= 1f && inner >= 1f) {
                            kind = 1;
                        } else {
                            color = coverage(fill.at(xc, yc), inner, borderColor, outer - inner);
                            kind = color >>> 24 == 0 ? 0 : 2;
                        }
                    }
                }
                if (kind == 1) {
                    if (fillRun == none) {
                        fillRun = px;
                    }
                } else if (fillRun != none) {
                    fillRow(batch, fillRun, px, py, yc, fill, gradient);
                    fillRun = none;
                }
                if (kind == 2 && edgeRun != none && color == edgeColor) {
                    continue;
                }
                if (edgeRun != none) {
                    batch.rect(edgeRun, py, px, py + 1, edgeColor);
                    edgeRun = none;
                }
                if (kind == 2) {
                    edgeRun = px;
                    edgeColor = color;
                }
            }
        }
    }

    /** A line icon at its exact size: (x, y) is its top-left corner and {@code size} its width in pixels. */
    public static void icon(QuadBatch batch, LineIcon icon, float x, float y, float size, int color) {
        int x0 = (int) Math.floor(x), x1 = (int) Math.ceil(x + size);
        int y0 = (int) Math.floor(y), y1 = (int) Math.ceil(y + size);
        final int none = Integer.MIN_VALUE;
        for (int py = y0; py < y1; py++) {
            // Neighbouring pixels of the same colour become one quad.
            int run = none, runColor = 0;
            for (int px = x0; px <= x1; px++) {
                int pixel = 0;
                if (px < x1) {
                    float cover = icon.coverage(px + 0.5f, py + 0.5f, x, y, size);
                    pixel = cover > 0.004f ? Rgb.fade(color, cover) : 0;
                }
                if (run != none && pixel == runColor) {
                    continue;
                }
                if (run != none && runColor >>> 24 != 0) {
                    batch.rect(run, py, px, py + 1, runColor);
                }
                run = px;
                runColor = pixel;
            }
        }
    }

    private static void fillRow(QuadBatch batch, int from, int to, int py, float yc, Fill fill, boolean gradient) {
        if (!gradient) {
            batch.rect(from, py, to, py + 1, fill.at(from + 0.5f, yc));
            return;
        }
        for (int start = from; start < to; start += 24) {
            int end = Math.min(to, start + 24);
            batch.rect(start, py, end, py + 1, fill.at(start, yc), fill.at(end, yc));
        }
    }

    /** One pixel partly covered by the fill and partly by the border. */
    private static int coverage(int fill, float fillCover, int border, float borderCover) {
        float af = Rgb.alphaOf(fill) * fillCover;
        float ab = Rgb.alphaOf(border) * borderCover;
        float a = af + ab;
        if (a <= 0.002f) {
            return 0;
        }
        int r = Math.round(((fill >> 16 & 255) * af + (border >> 16 & 255) * ab) / a);
        int g = Math.round(((fill >> 8 & 255) * af + (border >> 8 & 255) * ab) / a);
        int b = Math.round(((fill & 255) * af + (border & 255) * ab) / a);
        return Rgb.argb(Math.min(1f, a), r, g, b);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
