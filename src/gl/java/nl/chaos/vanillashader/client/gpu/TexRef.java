package nl.chaos.vanillashader.client.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** A texture a pass can sample: an OpenGL texture name (this is the 1.21.1 backend, on plain OpenGL). */
public final class TexRef {
    private final int id;

    private TexRef(int id) {
        this.id = id;
    }

    public static TexRef of(int id) {
        return new TexRef(id);
    }

    public static TexRef color(RenderTarget target) {
        return new TexRef(target.getColorTextureId());
    }

    public int id() {
        return id;
    }
}
