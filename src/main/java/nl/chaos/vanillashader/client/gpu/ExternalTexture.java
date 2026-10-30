package nl.chaos.vanillashader.client.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/**
 * A texture owned by another mod (Distant Horizons' depth buffer), handed over as a GPU texture or view. A view we
 * create for it is kept until the texture changes, since the owner recreates it on resize without telling us.
 */
public final class ExternalTexture {
    public static final ExternalTexture DH_DEPTH = new ExternalTexture();

    private @Nullable GpuTexture source;
    private @Nullable TexRef ref;

    private ExternalTexture() {
    }

    /** Null when the object is no texture we can sample. */
    public @Nullable TexRef wrap(@Nullable Object texture) {
        if (texture instanceof GpuTextureView view) {
            return view.isClosed() ? null : TexRef.of(view);
        }
        if (!(texture instanceof GpuTexture gpuTexture) || gpuTexture.isClosed()) {
            return null;
        }
        if (gpuTexture != source || ref == null) {
            if (ref != null) {
                ref.view().close();
            }
            source = gpuTexture;
            ref = TexRef.of(RenderSystem.getDevice().createTextureView(gpuTexture));
        }
        return ref;
    }
}
