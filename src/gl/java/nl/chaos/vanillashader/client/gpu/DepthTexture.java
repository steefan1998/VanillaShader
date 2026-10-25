package nl.chaos.vanillashader.client.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * A copy of a render target's depth buffer that passes can sample. Kept as a float colour texture (the passes only
 * read its red channel) and filled by a small pass, so it never depends on the depth format Minecraft picked.
 */
public final class DepthTexture {
    private static final GlProgram COPY = GlProgram.builtin("copy_depth", """
        uniform sampler2D InSampler;

        in vec2 texCoord;

        out vec4 fragColor;

        void main() {
            fragColor = vec4(texture(InSampler, texCoord).r, 0.0, 0.0, 1.0);
        }
        """, "In");

    private final PostTexture texture;

    public DepthTexture(String name) {
        texture = new PostTexture(name, GL30.GL_R32F, GL11.GL_RED, GL11.GL_FLOAT);
    }

    /** False when the target has no depth buffer (or the copy shader does not compile). */
    public boolean copyFrom(RenderTarget source) {
        if (!source.useDepth || source.getDepthTextureId() <= 0 || !COPY.compiles()) {
            return false;
        }
        texture.ensureSize(source.width, source.height);
        GlState.save();
        GlState.prepareDraw();
        COPY.draw(texture.framebuffer(), source.width, source.height, null, new Gpu.Input("In", TexRef.of(source.getDepthTextureId()), false));
        GlState.restore();
        return true;
    }

    public TexRef ref() {
        return texture.ref();
    }
}
