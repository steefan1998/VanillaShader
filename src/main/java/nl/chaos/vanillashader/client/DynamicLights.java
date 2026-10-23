package nl.chaos.vanillashader.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.squid.GlowSquid;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Point lights for the shader: light carried by entities (held or dropped torches, lanterns, glowstone, lava buckets,
 * burning mobs) first, then the nearest light blocks placed in the world. Vanilla already lights placed blocks, so
 * they only add a softer, coloured glow on top; entity lights have no vanilla light at all and count in full.
 */
final class DynamicLights {
    static final int MAX_LIGHTS = 32;
    private static final int MAX_ENTITY_LIGHTS = 16;
    private static final double ENTITY_RANGE = 48.0;
    private static final double BLOCK_RANGE = 32.0;
    private static final float PLACED_STRENGTH = 0.22F;

    private static final Vector3f WARM = new Vector3f(1.0F, 0.68F, 0.36F);
    private static final Vector3f SOUL = new Vector3f(0.42F, 0.78F, 1.0F);
    private static final Vector3f REDSTONE = new Vector3f(1.0F, 0.28F, 0.18F);
    private static final Vector3f COOL = new Vector3f(0.82F, 0.92F, 1.0F);
    private static final Vector3f GOLDEN = new Vector3f(1.0F, 0.84F, 0.52F);
    private static final Vector3f LAVA = new Vector3f(1.0F, 0.45F, 0.15F);
    private static final Vector3f SCULK = new Vector3f(0.25F, 0.85F, 1.0F);
    private static final Vector3f ENDER = new Vector3f(0.78F, 0.35F, 1.0F);
    private static final Vector3f WHITE = new Vector3f(1.0F, 0.97F, 0.92F);
    private static final Vector3f PHANTOM_GREEN = new Vector3f(0.55F, 1.0F, 0.6F);
    private static final Vector3f EXPERIENCE = new Vector3f(0.65F, 1.0F, 0.3F);

    /** x, y, z relative to the camera and radius in blocks; then r, g, b and intensity. */
    private final float[] data = new float[MAX_LIGHTS * 8];
    private final BlockLights blockLights = new BlockLights();
    private int count;

    int count() {
        return count;
    }

    float get(int light, int component) {
        return data[light * 8 + component];
    }

