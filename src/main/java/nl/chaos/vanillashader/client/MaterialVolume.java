package nl.chaos.vanillashader.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * A 64x64x64 voxel copy of the blocks around the camera for the ray-traced lighting: whether a block stops light,
 * what kind of surface it has and how much light it gives off. The shader marches rays through it, so light and
 * reflections also come from blocks that are off screen.
 *
 * <p>Like {@link ShadowHeightmap} the volume wraps around: block (x, y, z) lives at voxel (x mod 64, y mod 64,
 * z mod 64). The 64 horizontal slices are tiled 8 x 8 into one 512 x 512 texture; a few slices are rescanned every
 * frame. Texel layout: R = material ({@code MATERIAL_*}), G = light emission x 17, B = light colour index x 40
 * (see {@link DynamicLights#colorIndex}), A = 255 when known.
 */
final class MaterialVolume {
    static final int SIZE = 64;
    private static final int TILES = 8;
    private static final int SLICES_PER_FRAME = 4;

    // Keep in sync with voxels.glsl.
    static final int MATERIAL_AIR = 0;
    static final int MATERIAL_SOLID = 1;
    static final int MATERIAL_GLOSSY = 2;
    static final int MATERIAL_OBSIDIAN = 3;
    static final int MATERIAL_METAL = 4;
    static final int MATERIAL_GLASS = 5;
    static final int MATERIAL_EMISSIVE = 6;

    private final Map<BlockState, Integer> classified = new IdentityHashMap<>();
    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;
    private @Nullable ByteBuffer slice;
    private int nextSlice;

    @Nullable GpuTextureView view() {
        return view;
    }

    void update(ClientLevel level, Vec3 camera) {
        GpuDevice device = RenderSystem.getDevice();
        if (texture == null) {
            int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
            texture = device.createTexture(() -> "Vanilla Shader material volume", usage, TextureFormat.RGBA8, SIZE * TILES, SIZE * TILES, 1, 1);
            view = device.createTextureView(texture);
            slice = MemoryUtil.memAlloc(SIZE * SIZE * 4);
            device.createCommandEncoder().clearColorTexture(texture, 0);
        }

        int originX = (int) Math.floor(camera.x) - SIZE / 2;
        int originY = (int) Math.floor(camera.y) - SIZE / 2;
        int originZ = (int) Math.floor(camera.z) - SIZE / 2;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int n = 0; n < SLICES_PER_FRAME; n++) {
            int sliceIndex = nextSlice;
            nextSlice = (nextSlice + 1) % SIZE;
            int y = originY + Math.floorMod(sliceIndex - originY, SIZE);
            ByteBuffer buffer = slice;
            buffer.clear();
            LevelChunk chunk = null;
            for (int row = 0; row < SIZE; row++) {
                int z = originZ + Math.floorMod(row - originZ, SIZE);
                for (int column = 0; column < SIZE; column++) {
                    int x = originX + Math.floorMod(column - originX, SIZE);
                    int chunkX = SectionPos.blockToSectionCoord(x);
                    int chunkZ = SectionPos.blockToSectionCoord(z);
                    if (chunk == null || chunk.getPos().x() != chunkX || chunk.getPos().z() != chunkZ) {
                        chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                    }
                    int offset = (column + row * SIZE) * 4;
                    if (chunk == null || y < level.getMinY() || y > level.getMaxY()) {
                        buffer.putInt(offset, 0);
                        continue;
                    }
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                    int packed;
                    if (section.hasOnlyAir()) {
                        packed = MATERIAL_AIR;
                    } else {
                        packed = classify(section.getBlockState(x & 15, y & 15, z & 15));
                    }
                    buffer.put(offset, (byte) (packed & 0xFF));
                    buffer.put(offset + 1, (byte) (((packed >> 8) & 15) * 17));
                    buffer.put(offset + 2, (byte) (((packed >> 12) & 7) * 40));
                    buffer.put(offset + 3, (byte) 0xFF);
                }
            }
            buffer.limit(SIZE * SIZE * 4);
            int tileX = (sliceIndex % TILES) * SIZE;
            int tileY = (sliceIndex / TILES) * SIZE;
            device.createCommandEncoder().writeToTexture(texture, buffer, NativeImage.Format.RGBA, 0, 0, tileX, tileY, SIZE, SIZE);
        }
    }

    /** Material in the low byte, light emission (0..15) in bits 8-11, light colour index in bits 12-14. Cached per state. */
    private int classify(BlockState state) {
        Integer cached = classified.get(state);
        if (cached != null) {
            return cached;
        }
        int emission = state.getLightEmission();
        int material;
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (emission >= 6) {
            material = MATERIAL_EMISSIVE;
        } else if (name.contains("glass") || name.endsWith("ice")) {
            material = MATERIAL_GLASS;
        } else if (name.contains("obsidian")) {
            material = MATERIAL_OBSIDIAN;
        } else if (isMetal(name)) {
            material = MATERIAL_METAL;
        } else if (!state.canOcclude()) {
            material = MATERIAL_AIR;
        } else if (name.startsWith("polished_") || name.startsWith("smooth_") || name.contains("quartz")
            || name.contains("prismarine") || name.contains("glazed_terracotta") || name.endsWith("_tiles")
            || name.equals("calcite") || name.equals("purpur_block") || name.equals("purpur_pillar")) {
            material = MATERIAL_GLOSSY;
        } else {
            material = MATERIAL_SOLID;
        }
        int colorIndex = material == MATERIAL_EMISSIVE ? DynamicLights.colorIndex(DynamicLights.colorOf(state.getBlock())) : 0;
        int packed = material | (Math.min(emission, 15) << 8) | (colorIndex << 12);
        classified.put(state, packed);
        return packed;
    }

    private static boolean isMetal(String name) {
        if (name.contains("_ore") || name.startsWith("raw_")) {
            return false;
        }
        return name.equals("iron_block") || name.equals("gold_block") || name.equals("netherite_block")
            || name.equals("diamond_block") || name.equals("emerald_block") || name.contains("copper")
            || name.startsWith("amethyst") || name.equals("budding_amethyst") || name.equals("heavy_core");
    }
}
