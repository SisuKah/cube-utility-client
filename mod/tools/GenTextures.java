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
