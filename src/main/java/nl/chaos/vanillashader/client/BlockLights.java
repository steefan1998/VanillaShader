package nl.chaos.vanillashader.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Light-emitting blocks placed in the world (torches, lanterns, glowstone, ...) around the camera, so they can get the
 * same smooth, coloured light as held ones. Chunks are rescanned round-robin a few per frame; sections whose palette
 * holds no light source are skipped without looking at their blocks.
 */
final class BlockLights {
    private static final int RADIUS_CHUNKS = 2;
    private static final int CHUNKS_PER_FRAME = 2;
    private static final int MIN_LEVEL = 6;

    private final Map<Long, List<Light>> chunks = new HashMap<>();
    private int cursor;

    record Light(double x, double y, double z, int level, Vector3f color, boolean flickers) {
    }

    void update(ClientLevel level, Vec3 camera) {
        int centerX = SectionPos.blockToSectionCoord(camera.x);
        int centerZ = SectionPos.blockToSectionCoord(camera.z);
        chunks.keySet().removeIf(key -> Math.abs(ChunkPos.getX(key) - centerX) > RADIUS_CHUNKS || Math.abs(ChunkPos.getZ(key) - centerZ) > RADIUS_CHUNKS);

        int side = RADIUS_CHUNKS * 2 + 1;
        for (int i = 0; i < CHUNKS_PER_FRAME; i++) {
            int index = cursor++ % (side * side);
            scan(level, centerX - RADIUS_CHUNKS + index % side, centerZ - RADIUS_CHUNKS + index / side);
        }
    }

    /** Every known light source, for {@link DynamicLights} to pick the nearest from. */
    void addTo(List<Light> out) {
        for (List<Light> lights : chunks.values()) {
            out.addAll(lights);
        }
    }

    private void scan(ClientLevel level, int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            chunks.remove(key);
            return;
        }
        List<Light> lights = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.getLightEmission() >= MIN_LEVEL)) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        int emission = state.getLightEmission();
                        if (emission >= MIN_LEVEL) {
                            Vector3f color = DynamicLights.colorOf(state.getBlock());
                            lights.add(new Light(
                                (chunkX << 4) + x + 0.5, baseY + y + 0.5, (chunkZ << 4) + z + 0.5,
                                emission, color, DynamicLights.flickers(color)
                            ));
                        }
                    }
                }
            }
        }
        if (lights.isEmpty()) {
            chunks.remove(key);
        } else {
            chunks.put(key, lights);
        }
    }
}
