package nl.chaos.vanillashader.client.gpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
//? if >=26.2
/*import com.mojang.blaze3d.buffers.GpuBufferSlice;*/

/** A std140 uniform buffer: rewritten every frame (a small ring, so the GPU never waits) or written once. */
public final class UniformBlock {
    private final @Nullable MappableRingBuffer ring;
    private final @Nullable GpuBuffer fixed;

    private UniformBlock(@Nullable MappableRingBuffer ring, @Nullable GpuBuffer fixed) {
        this.ring = ring;
        this.fixed = fixed;
    }

    public static UniformBlock perFrame(String name, int size) {
        return new UniformBlock(new MappableRingBuffer(() -> "Vanilla Shader " + name, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, size), null);
    }

    public static UniformBlock constant(String name, int size, Consumer<ByteBuffer> writer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.malloc(size);
            writer.accept(data);
            data.rewind();
            return new UniformBlock(null, RenderSystem.getDevice().createBuffer(() -> "Vanilla Shader " + name, GpuBuffer.USAGE_UNIFORM, data));
        }
    }

    /** Rewrites a per-frame block; draws after this see the new contents. */
    public void write(Consumer<ByteBuffer> writer) {
        //? if >=26.2 {
        /*try (GpuBufferSlice.MappedView view = ring.currentBuffer().map(false, true)) {
            writer.accept(view.data());
        }
        *///?} else {
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(ring.currentBuffer(), false, true)) {
            writer.accept(view.data());
        }
        //?}
    }

    /** Moves a per-frame block on to its next buffer; call once the frame's draws are recorded. */
    public void rotate() {
        ring.rotate();
    }

    public void close() {
        if (ring != null) {
            ring.close();
        }
        if (fixed != null) {
            fixed.close();
        }
    }

    GpuBuffer buffer() {
        return ring != null ? ring.currentBuffer() : fixed;
    }
}
