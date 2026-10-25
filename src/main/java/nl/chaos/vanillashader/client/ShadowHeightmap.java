package nl.chaos.vanillashader.client;

import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import nl.chaos.vanillashader.client.gpu.PostTexture;
import nl.chaos.vanillashader.compat.McCompat;
import nl.chaos.vanillashader.client.gpu.TexRef;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * A top-down height map of the terrain around the camera (leaves included), used to ray-march sun shadows.
 *
 * <p>The texture wraps around: column {@code (x, z)} lives at texel {@code (x mod SIZE, z mod SIZE)}, so moving
 * only requires refreshing columns, never shifting data. A band of rows is refreshed every frame, which also
 * picks up placed and broken blocks within a fraction of a second. Each texel packs two 12-bit
 * heights above the world's minimum Y: R + high nibble of G = top including leaves (what casts shadows), low nibble
 * of G + B = top without leaves (to tell caves from ground under tall trees). A = 255 when the top block holds
 * water, 128 when it does not (so ice and glass are not mistaken for water), 0 when the column is unknown.
 */
final class ShadowHeightmap {
    static final int SIZE = 512;
    private static final int ROWS_PER_FRAME = 16;

    private @Nullable PostTexture texture;
    private @Nullable ByteBuffer band;
    private int nextRow;
    private int minY;
    // Highest top per texture row, so sun rays can stop as soon as they are above all terrain.
    private final int[] rowMaxTop = new int[SIZE];

    @Nullable TexRef ref() {
        return texture != null ? texture.ref() : null;
    }

    int minY() {
        return minY;
    }

    int maxTerrainY() {
        int max = 0;
        for (int top : rowMaxTop) {
            max = Math.max(max, top);
        }
        return max + minY;
    }

    void update(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        if (texture == null) {
            texture = new PostTexture("shadow heightmap");
            texture.ensureSize(SIZE, SIZE);
            band = MemoryUtil.memAlloc(SIZE * ROWS_PER_FRAME * 4);
            texture.clear(0);
        }

        minY = level.getMinY();
        int centerX = (int) Math.floor(McCompat.cameraPosition().x);
        int centerZ = (int) Math.floor(McCompat.cameraPosition().z);
        int originX = centerX - SIZE / 2;
        int originZ = centerZ - SIZE / 2;

        ByteBuffer buffer = band;
        buffer.clear();
        LevelChunk chunk = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int row = nextRow; row < nextRow + ROWS_PER_FRAME; row++) {
            int z = originZ + Math.floorMod(row - originZ, SIZE);
            int rowMax = 0;
            for (int column = 0; column < SIZE; column++) {
                int x = originX + Math.floorMod(column - originX, SIZE);
                int chunkX = SectionPos.blockToSectionCoord(x);
                int chunkZ = SectionPos.blockToSectionCoord(z);
                if (chunk == null || chunk.getPos().x() != chunkX || chunk.getPos().z() != chunkZ) {
                    chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                }
                int offset = (column + (row - nextRow) * SIZE) * 4;
                if (chunk == null) {
                    buffer.putInt(offset, 0);
                    continue;
                }
                // getHeight returns the top block; +1 gives the first free height, i.e. the top face.
                int top = Mth.clamp(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1 - minY, 0, 4095);
                rowMax = Math.max(rowMax, top);
                int ground = Mth.clamp(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1 - minY, 0, 4095);
                buffer.put(offset, (byte) (top >> 4));
                buffer.put(offset + 1, (byte) (((top & 15) << 4) | (ground >> 8)));
                buffer.put(offset + 2, (byte) ground);
                cursor.set(x, top - 1 + minY, z);
                boolean water = chunk.getBlockState(cursor).getFluidState().is(FluidTags.WATER);
                buffer.put(offset + 3, (byte) (water ? 0xFF : 0x80));
            }
            rowMaxTop[row] = rowMax;
        }
        buffer.limit(SIZE * ROWS_PER_FRAME * 4);
        texture.upload(buffer, 0, nextRow, SIZE, ROWS_PER_FRAME);
        nextRow = (nextRow + ROWS_PER_FRAME) % SIZE;
    }
}
