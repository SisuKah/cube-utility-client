package dev.cubeutilityclient.render;

/** ARGB colour helpers. */
public final class Rgb {
    private Rgb() {
    }

    public static int argb(float alpha, int red, int green, int blue) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return a << 24 | (red & 255) << 16 | (green & 255) << 8 | blue & 255;
    }

    /** A 0xRRGGBB colour at the given opacity. */
    public static int alpha(int rgb, float alpha) {
        return argb(alpha, rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255);
    }

    public static float alphaOf(int argb) {
        return (argb >>> 24) / 255f;
    }

    /** Mixes two ARGB colours, alpha included. */
    public static int lerp(int from, int to, float k) {
        k = Math.max(0f, Math.min(1f, k));
        int a = Math.round((from >>> 24) + ((to >>> 24) - (from >>> 24)) * k);
        int r = Math.round((from >> 16 & 255) + ((to >> 16 & 255) - (from >> 16 & 255)) * k);
        int g = Math.round((from >> 8 & 255) + ((to >> 8 & 255) - (from >> 8 & 255)) * k);
        int b = Math.round((from & 255) + ((to & 255) - (from & 255)) * k);
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** The colour with its alpha multiplied by k. */
    public static int fade(int argb, float k) {
        int a = Math.round((argb >>> 24) * Math.max(0f, Math.min(1f, k)));
        return a << 24 | argb & 0xFFFFFF;
    }
}
