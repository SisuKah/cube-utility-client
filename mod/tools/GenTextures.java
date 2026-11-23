import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Draws the mod's GUI textures: soft glows for stars and the galaxy core, the dust lane, the
 * vignette, the title shade and Minecraft's menu backgrounds. (The icons are drawn in code, by
 * LineIcon.) Run from mod/:
 *
 *   java tools/GenTextures.java src/main/resources/assets/cubeutilityclient/textures/gui
 */
public final class GenTextures {
    public static void main(String[] args) throws IOException {
        File out = new File(args.length > 0 ? args[0] : "src/main/resources/assets/cubeutilityclient/textures/gui");
        out.mkdirs();
        radial(out, "glow.png", 64, r -> Math.pow(Math.max(0, 1 - r), 2.4), 255, 255, 255);
        radial(out, "haze.png", 256, r -> Math.exp(-r * r * 4.5) * (1 - smooth(0.85, 1, r)), 255, 255, 255);
        radial(out, "core.png", 256, r -> Math.exp(-r * 9) * 0.9 + Math.exp(-r * r * 6) * 0.35, 255, 244, 224);
        radial(out, "vignette.png", 256, r -> smooth(0.45, 1.25, r) * 0.62, 0, 0, 0);
        shade(out);
        dust(out);
        menus(new File(out, "../../../minecraft/textures/gui"));
        badges(new File(out, "../font"), new File(out, "../../font"));
    }

    /**
     * The tab marker (TabMarker): the launcher's mark (src/components/Logo.tsx), a gold outline
     * cube with a sparkle at its top right, one picture and font per GUI scale. The tab list draws
     * text in GUI units without smoothing, so the picture for scale s is 8s pixels square and drawn
     * 8 units tall: one pixel per screen pixel. The mark is as big as fits, at a size that puts its
     * three upright edges on whole pixels, so its lines stay sharp at every scale.
     */
    static void badges(File textures, File fonts) throws IOException {
        textures.mkdirs();
        // Logo.tsx's 40-unit grid: the outline, the Y inside it, and the sparkle.
        double[][] lines = {
            {18, 6.5, 30.5, 13.7}, {30.5, 13.7, 30.5, 28.3}, {30.5, 28.3, 18, 35.5},
            {18, 35.5, 5.5, 28.3}, {5.5, 28.3, 5.5, 13.7}, {5.5, 13.7, 18, 6.5},
            {5.5, 13.7, 18, 21}, {18, 21, 30.5, 13.7}, {18, 21, 18, 35.5},
        };
        double[][] sparkle = {{33.5, 2.5}, {34.7, 5.6}, {37.8, 6.8}, {34.7, 8.0}, {33.5, 11.1}, {32.3, 8.0}, {29.2, 6.8}, {32.3, 5.6}};
        // A step brighter than Logo.tsx's #CDA76A and #E4C084: the tab list's backdrop is the
        // game world seen through a dark wash, not the launcher's solid dark, and the lines are thin.
        int strokeColor = 0xE2BF80, sparkleColor = 0xF5DEAD;
        for (int s = 1; s <= 8; s++) {
            int cell = 8 * s;
            // Pixels between two upright edges (12.5 units apart): the most that fits. The mark is
            // 33 units tall from the sparkle's tip to the cube's foot, and 32.3 wide.
            int gap = 2, stroke = 1;
            for (int n = 2; ; n++) {
                double k = n / 12.5;
                int w = Math.max(1, (int) Math.round(1.6 * k));
                if (33 * k + w / 2.0 > cell + 0.4 || 32.3 * k + w / 2.0 > cell + 0.4) {
                    break;
                }
                gap = n;
                stroke = w;
            }
            double k = gap / 12.5, half = stroke / 2.0;
            // An odd stroke is centred on pixel centres, an even one on pixel edges.
            double x0 = half - 5.5 * k;
            // Centred in the row; a mark a little too tall loses the sparkle's tip, never the cube's foot.
            double height = 33 * k + half;
            double top = height > cell ? cell - height : (cell - height) / 2;
            double y0 = top - 2.5 * k;
            double[][] px = new double[lines.length][];
            for (int i = 0; i < lines.length; i++) {
                double[] l = lines[i];
                px[i] = new double[] {x0 + l[0] * k, y0 + l[1] * k, x0 + l[2] * k, y0 + l[3] * k};
            }
            double[][] star = new double[sparkle.length][];
            for (int i = 0; i < sparkle.length; i++) {
                star[i] = new double[] {x0 + sparkle[i][0] * k, y0 + sparkle[i][1] * k};
            }
            BufferedImage img = new BufferedImage(cell, cell, BufferedImage.TYPE_INT_ARGB);
            int samples = 16;
            for (int y = 0; y < cell; y++) {
                for (int x = 0; x < cell; x++) {
                    int lineHits = 0, sparkHits = 0;
                    for (int sy = 0; sy < samples; sy++) {
                        for (int sx = 0; sx < samples; sx++) {
                            double qx = x + (sx + 0.5) / samples, qy = y + (sy + 0.5) / samples;
                            if (insideAny(star, qx, qy)) {
                                sparkHits++;
                            } else if (nearLine(px, qx, qy, half)) {
                                lineHits++;
                            }
                        }
                    }
                    int hits = lineHits + sparkHits;
                    if (hits > 0) {
                        double alpha = hits / (double) (samples * samples);
                        int r = ((strokeColor >> 16 & 255) * lineHits + (sparkleColor >> 16 & 255) * sparkHits) / hits;
                        int g = ((strokeColor >> 8 & 255) * lineHits + (sparkleColor >> 8 & 255) * sparkHits) / hits;
                        int b = ((strokeColor & 255) * lineHits + (sparkleColor & 255) * sparkHits) / hits;
                        img.setRGB(x, y, argb(alpha, r, g, b));
                    }
                }
            }
            ImageIO.write(img, "png", new File(textures, "badge_" + s + ".png"));
            // \ue000 is the mark; \ue001 a one-unit space between it and the name.
            java.nio.file.Files.writeString(new File(fonts, "badge_" + s + ".json").toPath(),
                    "{\n  \"providers\": [\n"
                            + "    { \"type\": \"bitmap\", \"file\": \"cubeutilityclient:font/badge_" + s + ".png\", \"ascent\": 7, \"height\": 8, \"chars\": [\"\\ue000\"] },\n"
                            + "    { \"type\": \"space\", \"advances\": { \"\\ue001\": 1 } }\n"
                            + "  ]\n}\n");
        }
    }

