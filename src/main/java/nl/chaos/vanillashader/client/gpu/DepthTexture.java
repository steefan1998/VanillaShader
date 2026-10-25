package nl.chaos.vanillashader.client.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.jspecify.annotations.Nullable;

/** A copy of a render target's depth buffer, in the same format, that passes can sample. */
public final class DepthTexture {
    private final String name;
    private @Nullable GpuTexture texture;
    private @Nullable TexRef ref;

    public DepthTexture(String name) {
        this.name = name;
    }

    /** False when the target has no depth buffer. */
    public boolean copyFrom(RenderTarget source) {
        GpuTexture depth = source.getDepthTexture();
        if (depth == null) {
            return false;
        }
        int width = depth.getWidth(0);
        int height = depth.getHeight(0);
        if (texture == null || texture.getWidth(0) != width || texture.getHeight(0) != height || texture.getFormat() != depth.getFormat()) {
            if (ref != null) {
                ref.view().close();
            }
            if (texture != null) {
                texture.close();
            }
            GpuDevice device = RenderSystem.getDevice();
            int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
            texture = device.createTexture(() -> "Vanilla Shader " + name, usage, depth.getFormat(), width, height, 1, 1);
            ref = TexRef.of(device.createTextureView(texture));
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(depth, texture, 0, 0, 0, 0, 0, width, height);
        return true;
    }

    public TexRef ref() {
        return ref;
    }
}