    void collect(@Nullable ClientLevel level, Vec3 camera, float partialTick, float seconds) {
        count = 0;
        if (level == null) {
            return;
        }

        List<Source> sources = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.distanceToSqr(camera) <= ENTITY_RANGE * ENTITY_RANGE) {
                Source source = sourceOf(entity, partialTick);
                if (source != null) {
                    sources.add(source);
                }
            }
        }
        sources.sort(Comparator.comparingDouble(source -> source.entity.distanceToSqr(camera)));
        for (Source source : sources) {
            if (count >= MAX_ENTITY_LIGHTS) {
                break;
            }
            Entity entity = source.entity;
            Vec3 pos = entity.getPosition(partialTick);
            // Held lights sit at hand height; dropped items and fires light from their middle.
            double height = entity.getBbHeight() * source.height + (entity instanceof LivingEntity ? 0.0 : 0.1);
            add(pos.x, pos.y + height, pos.z, camera, source.level, source.color, 1.0F, source.flickers, seconds, entity.getId());
        }

        blockLights.update(level, camera);
        List<BlockLights.Light> placed = new ArrayList<>();
        blockLights.addTo(placed);
        placed.removeIf(light -> distanceSqr(light, camera) > BLOCK_RANGE * BLOCK_RANGE);
        placed.sort(Comparator.comparingDouble(light -> distanceSqr(light, camera)));
        for (BlockLights.Light light : placed) {
            if (count >= MAX_LIGHTS) {
                break;
            }
            int seed = (int) (light.x() * 7.0 + light.y() * 13.0 + light.z() * 17.0);
            add(light.x(), light.y(), light.z(), camera, light.level(), light.color(), PLACED_STRENGTH, light.flickers(), seconds, seed);
        }
    }

    private void add(double x, double y, double z, Vec3 camera, float level, Vector3f color, float strength, boolean flickers, float seconds, int seed) {
        // Fire-like lights flicker slightly, each at its own pace.
        float flicker = flickers ? 0.9F + 0.1F * (float) Math.sin(seconds * 9.0 + seed * 1.7) * (float) Math.sin(seconds * 5.3 + seed) : 1.0F;
        int offset = count * 8;
        data[offset] = (float) (x - camera.x);
        data[offset + 1] = (float) (y - camera.y);
        data[offset + 2] = (float) (z - camera.z);
        data[offset + 3] = level;
        data[offset + 4] = color.x;
        data[offset + 5] = color.y;
        data[offset + 6] = color.z;
        data[offset + 7] = level / 15.0F * flicker * strength;
        count++;
    }

    private static double distanceSqr(BlockLights.Light light, Vec3 camera) {
        double dx = light.x() - camera.x;
        double dy = light.y() - camera.y;
        double dz = light.z() - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static @Nullable Source sourceOf(Entity entity, float partialTick) {
        Source best = emissive(entity, partialTick);
        if (entity instanceof LivingEntity living) {
            best = brighter(best, entity, living.getMainHandItem());
            best = brighter(best, entity, living.getOffhandItem());
        } else if (entity instanceof ItemEntity item) {
            best = brighter(best, entity, item.getItem());
        }
        if (entity.isOnFire() && (best == null || best.level < 15)) {
            best = new Source(entity, 15, WARM, true, 0.5);
        }
        return best;
    }

    private static @Nullable Source brighter(@Nullable Source current, Entity entity, ItemStack stack) {
        if (stack.isEmpty()) {
            return current;
        }
        int level = 0;
        Vector3f color = WARM;
        if (stack.is(Items.LAVA_BUCKET)) {
            level = 15;
            color = LAVA;
        } else if (stack.getItem() instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            level = block.defaultBlockState().getLightEmission();
            color = colorOf(block);
        }
        if (level <= 0 || (current != null && current.level >= level)) {
            return current;
        }
        return new Source(entity, level, color, flickers(color), entity instanceof LivingEntity ? 0.7 : 0.5);
    }

    /**
     * Glowing parts of mobs and projectiles, as bright as they currently look: the Warden's heart beats, a creeper
     * flashes before exploding, primed TNT blinks, a glow squid dims when hurt.
     */
    private static @Nullable Source emissive(Entity entity, float partialTick) {
        EntityType<?> type = entity.getType();
        if (type == EntityType.WARDEN && entity instanceof Warden warden) {
            float beat = warden.getHeartAnimation(partialTick);
            return beat > 0.05F ? new Source(entity, 10.0F * beat, SCULK, false, 0.62) : null;
        }
        if (type == EntityType.CREEPER && entity instanceof Creeper creeper) {
            // Same on/off rhythm as the white overlay the creeper renderer draws.
            float swelling = creeper.getSwelling(partialTick);
            float flash = (int) (swelling * 10.0F) % 2 == 0 ? 0.0F : Mth.clamp(swelling, 0.5F, 1.0F);
            return flash > 0.0F ? new Source(entity, 14.0F * flash, WHITE, false, 0.5) : null;
        }
        if (type == EntityType.TNT && entity instanceof PrimedTnt tnt) {
            return tnt.getFuse() / 5 % 2 == 0 ? new Source(entity, 10.0F, WHITE, false, 0.5) : null;
        }
        if (type == EntityType.GLOW_SQUID && entity instanceof GlowSquid squid) {
            return new Source(entity, squid.getDarkTicksRemaining() > 0 ? 2.0F : 8.0F, SCULK, false, 0.5);
        }
        if (type == EntityType.ENDERMAN) {
            return new Source(entity, 4.0F, ENDER, false, 0.9);
        }
        if (type == EntityType.SPIDER || type == EntityType.CAVE_SPIDER) {
            return new Source(entity, 3.0F, REDSTONE, false, 0.6);
        }
        if (type == EntityType.PHANTOM) {
            return new Source(entity, 3.0F, PHANTOM_GREEN, false, 0.5);
        }
        if (type == EntityType.ENDER_DRAGON) {
            return new Source(entity, 9.0F, ENDER, false, 0.5);
        }
        if (type == EntityType.BLAZE) {
            return new Source(entity, 12.0F, WARM, true, 0.6);
        }
        if (type == EntityType.MAGMA_CUBE) {
            return new Source(entity, 8.0F, LAVA, true, 0.5);
        }
        if (type == EntityType.ALLAY || type == EntityType.VEX) {
            return new Source(entity, 6.0F, COOL, false, 0.5);
        }
        if (type == EntityType.FIREBALL || type == EntityType.SMALL_FIREBALL) {
            return new Source(entity, type == EntityType.FIREBALL ? 13.0F : 10.0F, LAVA, true, 0.5);
        }
        if (type == EntityType.DRAGON_FIREBALL || type == EntityType.END_CRYSTAL) {
            return new Source(entity, 12.0F, ENDER, false, 0.5);
        }
        if (type == EntityType.SHULKER_BULLET) {
            return new Source(entity, 6.0F, GOLDEN, false, 0.5);
        }
        if (type == EntityType.EXPERIENCE_ORB) {
            return new Source(entity, 5.0F, EXPERIENCE, false, 0.5);
        }
        if (type == EntityType.FIREWORK_ROCKET) {
            return new Source(entity, 12.0F, GOLDEN, true, 0.5);
        }
        if (type == EntityType.GLOW_ITEM_FRAME) {
            return new Source(entity, 5.0F, COOL, false, 0.5);
        }
        return null;
    }

    static boolean flickers(Vector3f color) {
        return color == WARM || color == SOUL || color == LAVA;
    }

    static Vector3f colorOf(Block block) {
        if (block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH || block == Blocks.SOUL_LANTERN
            || block == Blocks.SOUL_CAMPFIRE || block == Blocks.SOUL_FIRE) {
            return SOUL;
        }
        if (block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH || block == Blocks.REDSTONE_LAMP
            || block == Blocks.REDSTONE_BLOCK || block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) {
            return REDSTONE;
        }
        if (block == Blocks.SEA_LANTERN || block == Blocks.BEACON || block == Blocks.END_ROD || block == Blocks.CONDUIT
            || block == Blocks.SEA_PICKLE || block == Blocks.PEARLESCENT_FROGLIGHT || block == Blocks.VERDANT_FROGLIGHT) {
            return COOL;
        }
        if (block == Blocks.GLOWSTONE || block == Blocks.SHROOMLIGHT || block == Blocks.OCHRE_FROGLIGHT || block == Blocks.JACK_O_LANTERN) {
            return GOLDEN;
        }
        if (block == Blocks.LAVA || block == Blocks.MAGMA_BLOCK || block == Blocks.LAVA_CAULDRON) {
            return LAVA;
        }
        return WARM;
    }

    /** {@code height} is where the light sits as a fraction of the entity's height. */
    private record Source(Entity entity, float level, Vector3f color, boolean flickers, double height) {
    }
}
