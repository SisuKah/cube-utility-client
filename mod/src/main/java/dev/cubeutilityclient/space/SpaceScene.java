package dev.cubeutilityclient.space;

import dev.cubeutilityclient.CubeUtilityClient;
import dev.cubeutilityclient.render.Paint;
import dev.cubeutilityclient.render.QuadBatch;
import dev.cubeutilityclient.render.Rgb;
import java.util.Random;
import net.minecraft.resources.Identifier;

/**
 * The menus' background: a spiral galaxy turning slowly to the right of the title, layers of
 * twinkling stars that drift and follow the mouse a little, soft teal nebula haze and now and then
 * a shooting star. One scene is shared by every menu, so it keeps turning across screens.
 *
 * Positions are in a 1280 x 720 design space (the launcher's mockup), scaled to the window.
 */
public final class SpaceScene {
    private static final Identifier GLOW = CubeUtilityClient.id("textures/gui/glow.png");
    private static final Identifier HAZE = CubeUtilityClient.id("textures/gui/haze.png");
    private static final Identifier CORE = CubeUtilityClient.id("textures/gui/core.png");
    private static final Identifier DUST = CubeUtilityClient.id("textures/gui/dust.png");
    private static final Identifier VIGNETTE = CubeUtilityClient.id("textures/gui/vignette.png");

    private static final SpaceScene INSTANCE = new SpaceScene();
    private static final long START = System.nanoTime();

    // Background stars: position in design space, size, brightness, twinkle speed and phase.
    private static final int STARS = 480;
    private static final float[] DRIFT = {2f, 5f, 10f};
    private static final float[] DEPTH = {5f, 11f, 20f};
    private final float[] starX = new float[STARS], starY = new float[STARS], starSize = new float[STARS];
    private final float[] starAlpha = new float[STARS], starSpeed = new float[STARS], starPhase = new float[STARS];
    private final int[] starLayer = new int[STARS], starColor = new int[STARS];

    // The galaxy, seen at an angle: radius, angle, height above its plane, colour and brightness.
    private static final float GALAXY_SIZE = 340f, INCLINATION = 0.42f, POSITION_ANGLE = 0.38f, PITCH = 0.27f;
    private static final float SPIN = (float) (Math.PI * 2 / 240);
    private final int particles;
    private final float[] partRadius, partAngle, partHeight, partAlpha, partSize;
    private final int[] partColor;
    private final boolean[] partKnot;

    private final Random random = new Random(31415);
    private float lastTime, parallaxX, parallaxY;
    private float shootX, shootY, shootVx, shootVy, shootLife = -1f, nextShoot = 3f;

    public static SpaceScene get() {
        return INSTANCE;
    }

    private SpaceScene() {
        int[] warm = {246, 228, 190}, teal = {170, 232, 226}, white = {230, 238, 242};
        for (int i = 0; i < STARS; i++) {
            int layer = i < 320 ? 0 : i < 450 ? 1 : 2;
            this.starLayer[i] = layer;
            this.starX[i] = this.random.nextFloat() * 1280f;
            this.starY[i] = this.random.nextFloat() * 720f;
            this.starSize[i] = new float[] {0.55f, 0.9f, 1.35f}[layer] * (0.7f + this.random.nextFloat() * 0.6f);
            this.starAlpha[i] = 0.3f + this.random.nextFloat() * 0.7f;
            this.starSpeed[i] = 0.5f + this.random.nextFloat() * 2.2f;
            this.starPhase[i] = this.random.nextFloat() * 6.2832f;
            float pick = this.random.nextFloat();
            this.starColor[i] = rgb(pick < 0.1f ? warm : pick < 0.2f ? teal : white);
        }

        int bulge = 1000, arms = 4400, diffuse = 1300;
        this.particles = bulge + arms + diffuse;
        this.partRadius = new float[this.particles];
        this.partAngle = new float[this.particles];
        this.partHeight = new float[this.particles];
        this.partAlpha = new float[this.particles];
        this.partSize = new float[this.particles];
        this.partColor = new int[this.particles];
        this.partKnot = new boolean[this.particles];
        int[] core = {255, 236, 204}, whiteish = {236, 240, 244}, tealish = {150, 222, 216}, gold = {240, 198, 128};
        int n = 0;
        for (int i = 0; i < bulge; i++, n++) {
            float r = (float) Math.abs(this.random.nextGaussian()) * 30f + this.random.nextFloat() * 8f;
            this.partRadius[n] = r;
            this.partAngle[n] = this.random.nextFloat() * 6.2832f;
            this.partHeight[n] = (float) this.random.nextGaussian() * 14f * (float) Math.exp(-r / 50f);
            this.partColor[n] = tone(core, whiteish, Math.min(1f, r / 80f) * 0.4f);
            this.partAlpha[n] = 0.22f + this.random.nextFloat() * 0.5f;
            this.partSize[n] = 0.9f + this.random.nextFloat() * 1.1f;
        }
        for (int i = 0; i < arms; i++, n++) {
            int arm = i % 2;
            float tt = (float) Math.pow(this.random.nextFloat(), 0.85);
            float r = Math.max(24f, 30f + tt * (GALAXY_SIZE - 30f) + (float) this.random.nextGaussian() * 5f);
            float spread = (float) this.random.nextGaussian() * (0.36f - 0.18f * tt);
            this.partRadius[n] = r;
            this.partAngle[n] = arm * (float) Math.PI + (float) (Math.log(r / 30f) / Math.tan(PITCH)) + spread;
            this.partHeight[n] = (float) this.random.nextGaussian() * (2f + 4f * tt);
            boolean knot = this.random.nextFloat() < 0.035f;
            this.partKnot[n] = knot;
            if (knot) {
                this.partColor[n] = this.random.nextFloat() < 0.5f ? rgb(gold) : tone(tealish, whiteish, 0.3f);
            } else if (tt < 0.35f) {
                this.partColor[n] = tone(core, whiteish, tt / 0.35f);
            } else {
                this.partColor[n] = tone(whiteish, tealish, (tt - 0.35f) / 0.65f);
            }
            this.partAlpha[n] = (knot ? 0.85f : 0.18f + this.random.nextFloat() * 0.55f) * (1f - tt * 0.4f);
            this.partSize[n] = knot ? 1.5f + this.random.nextFloat() * 1.2f : 0.8f + this.random.nextFloat();
        }
        for (int i = 0; i < diffuse; i++, n++) {
            float r = (float) Math.pow(this.random.nextFloat(), 0.55) * GALAXY_SIZE * 1.05f;
            this.partRadius[n] = r;
            this.partAngle[n] = this.random.nextFloat() * 6.2832f;
            this.partHeight[n] = (float) this.random.nextGaussian() * 4f;
            this.partColor[n] = tone(whiteish, tealish, Math.min(1f, r / GALAXY_SIZE));
            this.partAlpha[n] = 0.06f + this.random.nextFloat() * 0.14f;
            this.partSize[n] = 0.8f + this.random.nextFloat() * 0.6f;
        }
    }

