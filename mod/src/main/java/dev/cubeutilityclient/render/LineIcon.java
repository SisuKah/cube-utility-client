package dev.cubeutilityclient.render;

/**
 * The title screen's line icons, kept as shapes instead of images. Each is a centre line on a
 * 64-unit grid, drawn by {@link Paint#icon} at the exact pixel size it shows at with a stroke 5
 * units wide, so it stays sharp from a small window up to fullscreen. Shrunken images of the same
 * icons turned to mush below 20 px.
 */
public enum LineIcon {
    /** The arrow on Play CubeCraft. */
    ARROW {
        @Override
        float distance(float x, float y) {
            return Math.min(segment(x, y, 18, 46, 46, 18), Math.min(segment(x, y, 22, 18, 46, 18), segment(x, y, 46, 18, 46, 42)));
        }
    },
    /** Mods: three blocks and a fourth one turned aside, the usual sign for add-ons. */
    MODS {
        @Override
        float distance(float x, float y) {
            float blocks = Math.min(Math.abs(box(x, y, 19, 25, 9, 9, 2.5f)), Math.min(Math.abs(box(x, y, 19, 45, 9, 9, 2.5f)), Math.abs(box(x, y, 39, 45, 9, 9, 2.5f))));
            // The fourth block, turned 45 degrees about its centre.
            float dx = x - 42, dy = y - 22;
            float turned = Math.abs(box(DIAGONAL * (dx + dy) + 42, DIAGONAL * (dy - dx) + 22, 42, 22, 8, 8, 2.5f));
            return Math.min(blocks, turned);
        }
    },
    /**
     * Settings: a gear, as a ring with six solid teeth and a solid hub. Outlined teeth blur into
     * a ring at 16 px; solid ones stay teeth.
     */
    SETTINGS {
        @Override
        float distance(float x, float y) {
            float dx = x - 32, dy = y - 32;
            float gear = Math.abs(length(dx, dy) - 14);
            for (int i = 0; i < 6; i++) {
                // Each tooth is the top one, turned into place.
                float tx = COS[i] * dx + SIN[i] * dy + 32, ty = COS[i] * dy - SIN[i] * dx + 32;
                gear = Math.min(gear, solid(box(tx, ty, 32, 11, 5, 6, 2)));
            }
            return Math.min(gear, solid(length(dx, dy) - 4.5f));
        }
    },
    /** Quit: the power symbol, a ring open at the top with a bar through the gap. */
    QUIT {
        @Override
        float distance(float x, float y) {
            float dx = x - 32, dy = 33 - y;
            float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
            float ring = angle > 52f && angle < 128f
                    ? Math.min(length(x - GAP_RIGHT_X, y - GAP_Y), length(x - GAP_LEFT_X, y - GAP_Y))
                    : Math.abs(length(dx, dy) - 20);
            return Math.min(ring, segment(x, y, 32, 9, 32, 31));
        }
    };

    /** Half the stroke width, in grid units. */
    private static final float HALF_STROKE = 2.5f;
    private static final float DIAGONAL = (float) Math.sqrt(0.5);
    private static final float[] COS = new float[6], SIN = new float[6];
    /** Where the power ring's gap starts and ends (at 52 and 128 degrees, radius 20). */
    private static final float GAP_RIGHT_X = 32 + 20 * (float) Math.cos(Math.toRadians(52));
    private static final float GAP_LEFT_X = 32 + 20 * (float) Math.cos(Math.toRadians(128));
    private static final float GAP_Y = 33 - 20 * (float) Math.sin(Math.toRadians(52));

    static {
        for (int i = 0; i < 6; i++) {
            COS[i] = (float) Math.cos(Math.PI / 3 * i);
            SIN[i] = (float) Math.sin(Math.PI / 3 * i);
        }
    }

    /** The distance from (x, y) to the icon's centre line, in grid units. */
    abstract float distance(float x, float y);

    /**
     * How much of the screen pixel centred on (px, py) the icon covers, from 0 to 1, when its
     * 64-unit grid fills the square at (x, y) that is {@code size} pixels wide.
     */
    public float coverage(float px, float py, float x, float y, float size) {
        float unit = size / 64f;
        float distance = this.distance((px - x) / unit, (py - y) / unit) * unit;
        float halfStroke = Math.max(0.55f, HALF_STROKE * unit);
        float cover = 0.5f + halfStroke - distance;
        return cover <= 0f ? 0f : Math.min(1f, cover);
    }

    /** A filled shape, given its signed distance, as a distance to a centre line whose stroke fills it. */
    private static float solid(float signedDistance) {
        return Math.max(0f, signedDistance + HALF_STROKE);
    }

    private static float length(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    private static float segment(float px, float py, float ax, float ay, float bx, float by) {
        float pax = px - ax, pay = py - ay, bax = bx - ax, bay = by - ay;
        float h = Math.max(0f, Math.min(1f, (pax * bax + pay * bay) / (bax * bax + bay * bay)));
        return length(pax - bax * h, pay - bay * h);
    }

    /** Signed: negative inside. */
    private static float circle(float px, float py, float cx, float cy, float radius) {
        return length(px - cx, py - cy) - radius;
    }

    /** Signed distance to a box centred on (cx, cy) with half sizes hw and hh and rounded corners. */
    private static float box(float px, float py, float cx, float cy, float hw, float hh, float corner) {
        float qx = Math.abs(px - cx) - (hw - corner), qy = Math.abs(py - cy) - (hh - corner);
        return length(Math.max(qx, 0f), Math.max(qy, 0f)) + Math.min(Math.max(qx, qy), 0f) - corner;
    }
}
