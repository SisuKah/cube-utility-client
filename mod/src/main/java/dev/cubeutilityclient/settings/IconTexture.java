package dev.cubeutilityclient.settings;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * A mod's icon, scaled to a 128 px square with mipmaps, so it stays smooth at the 20 to 50
 * pixels the settings page shows it at, whatever size the mod ships it in.
 */
final class IconTexture extends AbstractTexture {
    static final int SIZE = 128;

    IconTexture(Identifier id, NativeImage source) {
        NativeImage image = fit(source);
        // Clear pixels take their neighbours' colour, so smaller mip levels don't get dark rims.
        TextureUtil.solidify(image);
        GpuDevice device = RenderSystem.getDevice();
        NativeImage[] mips = MipmapGenerator.generateMipLevels(id, new NativeImage[] {image}, 4, MipmapStrategy.MEAN, 0f, image.computeTransparency());
        this.texture = device.createTexture(id::toString, 5, GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, mips.length);
        this.textureView = device.createTextureView(this.texture);
        for (int level = 0; level < mips.length; level++) {
            device.createCommandEncoder().writeToTexture(this.texture, mips[level], level, 0, 0, 0);
        }
        for (NativeImage mip : mips) {
            mip.close();
        }
        this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, true);
    }

    /** The image in the middle of a clear 128 px square, averaged down (or scaled up) to fit. */
    private static NativeImage fit(NativeImage source) {
        int sw = source.getWidth(), sh = source.getHeight();
        float scale = SIZE / (float) Math.max(sw, sh);
        int dw = Math.max(1, Math.round(sw * scale)), dh = Math.max(1, Math.round(sh * scale));
        int ox = (SIZE - dw) / 2, oy = (SIZE - dh) / 2;
        NativeImage out = new NativeImage(SIZE, SIZE, true);
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                // The source area this pixel covers, averaged with alpha weighting.
                float x0 = x / scale, x1 = (x + 1) / scale, y0 = y / scale, y1 = (y + 1) / scale;
                int ix0 = (int) Math.floor(x0), ix1 = Math.min(sw, Math.max(ix0 + 1, (int) Math.ceil(x1)));
                int iy0 = (int) Math.floor(y0), iy1 = Math.min(sh, Math.max(iy0 + 1, (int) Math.ceil(y1)));
                float a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int sy = iy0; sy < iy1; sy++) {
                    for (int sx = ix0; sx < ix1; sx++) {
                        int c = source.getPixel(sx, sy);
                        float ca = ARGB.alpha(c) / 255f;
                        a += ca;
                        r += ARGB.red(c) * ca;
                        g += ARGB.green(c) * ca;
                        b += ARGB.blue(c) * ca;
                        n++;
                    }
                }
                int color = a <= 0f ? 0 : ARGB.color(Math.round(a / n * 255f), Math.round(r / a), Math.round(g / a), Math.round(b / a));
                out.setPixel(ox + x, oy + y, color);
            }
        }
        return out;
    }
}