    /** Whether (x, y) is within {@code half} of any of the segments {x1, y1, x2, y2}: a round-jointed stroke. */
    static boolean nearLine(double[][] segments, double x, double y, double half) {
        for (double[] s : segments) {
            double dx = s[2] - s[0], dy = s[3] - s[1];
            double t = Math.max(0, Math.min(1, ((x - s[0]) * dx + (y - s[1]) * dy) / (dx * dx + dy * dy)));
            if (Math.hypot(x - s[0] - t * dx, y - s[1] - t * dy) <= half) {
                return true;
            }
        }
        return false;
    }

    /** Whether (x, y) is inside the polygon, by the even-odd rule (it may be concave, like a star). */
    static boolean insideAny(double[][] polygon, double x, double y) {
        boolean in = false;
        for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
            double[] a = polygon[i], b = polygon[j];
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) {
                in = !in;
            }
        }
        return in;
    }

    /**
     * Replacements for Minecraft's menu textures: a teal-black wash over the blurred scene instead
     * of grey, and gold hairlines under the header and above the footer.
     */
    static void menus(File out) throws IOException {
        out.mkdirs();
        fill(out, "menu_background.png", 16, 16, 0.30, 3, 7, 9);
        fill(out, "menu_list_background.png", 16, 16, 0.45, 3, 7, 9);
        BufferedImage header = new BufferedImage(32, 2, BufferedImage.TYPE_INT_ARGB);
        BufferedImage footer = new BufferedImage(32, 2, BufferedImage.TYPE_INT_ARGB);
        int gold = argb(0.30, 228, 192, 132), shadow = argb(0.35, 0, 0, 0);
        for (int x = 0; x < 32; x++) {
            header.setRGB(x, 0, gold);
            header.setRGB(x, 1, shadow);
            footer.setRGB(x, 0, shadow);
            footer.setRGB(x, 1, gold);
        }
        ImageIO.write(header, "png", new File(out, "header_separator.png"));
        ImageIO.write(footer, "png", new File(out, "footer_separator.png"));

        // The scrollbar: a translucent gold thumb on a faint track, corners cut for a soft edge.
        File widgets = new File(out, "sprites/widget");
        widgets.mkdirs();
        scroller(widgets, "scroller", argb(0.50, 228, 192, 132));
        scroller(widgets, "scroller_background", argb(0.06, 255, 255, 255));
    }

    static void scroller(File out, String name, int color) throws IOException {
        BufferedImage img = new BufferedImage(6, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 6; x++) {
                boolean corner = (x == 0 || x == 5) && (y == 0 || y == 31);
                img.setRGB(x, y, corner ? 0 : color);
            }
        }
        ImageIO.write(img, "png", new File(out, name + ".png"));
        java.nio.file.Files.writeString(new File(out, name + ".png.mcmeta").toPath(),
                "{\n  \"gui\": { \"scaling\": { \"type\": \"nine_slice\", \"width\": 6, \"height\": 32, \"border\": 1 } }\n}\n");
    }

    static int argb(double alpha, int red, int green, int blue) {
        return (int) Math.round(alpha * 255) << 24 | red << 16 | green << 8 | blue;
    }

    static void fill(File out, String name, int w, int h, double alpha, int red, int green, int blue) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb(alpha, red, green, blue));
            }
        }
        ImageIO.write(img, "png", new File(out, name));
    }

    interface Falloff {
        double alpha(double r);
    }

    static double smooth(double a, double b, double x) {
        double k = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return k * k * (3 - 2 * k);
    }

    static void radial(File out, String name, int size, Falloff falloff, int red, int green, int blue) throws IOException {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        double half = size / 2.0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double r = Math.hypot(x + 0.5 - half, y + 0.5 - half) / half;
                int a = (int) Math.round(Math.max(0, Math.min(1, falloff.alpha(r))) * 255);
                img.setRGB(x, y, (a << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        ImageIO.write(img, "png", new File(out, name));
    }

    /** The darkening behind the title: strong at the left edge, gone past the middle. */
    static void shade(File out) throws IOException {
        int w = 256, h = 4;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w; x++) {
            double t = (x + 0.5) / w;
            double a = t < 0.38 ? 0.62 - (0.62 - 0.30) * (t / 0.38) : t < 0.6 ? 0.30 * (1 - smooth(0.38, 0.6, t)) : 0;
            int alpha = (int) Math.round(a * 255);
            for (int y = 0; y < h; y++) img.setRGB(x, y, (alpha << 24) | 0x020305);
        }
        ImageIO.write(img, "png", new File(out, "shade.png"));
    }

    /**
     * The dark lane across the near side of the galaxy's core, in the galaxy's own plane (1 px =
     * 1 unit, centre at 128,128). The screen squashes it to the galaxy's tilt.
     */
    static void dust(File out) throws IOException {
        int size = 256;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        double cx = 128, cy = 136, radius = 92, width = 22, blur = 7;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
                double angle = Math.atan2(dy, dx);
                double a = 0;
                if (angle > 0.12 * Math.PI - 0.25 && angle < 0.88 * Math.PI + 0.25) {
                    double along = Math.min(angle - 0.12 * Math.PI, 0.88 * Math.PI - angle);
                    double ends = smooth(-0.25, 0.25, along);
                    double off = Math.abs(Math.hypot(dx, dy) - radius);
                    a = (1 - smooth(width / 2 - blur, width / 2 + blur, off)) * ends * 0.55;
                }
                int alpha = (int) Math.round(Math.max(0, a) * 255);
                img.setRGB(x, y, (alpha << 24) | 0x020406);
            }
        }
        ImageIO.write(img, "png", new File(out, "dust.png"));
    }
}
