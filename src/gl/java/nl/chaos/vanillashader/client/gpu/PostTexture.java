package nl.chaos.vanillashader.client.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/** An RGBA8 texture that passes render into, sample from, and that can be filled from the CPU. */
public final class PostTexture {
    private final String name;
    private final int internalFormat;
    private final int format;
    private final int type;
    private int texture;
    private int framebuffer;
    private int width;
    private int height;
    private TexRef ref;

    public PostTexture(String name) {
        this(name, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
    }

    PostTexture(String name, int internalFormat, int format, int type) {
        this.name = name;
        this.internalFormat = internalFormat;
        this.format = format;
        this.type = type;
    }

    /** (Re)creates the texture at this size; returns true when it was (re)created, and so holds no image yet. */
    public boolean ensureSize(int width, int height) {
        if (texture != 0 && this.width == width && this.height == height) {
            return false;
        }
        close();
        this.width = width;
        this.height = height;
        GlState.save();
        texture = GlStateManager._genTexture();
        GlState.bindTexture(0, texture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, null);
        framebuffer = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        GlState.restore();
        ref = TexRef.of(texture);
        return true;
    }

    /** Copies the target's colour, resizing to match; false when the target has no colour buffer. */
    public boolean copyColorFrom(RenderTarget source) {
        if (source.getColorTextureId() <= 0) {
            return false;
        }
        ensureSize(source.width, source.height);
        GlState.save();
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlState.restore();
        return true;
    }

    public void clear(int argb) {
        GlState.save();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._clearColor(((argb >> 16) & 0xFF) / 255.0F, ((argb >> 8) & 0xFF) / 255.0F, (argb & 0xFF) / 255.0F, ((argb >>> 24) & 0xFF) / 255.0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GlState.restore();
    }

    /** Uploads tightly packed RGBA8 pixels into a region. */
    public void upload(ByteBuffer pixels, int x, int y, int width, int height) {
        GlState.save();
        GlState.bindTexture(0, texture);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        GlState.restore();
    }

    public TexRef ref() {
        return ref;
    }

    int framebuffer() {
        return framebuffer;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void close() {
        if (framebuffer != 0) {
            GlStateManager._glDeleteFramebuffers(framebuffer);
        }
        if (texture != 0) {
            GlStateManager._deleteTexture(texture);
        }
        framebuffer = 0;
        texture = 0;
        ref = null;
    }

    @Override
    public String toString() {
        return name;
    }
}
