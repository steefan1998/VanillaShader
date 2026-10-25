package nl.chaos.vanillashader.client.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlUtil;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/** Drawing full-screen passes, plus the device facts the effects need (1.21.1, plain OpenGL). */
public final class Gpu {
    private Gpu() {
    }

    /** A texture bound to {@code <name>Sampler}, sampled with linear or nearest filtering. */
    public record Input(String name, TexRef texture, boolean linear) {
    }

    public static void draw(PostPipeline pipeline, PostTexture output, @Nullable String block, @Nullable UniformBlock uniforms, Input... inputs) {
        GlState.prepareDraw();
        pipeline.program().draw(output.framebuffer(), output.width(), output.height(), block != null ? uniforms : null, inputs);
    }

    public static void draw(PostPipeline pipeline, RenderTarget output, @Nullable String block, @Nullable UniformBlock uniforms, Input... inputs) {
        GlState.prepareDraw();
        pipeline.program().draw(output.frameBufferId, output.width, output.height, block != null ? uniforms : null, inputs);
    }

    /** Puts Minecraft's OpenGL state back after a run of passes. */
    public static void finish() {
        GlState.restore();
    }

    public static boolean isZZeroToOne() {
        return false;
    }

    /** GPU and driver, for log messages. */
    public static String describe() {
        return GlUtil.getRenderer() + " (" + GlUtil.getOpenGLVersion() + ")";
    }

    /** Measures the GPU time of the commands issued between {@link #startTimer()} and {@link Timer#stop()}. */
    public static Timer startTimer() {
        return new Timer();
    }

    public static final class Timer {
        private final int query = GL15.glGenQueries();

        private Timer() {
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
        }

        public void stop() {
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        }

        // Empty until the GPU has got there.
        public OptionalLong nanos() {
            if (GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(GL33.glGetQueryObjecti64(query, GL15.GL_QUERY_RESULT));
        }

        public void close() {
            GL15.glDeleteQueries(query);
        }
    }
}
