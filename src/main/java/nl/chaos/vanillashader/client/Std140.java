package nl.chaos.vanillashader.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;

/**
 * Writes a std140 uniform block. Ours only hold mat4 and vec4 members, which need no padding, so this is a plain
 * sequential writer (Minecraft's own Std140Builder only exists from 1.21.6).
 */
public final class Std140 {
    private final ByteBuffer buffer;

    private Std140(ByteBuffer buffer) {
        this.buffer = buffer.order(ByteOrder.nativeOrder());
    }

    public static Std140 into(ByteBuffer buffer) {
        return new Std140(buffer);
    }

    public Std140 putMat4f(Matrix4fc matrix) {
        matrix.get(buffer.position(), buffer);
        buffer.position(buffer.position() + 64);
        return this;
    }

    public Std140 putVec4(float x, float y, float z, float w) {
        buffer.putFloat(x).putFloat(y).putFloat(z).putFloat(w);
        return this;
    }

    public Std140 putVec4(Vector4fc vector) {
        return putVec4(vector.x(), vector.y(), vector.z(), vector.w());
    }
}
