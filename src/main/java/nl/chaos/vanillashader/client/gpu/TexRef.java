package nl.chaos.vanillashader.client.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;

/** A texture a pass can sample. (The 1.21.1 backend has its own version of this package, on plain OpenGL.) */
public final class TexRef {
    private final GpuTextureView view;

    private TexRef(GpuTextureView view) {
        this.view = view;
    }

    public static TexRef of(GpuTextureView view) {
        return new TexRef(view);
    }

    public static TexRef color(RenderTarget target) {
        return new TexRef(target.getColorTextureView());
    }

    public GpuTextureView view() {
        return view;
    }
}
