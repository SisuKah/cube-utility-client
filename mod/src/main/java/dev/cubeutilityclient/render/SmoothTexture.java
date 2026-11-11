package dev.cubeutilityclient.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.MipmappedTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * A texture with mipmaps made by plain averaging, so it shrinks smoothly. Minecraft's own
 * {@link MipmappedTexture} treats any image with fully clear pixels as a cut-out and stretches the
 * alpha of its smaller levels to keep hard edges, which turns soft glows and thin icons blotchy.
 *
 * <p>Averaging ignores alpha, so the clear pixels of these images carry the same colour as the
 * rest (tools/GenTextures.java makes them that way); otherwise edges would darken as they shrink.
 */
final class SmoothTexture extends MipmappedTexture {
    private static final int LEVELS = 4;

    SmoothTexture(Identifier location) {
        super(location, LEVELS);
    }

    @Override
    protected void doLoad(NativeImage image) {
        GpuDevice device = RenderSystem.getDevice();
        this.close();
        int smallestSide = Math.min(Integer.lowestOneBit(image.getWidth()), Integer.lowestOneBit(image.getHeight()));
        int levels = Math.min(LEVELS, Mth.log2(smallestSide));
        NativeImage[] mips = MipmapGenerator.generateMipLevels(this.resourceId(), new NativeImage[] {image}, levels, MipmapStrategy.MEAN, 0f, image.computeTransparency());
        this.texture = device.createTexture(this.resourceId()::toString, 5, GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, mips.length);
        this.textureView = device.createTextureView(this.texture);
        for (int level = 0; level < mips.length; level++) {
            device.createCommandEncoder().writeToTexture(this.texture, mips[level], level, 0, 0, 0);
        }
        // Level 0 is the loaded image, which its owner closes.
        for (int level = 1; level < mips.length; level++) {
            mips[level].close();
        }
    }
}
