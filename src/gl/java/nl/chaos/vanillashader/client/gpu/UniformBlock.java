package nl.chaos.vanillashader.client.gpu;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

/** A std140 uniform buffer, rewritten every frame or written once. */
public final class UniformBlock {
    private final int buffer;
    private final int size;
    private final ByteBuffer staging;

    private UniformBlock(int size) {
        this.size = size;
        this.staging = MemoryUtil.memAlloc(size);
        this.buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, size, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
    }

    public static UniformBlock perFrame(String name, int size) {
        return new UniformBlock(size);
    }

    public static UniformBlock constant(String name, int size, Consumer<ByteBuffer> writer) {
        UniformBlock block = new UniformBlock(size);
        block.write(writer);
        return block;
    }

    public void write(Consumer<ByteBuffer> writer) {
        staging.clear();
        writer.accept(staging);
        staging.rewind();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer);
        // Orphaning first lets the driver hand out fresh memory instead of waiting for last frame's draws.
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, size, GL15.GL_DYNAMIC_DRAW);
        GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, staging);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
    }

    /** Nothing to do on OpenGL: {@link #write} already orphans the buffer. */
    public void rotate() {
    }

    public void close() {
        GL15.glDeleteBuffers(buffer);
        MemoryUtil.memFree(staging);
    }

    int buffer() {
        return buffer;
    }
}
