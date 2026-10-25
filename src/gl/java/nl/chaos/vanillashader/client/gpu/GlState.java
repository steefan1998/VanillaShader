package nl.chaos.vanillashader.client.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferUploader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * The OpenGL state our passes change, and putting Minecraft's back. Everything that Minecraft caches goes through
 * GlStateManager so its cache stays right; the framebuffer, vertex array and samplers are restored by hand.
 */
final class GlState {
    static final int MAX_INPUTS = 16;
    private static int emptyVertexArray;
    private static boolean saved;
    private static int framebuffer;
    private static int vertexArray;
    private static int activeTexture;

    private GlState() {
    }

    /** Remembers what Minecraft had bound; nested calls keep the outermost state. */
    static void save() {
        if (saved) {
            return;
        }
        saved = true;
        framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        activeTexture = GlStateManager._getActiveTexture();
    }

    /** Sets up for full-screen passes: no depth test, blending or culling, and an empty vertex array. */
    static void prepareDraw() {
        save();
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._disableBlend();
        GlStateManager._disableCull();
        GlStateManager._disableScissorTest();
        GlStateManager._colorMask(true, true, true, true);
        if (emptyVertexArray == 0) {
            emptyVertexArray = GlStateManager._glGenVertexArrays();
        }
        GlStateManager._glBindVertexArray(emptyVertexArray);
    }

    static void restore() {
        if (!saved) {
            return;
        }
        saved = false;
        for (int unit = 0; unit < MAX_INPUTS; unit++) {
            GL33.glBindSampler(unit, 0);
        }
        GlStateManager._glUseProgram(0);
        GlStateManager._glBindVertexArray(vertexArray);
        // Minecraft remembers the last vertex buffer it bound and would skip binding it again.
        BufferUploader.invalidate();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GlStateManager._activeTexture(activeTexture);
        GlStateManager._depthMask(true);
    }

    static void bindTexture(int unit, int texture) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture);
    }
}
