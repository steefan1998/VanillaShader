package nl.chaos.vanillashader.client.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;
//? if >=26.2 {
/*import com.mojang.blaze3d.systems.GpuQueryPool;
import java.util.Optional;
*///?} else {
import com.mojang.blaze3d.systems.GpuQuery;
import java.util.OptionalInt;
//?}

/** Drawing full-screen passes, plus the device facts the effects need. */
public final class Gpu {
    private Gpu() {
    }

    /** A texture bound to {@code <name>Sampler}, sampled with linear or nearest filtering. */
    public record Input(String name, TexRef texture, boolean linear) {
    }

    public static void draw(PostPipeline pipeline, PostTexture output, @Nullable String block, @Nullable UniformBlock uniforms, Input... inputs) {
        draw(pipeline, output.view(), block, uniforms, inputs);
    }

    public static void draw(PostPipeline pipeline, RenderTarget output, @Nullable String block, @Nullable UniformBlock uniforms, Input... inputs) {
        draw(pipeline, output.getColorTextureView(), block, uniforms, inputs);
    }

    /** Draws into a texture of another backend-specific kind (the 26.3 OIT depth). */
    public static void draw(PostPipeline pipeline, TexRef output, @Nullable String block, @Nullable UniformBlock uniforms, Input... inputs) {
        draw(pipeline, output.view(), block, uniforms, inputs);
    }

    private static void draw(PostPipeline pipeline, GpuTextureView output, @Nullable String block, @Nullable UniformBlock uniforms, Input... inputs) {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        //? if >=26.2 {
        /*RenderPass pass = encoder.createRenderPass(() -> "Vanilla Shader " + pipeline.location(), output, Optional.empty());
        *///?} else
        RenderPass pass = encoder.createRenderPass(() -> "Vanilla Shader " + pipeline.location(), output, OptionalInt.empty());
        try (pass) {
            //? if >=26.3 {
            /*pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline.pipeline()));
            *///?} else
            pass.setPipeline(pipeline.pipeline());
            // From 26.2 every pipeline also reads the global uniforms.
            //? if >=26.2
            /*RenderSystem.bindDefaultUniforms(pass);*/
            if (block != null && uniforms != null) {
                pass.setUniform(block, uniforms.buffer());
            }
            for (Input input : inputs) {
                GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(input.linear() ? FilterMode.LINEAR : FilterMode.NEAREST);
                //? if >=26.3 {
                /*pass.setUniform(input.name() + "Sampler", input.texture().view(), sampler);
                *///?} else
                pass.bindTexture(input.name() + "Sampler", input.texture().view(), sampler);
            }
            // One triangle covering the screen (the screenquad vertex shader makes it from the vertex index).
            //? if >=26.2 {
            /*pass.draw(3, 1, 0, 0);
            *///?} else
            pass.draw(0, 3);
        }
    }

    /** Puts Minecraft's GPU state back after a run of passes; nothing to do on its own GPU API. */
    public static void finish() {
    }

    public static boolean isZZeroToOne() {
        //? if >=26.2 {
        /*return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        *///?} else
        return RenderSystem.getDevice().isZZeroToOne();
    }

    /** GPU and driver, for log messages. */
    public static String describe() {
        GpuDevice device = RenderSystem.getDevice();
        //? if >=26.2 {
        /*return device.getDeviceInfo().name() + " (" + device.getDeviceInfo().backendName() + " " + device.getDeviceInfo().driverInfo() + ")";
        *///?} else
        return device.getRenderer() + " (" + device.getVersion() + ")";
    }

    /** Measures the GPU time of the commands recorded between {@link #startTimer()} and {@link Timer#stop()}. */
    public static Timer startTimer() {
        return new Timer();
    }

    public static final class Timer {
        //? if >=26.2 {
        /*private final GpuQueryPool pool = RenderSystem.getDevice().createTimestampQueryPool(2);

        private Timer() {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, 0);
        }

        public void stop() {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, 1);
        }

        // Empty until the GPU has got there.
        public OptionalLong nanos() {
            OptionalLong[] stamps = pool.getValues(0, 2);
            if (stamps[0].isEmpty() || stamps[1].isEmpty()) {
                return OptionalLong.empty();
            }
            double period = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
            return OptionalLong.of(Math.round((stamps[1].getAsLong() - stamps[0].getAsLong()) * period));
        }

        public void close() {
            pool.close();
        }
        *///?} else {
        private final GpuQuery query = RenderSystem.getDevice().createCommandEncoder().timerQueryBegin();

        private Timer() {
        }

        public void stop() {
            RenderSystem.getDevice().createCommandEncoder().timerQueryEnd(query);
        }

        // Empty until the GPU has got there.
        public OptionalLong nanos() {
            return query.getValue();
        }

        public void close() {
            query.close();
        }
        //?}
    }
}
