package nl.chaos.vanillashader.client.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
//? if >=26.2
/*import net.minecraft.util.ARGB;*/
//? if <26.2
import com.mojang.blaze3d.platform.NativeImage;

/** An RGBA8 texture that passes render into, sample from, and that can be filled from the CPU. */
public final class PostTexture {
    private final String name;
    private @Nullable GpuTexture texture;
    private @Nullable TexRef ref;

    public PostTexture(String name) {
        this.name = name;
    }

    /** (Re)creates the texture at this size; returns true when it was (re)created, and so holds no image yet. */
    public boolean ensureSize(int width, int height) {
        if (texture != null && texture.getWidth(0) == width && texture.getHeight(0) == height) {
            return false;
        }
        close();
        GpuDevice device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
        texture = device.createTexture(() -> "Vanilla Shader " + name, usage, TextureFormat.RGBA8, width, height, 1, 1);
        ref = TexRef.of(device.createTextureView(texture));
        return true;
    }

    /** Copies the target's colour, resizing to match; false when the target has no colour buffer. */
    public boolean copyColorFrom(RenderTarget source) {
        GpuTexture color = source.getColorTexture();
        if (color == null) {
            return false;
        }
        ensureSize(source.width, source.height);
        encoder().copyTextureToTexture(color, texture, 0, 0, 0, 0, 0, source.width, source.height);
        return true;
    }

    public void clear(int argb) {
        //? if >=26.2 {
        /*encoder().clearColorTexture(texture, ARGB.vector4fFromARGB32(argb));
        *///?} else
        encoder().clearColorTexture(texture, argb);
    }

    /** Uploads tightly packed RGBA8 pixels into a region. */
    public void upload(ByteBuffer pixels, int x, int y, int width, int height) {
        //? if >=26.2 {
        /*encoder().writeToTexture(texture, pixels, 0, 0, x, y, width, height);
        *///?} else
        encoder().writeToTexture(texture, pixels, NativeImage.Format.RGBA, 0, 0, x, y, width, height);
    }

    public TexRef ref() {
        return ref;
    }

    GpuTextureView view() {
        return ref.view();
    }

    public int width() {
        return texture.getWidth(0);
    }

    public int height() {
        return texture.getHeight(0);
    }

    public void close() {
        if (ref != null) {
            ref.view().close();
        }
        if (texture != null) {
            texture.close();
        }
        texture = null;
        ref = null;
    }

    private static CommandEncoder encoder() {
        return RenderSystem.getDevice().createCommandEncoder();
    }
}
