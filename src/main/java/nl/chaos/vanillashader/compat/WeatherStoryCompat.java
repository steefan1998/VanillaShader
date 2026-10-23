package nl.chaos.vanillashader.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Reads WeatherStory state without a hard dependency: local cloud cover and storm strength (both 0..1), used
 * to dim the sun-driven effects so they agree with what WeatherStory draws, and its cloud shadow map.
 */
public final class WeatherStoryCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LIGHTING_CLASS = "dev.weatherstory.WeatherLighting";

    private static MethodHandle cover;
    private static MethodHandle storm;
    private static boolean resolved;

    private static @Nullable Field shadowMapField;
    private static MethodHandle currentConfig;
    private static MethodHandle renderDistance;
    private static boolean cloudsResolved;

    private static @Nullable MethodHandle flashUniforms;
    private static boolean flashesResolved;

    private WeatherStoryCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("weatherstory");
    }

    /** Cloud cover at the camera, or {@code -1} if WeatherStory is not available. */
    public static float cover() {
        return call(resolve() ? cover : null);
    }

    /** Storm strength at the camera, or {@code -1} if WeatherStory is not available. */
    public static float storm() {
        return call(resolve() ? storm : null);
    }

    /**
     * WeatherStory's cloud shadow cache: a top-down map (red = sunlight that gets through) of the clouds around
     * the camera, projected along the sun onto y = 0 and covering {@link #cloudShadowRange()} blocks each way.
     * WeatherStory only applies it in the vanilla terrain shader, which Sodium replaces.
     */
    public static @Nullable GpuTextureView cloudShadowMap() {
        if (!resolveClouds()) {
            return null;
        }
        try {
            RenderTarget target = (RenderTarget) shadowMapField.get(null);
            return target == null ? null : target.getColorTextureView();
        } catch (ReflectiveOperationException | ClassCastException e) {
            LOGGER.warn("Could not read WeatherStory's cloud shadow map, cloud shadows disabled", e);
            shadowMapField = null;
            return null;
        }
    }

    /** Half the width of the cloud shadow map in blocks, or {@code -1} if unknown. */
    public static int cloudShadowRange() {
        if (!resolveClouds()) {
            return -1;
        }
        try {
            return (int) renderDistance.invoke(currentConfig.invoke());
        } catch (Throwable t) {
            LOGGER.warn("Could not read WeatherStory's cloud range, cloud shadows disabled", t);
            shadowMapField = null;
            return -1;
        }
    }

    /**
     * Up to four active lightning flashes inside WeatherStory's clouds, packed as {@code x, y, z, brightness}
     * with the position relative to {@code camera} and brightness 0..1; null if unavailable.
     */
    public static float @Nullable [] lightningFlashes(Vec3 camera, float partialTick) {
        if (!flashesResolved) {
            flashesResolved = true;
            if (isLoaded()) {
                try {
                    Class<?> storm = Class.forName("dev.weatherstory.CloudStormClient");
                    flashUniforms = MethodHandles.publicLookup()
                        .findStatic(storm, "flashUniforms", MethodType.methodType(float[].class, Vec3.class, float.class));
                } catch (ReflectiveOperationException e) {
                    LOGGER.warn("WeatherStory's lightning flashes were not found; lightning will not light the world", e);
                }
            }
        }
        if (flashUniforms == null) {
            return null;
        }
        try {
            return (float[]) flashUniforms.invokeExact(camera, partialTick);
        } catch (Throwable t) {
            LOGGER.warn("Could not read WeatherStory's lightning flashes, disabling them", t);
            flashUniforms = null;
            return null;
        }
    }

    private static boolean resolveClouds() {
        if (!cloudsResolved) {
            cloudsResolved = true;
            if (isLoaded()) {
                try {
                    Class<?> atmosphere = Class.forName("dev.weatherstory.CloudAtmosphere");
                    Class<?> config = Class.forName("dev.weatherstory.CloudConfig");
                    Class<?> settings = Class.forName("dev.weatherstory.CloudConfig$Settings");
                    Field field = atmosphere.getDeclaredField("shadowMap");
                    field.setAccessible(true);
                    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                    currentConfig = lookup.findStatic(config, "current", MethodType.methodType(settings));
                    renderDistance = lookup.findVirtual(settings, "renderDistance", MethodType.methodType(int.class));
                    shadowMapField = field;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOGGER.warn("WeatherStory's cloud shadow map was not found; cloud shadows disabled", e);
                }
            }
        }
        return shadowMapField != null;
    }

    private static float call(MethodHandle handle) {
        if (handle == null) {
            return -1.0F;
        }
        try {
            return (float) handle.invokeExact();
        } catch (Throwable t) {
            LOGGER.warn("WeatherStory integration failed, disabling it", t);
            cover = null;
            storm = null;
            return -1.0F;
        }
    }

    private static boolean resolve() {
        if (!resolved) {
            resolved = true;
            if (isLoaded()) {
                try {
                    Class<?> lighting = Class.forName(LIGHTING_CLASS);
                    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                    cover = lookup.findStatic(lighting, "cover", MethodType.methodType(float.class));
                    storm = lookup.findStatic(lighting, "storm", MethodType.methodType(float.class));
                    LOGGER.info("WeatherStory detected, sun effects follow its cloud cover and storms");
                } catch (ReflectiveOperationException e) {
                    LOGGER.warn("WeatherStory is installed but its lighting API was not found; weather integration disabled", e);
                }
            }
        }
        return cover != null;
    }
}
