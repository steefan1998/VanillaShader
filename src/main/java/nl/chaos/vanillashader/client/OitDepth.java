package nl.chaos.vanillashader.client;

//? if >=26.3 {
/*import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.Optional;
import net.minecraft.client.renderer.LevelTargetBundle;
import nl.chaos.vanillashader.client.gpu.Gpu;
import nl.chaos.vanillashader.client.gpu.Gpu.Input;
import nl.chaos.vanillashader.client.gpu.PostPipeline;
import nl.chaos.vanillashader.client.gpu.TexRef;
import nl.chaos.vanillashader.client.gpu.UniformBlock;
import org.jspecify.annotations.Nullable;

// With improved transparency (order-independent transparency, 26.3+) water, glass and ice no longer write the depth
// buffer. OIT's depth bounds target keeps the nearest translucent surface per pixel instead (as minus its linear
// distance, in x); resolve() turns that back into the depth buffer the effects expect: the opaque depth with the
// translucent surfaces in front of it.
final class OitDepth {
    private @Nullable PostPipeline resolve;
    private @Nullable GpuTexture bounds;
    private @Nullable TexRef boundsRef;
    private @Nullable GpuTexture depth;
    private @Nullable TexRef depthRef;

    // Copies the depth bounds while they are still valid (they belong to the frame graph and are reused afterwards).
    boolean capture(LevelTargetBundle targets) {
        if (targets.depthBounds == ResourceHandle.<RenderTarget>invalid()) {
            return false;
        }
        RenderTarget source = targets.depthBounds.get();
        GpuTexture color = source.getColorTexture();
        if (color == null) {
            return false;
        }
        GpuDevice device = RenderSystem.getDevice();
        if (bounds == null || bounds.getWidth(0) != source.width || bounds.getHeight(0) != source.height || bounds.getFormat() != color.getFormat()) {
            close();
            bounds = device.createTexture(() -> "Vanilla Shader OIT depth bounds", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, color.getFormat(), source.width, source.height, 1, 1);
            boundsRef = TexRef.of(device.createTextureView(bounds));
            depth = device.createTexture(() -> "Vanilla Shader OIT depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.R32_FLOAT, source.width, source.height, 1, 1);
            depthRef = TexRef.of(device.createTextureView(depth));
        }
        device.createCommandEncoder().copyTextureToTexture(color, bounds, 0, 0, 0, 0, 0, source.width, source.height);
        return true;
    }

    // The combined depth, or null when the resolve shader does not compile on this GPU.
    @Nullable TexRef resolve(UniformBlock frame, TexRef opaqueDepth, int width, int height) {
        if (resolve == null) {
            resolve = PostPipeline.of(PostPipeline.builder("oit_depth", "Frame", "Depth", "Bounds")
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                .build());
        }
        if (depth == null || depth.getWidth(0) != width || depth.getHeight(0) != height || !ShaderRenderer.usable(resolve)) {
            return null;
        }
        try (PassTimer.Timing timing = PassTimer.gpu(resolve.path())) {
            Gpu.draw(resolve, depthRef, "Frame", frame, new Input("Depth", opaqueDepth, false), new Input("Bounds", boundsRef, false));
        }
        return depthRef;
    }

    private void close() {
        if (bounds != null) {
            boundsRef.view().close();
            bounds.close();
            depthRef.view().close();
            depth.close();
        }
        bounds = null;
        boundsRef = null;
        depth = null;
        depthRef = null;
    }
}
*///?}