    private static int rgb(int[] c) {
        return c[0] << 16 | c[1] << 8 | c[2];
    }

    private static int tone(int[] a, int[] b, float k) {
        return Math.round(a[0] + (b[0] - a[0]) * k) << 16 | Math.round(a[1] + (b[1] - a[1]) * k) << 8 | Math.round(a[2] + (b[2] - a[2]) * k);
    }

    /**
     * Draws the scene over the whole screen. {@code mouseX} and {@code mouseY} run from -1 to 1;
     * the scene leans a little away from the mouse.
     */
    public void draw(Paint paint, float mouseX, float mouseY) {
        float t = (System.nanoTime() - START) / 1e9f;
        float dt = Math.min(0.05f, Math.max(0f, t - this.lastTime));
        this.lastTime = t;
        float ease = 1f - (float) Math.pow(1f - 0.045f, dt * 60f);
        this.parallaxX += (mouseX - this.parallaxX) * ease;
        this.parallaxY += (mouseY - this.parallaxY) * ease;
        float ex = this.parallaxX, ey = this.parallaxY;

        float w = paint.width, h = paint.height;
        float s = Math.min(w / 1280f, h / 720f);
        float sx = w / 1280f, sy = h / 720f, cover = Math.max(sx, sy);
        float oy = (h - 720f * s) / 2f;

        // The sky: near-black, lighter towards the galaxy, with faint teal and gold haze.
        paint.solid().rect(0, 0, w, h, 0xFF020304);
        QuadBatch haze = paint.texture(HAZE, false);
        float hx = -ex * 8f * s, hy = -ey * 5f * s;
        blob(haze, 1060f * sx + hx, 300f * sy + hy, 1150f * cover, Rgb.alpha(0x0B171C, 1f));
        blob(haze, 160f * sx + hx, 120f * sy + hy, 460f * cover, Rgb.alpha(0x2F9C95, 0.08f));
        blob(haze, 1080f * sx + hx, 320f * sy + hy, 560f * cover, Rgb.alpha(0x46C2B8, 0.05f));
        blob(haze, 1200f * sx + hx, 690f * sy + hy, 440f * cover, Rgb.alpha(0x1E606C, 0.10f));
        blob(haze, 460f * sx + hx, 700f * sy + hy, 360f * cover, Rgb.alpha(0xE4C084, 0.03f));

        // Background stars, as soft dots so they glide between pixels instead of jumping.
        QuadBatch dots = paint.texture(GLOW, true);
        for (int i = 0; i < STARS; i++) {
            int layer = this.starLayer[i];
            float x = (this.starX[i] - t * DRIFT[layer] - ex * DEPTH[layer]) % 1280f;
            if (x < 0f) {
                x += 1280f;
            }
            float px = x * sx, py = (this.starY[i] - ey * DEPTH[layer] * 0.6f) * sy;
            float alpha = this.starAlpha[i] * (0.55f + 0.45f * (float) Math.sin(t * this.starSpeed[i] + this.starPhase[i]));
            float half = Math.max(1.1f, this.starSize[i] * s * 1.7f);
            dots.rect(px - half, py - half, px + half, py + half, Rgb.alpha(this.starColor[i], Math.min(1f, alpha * 1.5f)));
            if (layer == 2) {
                float g = 7f * s;
                dots.rect(px - g, py - g, px + g, py + g, Rgb.alpha(0xDCF4F0, alpha * 0.45f));
            }
        }

        // The galaxy, at the right of the screen.
        float gx = w - 250f * s - ex * 14f * s, gy = oy + 290f * s - ey * 8f * s;
        float cpa = (float) Math.cos(POSITION_ANGLE), spa = (float) Math.sin(POSITION_ANGLE);
        tilted(paint.texture(HAZE, true), gx, gy, GALAXY_SIZE * 1.15f * s, cpa, spa, Rgb.alpha(0xDCE8E4, 0.20f));
        float core = 120f * s;
        paint.texture(CORE, true).rect(gx - core, gy - core, gx + core, gy + core, Rgb.alpha(0xFFF6E2, 0.95f));
        float turn = t * SPIN;
        for (int i = 0; i < this.particles; i++) {
            float r = this.partRadius[i];
            float angle = this.partAngle[i] + turn * (r < 60f ? 1.15f : 1f);
            float lx = r * (float) Math.cos(angle);
            float ly = r * (float) Math.sin(angle) * INCLINATION + this.partHeight[i] * 0.9f;
            float px = gx + (lx * cpa - ly * spa) * s;
            float py = gy + (lx * spa + ly * cpa) * s;
            float alpha = this.partAlpha[i] * (0.85f + 0.15f * (float) Math.sin(t * 1.7f + this.partAngle[i] * 5f));
            float half = Math.max(1.0f, this.partSize[i] * s * 1.5f);
            dots.rect(px - half, py - half, px + half, py + half, Rgb.alpha(this.partColor[i], Math.min(1f, alpha * 1.35f)));
            if (this.partKnot[i]) {
                float g = 6f * s;
                dots.rect(px - g, py - g, px + g, py + g, Rgb.alpha(this.partColor[i], alpha * 0.5f));
            }
        }
        // The dark dust lane across the near side of the core.
        tilted(paint.texture(DUST, false), gx, gy, 128f * s, cpa, spa, 0xFFFFFFFF);

        this.shootingStar(paint, t, dt, s, sx, sy);

        paint.texture(VIGNETTE, false).rect(0, 0, w, h, 0xFFFFFFFF);
    }

    /** A soft round blob of colour. */
    private static void blob(QuadBatch batch, float x, float y, float radius, int color) {
        batch.rect(x - radius, y - radius, x + radius, y + radius, color);
    }

    /** A square texture laid in the galaxy's plane: squashed by its tilt, then turned. */
    private static void tilted(QuadBatch batch, float cx, float cy, float half, float cos, float sin, int color) {
        float[] lx = {-half, -half, half, half};
        float[] ly = {-half * INCLINATION, half * INCLINATION, half * INCLINATION, -half * INCLINATION};
        float[] px = new float[4], py = new float[4];
        for (int k = 0; k < 4; k++) {
            px[k] = cx + lx[k] * cos - ly[k] * sin;
            py[k] = cy + lx[k] * sin + ly[k] * cos;
        }
        batch.quad(px[0], py[0], px[1], py[1], px[2], py[2], px[3], py[3], color, color, color, color);
    }

    /** Every few seconds a streak crosses the upper sky. */
    private void shootingStar(Paint paint, float t, float dt, float s, float sx, float sy) {
        if (this.shootLife < 0f && t > this.nextShoot) {
            this.shootX = 260f + this.random.nextFloat() * 600f;
            this.shootY = -10f + this.random.nextFloat() * 150f;
            this.shootVx = -620f - this.random.nextFloat() * 260f;
            this.shootVy = 240f + this.random.nextFloat() * 120f;
            this.shootLife = 0f;
        }
        if (this.shootLife < 0f) {
            return;
        }
        this.shootLife += dt;
        this.shootX += this.shootVx * dt;
        this.shootY += this.shootVy * dt;
        float max = 0.85f;
        float fade = (float) Math.sin(Math.min(1f, this.shootLife / max) * Math.PI);
        float headX = this.shootX * sx, headY = this.shootY * sy;
        float tailX = headX - this.shootVx * 0.2f * s, tailY = headY - this.shootVy * 0.2f * s;
        float length = (float) Math.hypot(headX - tailX, headY - tailY);
        if (length > 0.5f) {
            float nx = -(headY - tailY) / length * 0.75f * s, ny = (headX - tailX) / length * 0.75f * s;
            int head = Rgb.alpha(0xFFF8E8, 0.85f * fade), tail = Rgb.alpha(0xFFF8E8, 0f);
            paint.glow().quad(headX + nx, headY + ny, headX - nx, headY - ny, tailX - nx, tailY - ny, tailX + nx, tailY + ny, head, head, tail, tail);
        }
        if (this.shootLife > max) {
            this.shootLife = -1f;
            this.nextShoot = t + 4f + this.random.nextFloat() * 6f;
        }
    }
}
