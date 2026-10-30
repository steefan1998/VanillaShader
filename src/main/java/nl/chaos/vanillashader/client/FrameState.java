package nl.chaos.vanillashader.client;

import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
//? if >=26.1 {
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.dimension.DimensionType;
//?} else {
/*import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.DimensionSpecialEffects;
*///?}
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import nl.chaos.vanillashader.compat.DistantHorizonsCompat;
import nl.chaos.vanillashader.compat.McCompat;
import nl.chaos.vanillashader.compat.WeatherStoryCompat;
import nl.chaos.vanillashader.config.ShaderConfig;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Per-frame values shared by every pass, laid out as the std140 {@code Frame} block in
 * {@code shaders/include/frame.glsl}. Keep both in sync.
 */
final class FrameState {
    static final int SIZE = 4 * 64 + (23 + DynamicLights.MAX_LIGHTS * 2) * 16;
    static final int MAX_FLASHES = 4;

    private static final int[] AO_SAMPLES = {0, 4, 8, 10, 14};
    private static final int[] SSR_STEPS = {-1, 0, 16, 32, 56};
    private static final int[] SSR_REFINE = {0, 0, 3, 5, 6};
    private static final int[] RAY_SAMPLES = {0, 16, 28, 44, 64};
    private static final int[] SHADOW_STEPS = {0, 12, 20, 32, 48};

    private final long startNanos = System.nanoTime();

    // Captured mid-frame, while the level render state is still complete.
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f viewRotation = new Matrix4f();
    private final Matrix4f levelProjection = new Matrix4f();
    private boolean levelProjectionValid;
    private Vec3 cameraPos = Vec3.ZERO;
    private FogType fogType = FogType.NONE;
    private final Vector4f fogColor = new Vector4f();
    private float fogEnd = 256.0F;
    private final Vector4f fogParams = new Vector4f();
    private final DynamicLights dynamicLights = new DynamicLights();
    private final Vector4f misc = new Vector4f();
    private final Vector4f rayTracing = new Vector4f();
    private final Vector4f dhParams = new Vector4f();
    // x = rainbow, y/z = Distant Horizons fade distances, w = cloud height (frame.glsl's Misc2).
    private final Vector4f misc2 = new Vector4f();
    private float cloudHeight = 192.0F;
    private final Matrix4f dhInverseProjection = new Matrix4f();
    private static final int[] RT_RAYS = {2, 2, 3, 4, 6};
    private boolean endSky;
    private float sunAngle;
    private float endFlashIntensity;
    private float endFlashXAngle;
    private float endFlashYAngle;
    private float moonAngle;
    private final Vector3f skyColor = new Vector3f();

    // Derived in update().
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f inverseView = new Matrix4f();
    private final Vector4f screenSize = new Vector4f();
    private final Vector4f lightDir = new Vector4f();
    private final Vector4f lightScreen = new Vector4f();
    private final Vector4f lightColor = new Vector4f();
    private final Vector4f sky = new Vector4f();
    private final Vector4f horizon = new Vector4f();
    private final Vector4f camera = new Vector4f();
    private final Vector4f weather = new Vector4f();
    private final Vector4f quality0 = new Vector4f();
    private final Vector4f quality1 = new Vector4f();
    private final Vector4f look0 = new Vector4f();
    private final Vector4f look1 = new Vector4f();
    private final Vector4f depthParams = new Vector4f();
    private final Vector4f cloudParams = new Vector4f();
    private final float[] flashes = new float[MAX_FLASHES * 4];
    private float smoothedSkyLight = 1.0F;
    private float frameCounter;
    // How wet the world is: builds up while it rains where the camera is and dries slowly afterwards.
    private float wetness;
    private long lastWetnessNanos = System.nanoTime();
    private @Nullable ResourceKey<Level> airlessDimension;
    private float airlessState;

    void setLevelProjection(Matrix4fc projection) {
        levelProjection.set(projection);
        levelProjectionValid = true;
    }

    //? if >=26.1 {
    void capture(LevelRenderState state) {
        CameraRenderState cam = state.cameraRenderState;
        // Prefer the projection actually used this frame (with view bobbing); fall back to the plain camera one.
        projection.set(levelProjectionValid ? levelProjection : cam.projectionMatrix);
        levelProjectionValid = false;
        viewRotation.set(cam.viewRotationMatrix);
        cameraPos = cam.pos;
        fogType = cam.fogType;
        fogColor.set(cam.fogData.color);
        // The nearer of render-distance fog and environmental fog (which WeatherStory pulls in for mist and sand).
        fogEnd = Math.max(8.0F, Math.min(cam.fogData.renderDistanceEnd, cam.fogData.environmentalEnd));
        fogParams.set(cam.fogData.environmentalStart, cam.fogData.environmentalEnd, cam.fogData.renderDistanceStart, cam.fogData.renderDistanceEnd);
        SkyRenderState skyState = state.skyRenderState;
        endSky = skyState.skybox == DimensionType.Skybox.END;
        sunAngle = skyState.sunAngle;
        moonAngle = skyState.moonAngle;
        endFlashIntensity = skyState.endFlashIntensity;
        endFlashXAngle = skyState.endFlashXAngle;
        endFlashYAngle = skyState.endFlashYAngle;
        //? if >=26.3 {
        /*// Null in skies without one (the End) and before the sky is first set up; black, like the int it replaced.
        if (skyState.skyColor != null) {
            skyColor.set(skyState.skyColor);
        } else {
            skyColor.set(0.0F);
        }
        *///?} else
        skyColor.set(ARGB.redFloat(skyState.skyColor), ARGB.greenFloat(skyState.skyColor), ARGB.blueFloat(skyState.skyColor));
        cloudHeight = state.cloudHeight;
    }
    //?} else {
    /*// 1.21.1 has no level render state: read the camera, the fog Minecraft set up for the terrain, and the level.
    void capture(Camera camera, Matrix4fc cameraProjection, Matrix4fc viewRotationMatrix, ClientLevel level, float partialTick) {
        projection.set(levelProjectionValid ? levelProjection : cameraProjection);
        levelProjectionValid = false;
        viewRotation.set(viewRotationMatrix);
        cameraPos = camera.getPosition();
        fogType = camera.getFluidInCamera();
        float[] fog = RenderSystem.getShaderFogColor();
        fogColor.set(fog[0], fog[1], fog[2], fog[3]);
        // 1.21.1 has one fog range for everything.
        float fogStart = RenderSystem.getShaderFogStart();
        float fogFar = RenderSystem.getShaderFogEnd();
        fogEnd = Math.max(8.0F, fogFar);
        fogParams.set(fogStart, fogFar, fogStart, fogFar);
        endSky = level.effects().skyType() == DimensionSpecialEffects.SkyType.END;
        sunAngle = level.getSunAngle(partialTick);
        moonAngle = sunAngle + Mth.PI;
        // The End only flashes from 1.21.9.
        endFlashIntensity = 0.0F;
        cloudHeight = level.effects().getCloudHeight();
        Vec3 color = level.getSkyColor(cameraPos, partialTick);
        skyColor.set((float) color.x, (float) color.y, (float) color.z);
    }
    *///?}

    /** Fog much nearer than the render distance: WeatherStory mist or sand, so its wisps may be on screen. */
    boolean fogIsClose() {
        return fogEnd < 0.6F * Math.max(16.0F, fogParams.w);
    }

    boolean anyPointLights() {
        if (dynamicLights.count() > 0) {
            return true;
        }
        for (int i = 0; i < MAX_FLASHES; i++) {
            if (flashes[i * 4 + 3] > 0.0F) {
                return true;
            }
        }
        return false;
    }

    boolean sunOnScreen() {
        return lightScreen.z > 0.01F && lightColor.w > 0.01F;
    }

    void update(Minecraft minecraft, ShaderConfig.Effects effects, int width, int height, boolean hasWaterMask, boolean zZeroToOne, int minY, int maxTerrainY, int cloudShadowRange) {
        ClientLevel level = minecraft.level;
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        // Sun effects in every dimension with daylight, modded ones included (Aether, Stellaris planets, ...), not just
        // the Overworld. The Nether and the End have no sky light.
        boolean overworld = level.dimensionType().hasSkyLight() && !endSky;

        projection.invert(inverseProjection);
        viewRotation.invert(inverseView);
        screenSize.set(width, height, 1.0F / width, 1.0F / height);

        // Same rotation the sky renderer applies to the sun and moon quads.
        Vector3f sunWorld = new Quaternionf().rotationY((float) Math.toRadians(-90.0)).rotateX(sunAngle).transform(new Vector3f(0, 1, 0));
        Vector3f moonWorld = new Quaternionf().rotationY((float) Math.toRadians(-90.0)).rotateX(moonAngle).transform(new Vector3f(0, 1, 0));
        float dayFactor = Mth.clamp(sunWorld.y * 5.0F + 0.5F, 0.0F, 1.0F);
        float sunset = (1.0F - smoothstep(0.1F, 0.7F, sunWorld.y)) * smoothstep(-0.25F, -0.02F, sunWorld.y);

        boolean useSun = sunWorld.y > -0.05F || moonWorld.y < 0.0F;
        Vector3f lightWorld = useSun ? sunWorld : moonWorld;
        boolean endFlash = false;
        float intensity = useSun
            ? Mth.clamp(sunWorld.y * 4.0F + 0.35F, 0.0F, 1.0F)
            : Mth.clamp(moonWorld.y * 2.0F, 0.0F, 1.0F) * 0.3F;

        float rain = level.getRainLevel(partialTick);
        float thunder = level.getThunderLevel(partialTick);
        float cover = -1.0F;
        float storm = -1.0F;
        if (ShaderConfig.WEATHER_INTEGRATION.get()) {
            cover = WeatherStoryCompat.cover();
            storm = WeatherStoryCompat.storm();
        }
        float overcast = Math.max(rain, thunder);
        if (cover >= 0.0F) {
            overcast = Math.max(overcast, cover * 0.9F);
        }
        if (storm >= 0.0F) {
            overcast = Math.max(overcast, storm);
        }
        overcast = Mth.clamp(overcast, 0.0F, 1.0F);
        // With cloud shadows the clouds already block the sun where they are, so partial cover must not dim it everywhere.
        float sunBlock = cloudShadowRange > 0 ? Math.max(Math.max(rain, thunder), Math.max(storm, 0.0F)) : overcast;
        intensity *= 1.0F - 0.85F * sunBlock;

        //? if >=26.1 {
        boolean clearAir = fogType == FogType.NONE || fogType == FogType.ATMOSPHERIC;
        //?} else
        /*boolean clearAir = fogType == FogType.NONE;*/
        float underwater = fogType == FogType.WATER ? 1.0F : clearAir ? 0.0F : 2.0F;
        if (!overworld || underwater > 1.5F) {
            intensity = 0.0F;
            sunset = 0.0F;
        }
        // A sun but no air (the moon and planets of Stellaris and the like): no golden hour and no haze.
        float airless = airless(level, overworld, dayFactor);
        sunset *= 1.0F - airless;
        // In the End, the sky flashes now and then: light (and shadows, god rays) from that direction while it lasts.
        if (endSky && endFlashIntensity > 0.01F && underwater < 1.5F) {
            endFlash = true;
            useSun = true;
            lightWorld = new Quaternionf()
                .rotationY((float) Math.toRadians(180.0F - endFlashYAngle))
                .rotateX((float) Math.toRadians(-90.0F - endFlashXAngle))
                .transform(new Vector3f(0, 1, 0));
            intensity = Mth.clamp(endFlashIntensity, 0.0F, 1.0F) * 0.9F;
            sunset = 0.0F;
        }

        Vector3f lightView = viewRotation.transformDirection(new Vector3f(lightWorld)).normalize();
        lightDir.set(lightView.x, lightView.y, lightView.z, intensity);

        Vector4f clip = projection.transform(new Vector4f(lightView.x * 100.0F, lightView.y * 100.0F, lightView.z * 100.0F, 1.0F));
        float onScreen = 0.0F;
        float u = 0.5F;
        float v = 0.5F;
        if (clip.w > 0.0F) {
            float ndcX = clip.x / clip.w;
            float ndcY = clip.y / clip.w;
            u = ndcX * 0.5F + 0.5F;
            v = ndcY * 0.5F + 0.5F;
            onScreen = 1.0F - smoothstep(1.0F, 1.7F, Math.max(Math.abs(ndcX), Math.abs(ndcY)));
        }
        if (!useSun || underwater > 0.5F) {
            onScreen *= 0.35F;
        }
        lightScreen.set(u, v, onScreen, (float) width / height);

        // Warm white at noon, orange at sunrise/sunset, cool blue for the moon.
        Vector3f color = endFlash
            ? new Vector3f(0.78F, 0.55F, 1.0F)
            : useSun
                ? new Vector3f(1.0F, 0.94F, 0.84F).lerp(new Vector3f(1.0F, 0.52F, 0.22F), sunset)
                : new Vector3f(0.55F, 0.65F, 1.0F);
        lightColor.set(color.x, color.y, color.z, intensity);

        // Day factor only means something with a sun; elsewhere keep it at "day" so no night tint is applied.
        sky.set(skyColor.x, skyColor.y, skyColor.z, overworld ? dayFactor : 1.0F);

        float skyLight = 1.0F;
        if (McCompat.camera() != null) {
            skyLight = level.getBrightness(LightLayer.SKY, BlockPos.containing(McCompat.cameraPosition())) / 15.0F;
        }
        smoothedSkyLight = Mth.lerp(0.05F, smoothedSkyLight, skyLight);
        horizon.set(fogColor.x, fogColor.y, fogColor.z, overworld ? smoothedSkyLight : 0.0F);

        float time = (float) (((System.nanoTime() - startNanos) / 1.0E9) % 3600.0);
        camera.set((float) (cameraPos.x % 4096.0), (float) cameraPos.y, (float) (cameraPos.z % 4096.0), time);
        weather.set(rain, overcast, sunset, underwater);

        quality0.set(AO_SAMPLES[effects.ambientOcclusion()], SSR_STEPS[effects.reflections()], SSR_REFINE[effects.reflections()], RAY_SAMPLES[effects.godRays()]);
        quality1.set(SHADOW_STEPS[effects.shadows()], effects.bloom(), fogEnd, overworld || endFlash ? 1.0F : 0.0F);
        look0.set(
            ShaderConfig.EXPOSURE.get() / 100.0F,
            ShaderConfig.SATURATION.get() / 100.0F,
            ShaderConfig.CONTRAST.get() / 100.0F,
            ShaderConfig.WARMTH.get() / 100.0F
        );
        look1.set(
            ShaderConfig.ATMOSPHERE.get() / 100.0F * (1.0F - airless),
            ShaderConfig.WATER_REFLECTIVITY.get() / 100.0F,
            ShaderConfig.VIGNETTE.get() / 100.0F,
            hasWaterMask ? 1.0F : 0.0F
        );
        frameCounter = (frameCounter + 1.0F) % 1024.0F;
        // ndcZ = depth * x + y
        depthParams.set(zZeroToOne ? 1.0F : 2.0F, zZeroToOne ? 0.0F : -1.0F, frameCounter, minY);
        // WeatherStory projects its cloud shadow map along (-sin a, max(0.15, cos a), 0).
        cloudParams.set(cloudShadowRange, Math.max(storm, 0.0F), (float) -Math.sin(sunAngle), Math.max(0.15F, (float) Math.cos(sunAngle)));

        Arrays.fill(flashes, 0.0F);
        float[] stormFlashes = ShaderConfig.WEATHER_INTEGRATION.get() ? WeatherStoryCompat.lightningFlashes(cameraPos, partialTick) : null;
        if (stormFlashes != null) {
            System.arraycopy(stormFlashes, 0, flashes, 0, Math.min(stormFlashes.length, flashes.length));
        }
        if (effects.enabled() && ShaderConfig.DYNAMIC_LIGHTS.get()) {
            dynamicLights.collect(level, cameraPos, partialTick, time);
        } else {
            dynamicLights.collect(null, cameraPos, partialTick, time);
        }

        updateWetness(level, rain);
        misc.set(maxTerrainY, dynamicLights.count(), wetness, brightSky(level, overworld));
        // A rainbow needs sunlight on falling or just-fallen rain: the world wet, the rain light or over, and the sun
        // low enough that the bow (42 degrees around the point opposite it) is above the horizon.
        float bow = 0.0F;
        if (useSun && !endFlash && overworld && underwater < 0.5F) {
            float wet = smoothstep(0.15F, 0.5F, wetness) * (1.0F - smoothstep(0.5F, 0.9F, rain)) * (1.0F - thunder);
            float sunHeight = smoothstep(-0.02F, 0.08F, sunWorld.y) * (1.0F - smoothstep(0.55F, 0.7F, sunWorld.y));
            // A WeatherStory storm overhead hides the sun.
            bow = wet * sunHeight * (1.0F - Math.max(storm, 0.0F));
        }
        misc2.x = bow;
        // No clouds (NaN, e.g. the Nether): nothing is too high to be terrain.
        misc2.w = Float.isFinite(cloudHeight) ? cloudHeight : 1.0E6F;
        // x = on, y = rays per pixel (follows the profile), z = how far rays travel in blocks.
        boolean tracing = effects.enabled() && ShaderConfig.rayTracing();
        rayTracing.set(tracing ? 1.0F : 0.0F, RT_RAYS[Math.max(effects.reflections(), 0)], 28.0F, 0.0F);

        // Vanilla lightning bolts (also the ones WeatherStory strikes on the ground) fill the remaining slots.
        int slot = 0;
        while (slot < MAX_FLASHES && flashes[slot * 4 + 3] > 0.0F) {
            slot++;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (slot >= MAX_FLASHES) {
                break;
            }
            if (entity instanceof LightningBolt) {
                Vec3 pos = entity.position();
                flashes[slot * 4] = (float) (pos.x - cameraPos.x);
                flashes[slot * 4 + 1] = (float) (pos.y + 24.0 - cameraPos.y);
                flashes[slot * 4 + 2] = (float) (pos.z - cameraPos.z);
                flashes[slot * 4 + 3] = 1.0F;
                slot++;
            }
        }
    }

    void write(Std140 builder) {
        builder.putMat4f(projection)
            .putMat4f(inverseProjection)
            .putMat4f(inverseView)
            .putVec4(screenSize)
            .putVec4(lightDir)
            .putVec4(lightScreen)
            .putVec4(lightColor)
            .putVec4(sky)
            .putVec4(horizon)
            .putVec4(camera)
            .putVec4(weather)
            .putVec4(quality0)
            .putVec4(quality1)
            .putVec4(look0)
            .putVec4(look1)
            .putVec4(depthParams)
            .putVec4(cloudParams)
            .putVec4(fogParams);
        for (int i = 0; i < MAX_FLASHES; i++) {
            builder.putVec4(flashes[i * 4], flashes[i * 4 + 1], flashes[i * 4 + 2], flashes[i * 4 + 3]);
        }
        for (int i = 0; i < DynamicLights.MAX_LIGHTS; i++) {
            boolean used = i < dynamicLights.count();
            builder.putVec4(used ? dynamicLights.get(i, 0) : 0.0F, used ? dynamicLights.get(i, 1) : 0.0F, used ? dynamicLights.get(i, 2) : 0.0F, used ? dynamicLights.get(i, 3) : 1.0F);
        }
        for (int i = 0; i < DynamicLights.MAX_LIGHTS; i++) {
            boolean used = i < dynamicLights.count();
            builder.putVec4(used ? dynamicLights.get(i, 4) : 0.0F, used ? dynamicLights.get(i, 5) : 0.0F, used ? dynamicLights.get(i, 6) : 0.0F, used ? dynamicLights.get(i, 7) : 0.0F);
        }
        builder.putVec4(misc);
        builder.putVec4(rayTracing);
        builder.putVec4(dhParams);
        builder.putVec4(misc2);
        builder.putMat4f(dhInverseProjection);
    }

    /**
     * Distant Horizons' depth conventions for this frame, or null when it did not draw. With {@code shadeTerrain} off,
     * DH's terrain is left alone and our effects fade out over the outer part of the render distance (in blocks),
     * where the chunks meet it; on, DH's terrain is shaded too and only the shadows hand over there.
     */
    void setDistantHorizons(DistantHorizonsCompat.@Nullable Frame dh, float renderDistance, boolean shadeTerrain) {
        if (dh == null) {
            dhParams.set(0.0F);
            dhInverseProjection.identity();
            misc2.y = 0.0F;
            misc2.z = 0.0F;
        } else {
            dhParams.set(dh.ndcScale(), dh.ndcOffset(), dh.reversedZ() ? 1.0F : 0.0F, shadeTerrain ? 2.0F : 1.0F);
            dhInverseProjection.set(dh.inverseProjection());
            // Where the chunks hand over to DH: our effects fade out there, or (shading DH too) the shadows give way to
            // the lighting from facing that DH's terrain gets.
            misc2.y = renderDistance * 0.6F;
            misc2.z = renderDistance * 0.95F;
        }
    }

    // A modded dimension whose sky stays (nearly) black while its sun is up has no atmosphere to scatter light: the
    // golden hour colouring and the haze would paint a starry sky amber. 1 = airless. Judged while the sun is well
    // up (every sky is dark at night) and remembered per dimension through sunset and the night.
    private float airless(ClientLevel level, boolean daylight, float dayFactor) {
        if (!daylight || level.dimension() == Level.OVERWORLD) {
            return 0.0F;
        }
        if (level.dimension() != airlessDimension) {
            airlessDimension = level.dimension();
            airlessState = 0.0F;
        }
        if (dayFactor > 0.6F) {
            float skyLuma = 0.2126F * skyColor.x + 0.7152F * skyColor.y + 0.0722F * skyColor.z;
            airlessState = 1.0F - smoothstep(0.03F, 0.12F, skyLuma);
        }
        return airlessState;
    }

    // Modded sky dimensions with an almost white sky and fog (The Aether II and the like): the haze, bloom and exposure
    // tuned for the Overworld's blue-grey air wash those out to white, so they are toned down there. 0 = not at all.
    private float brightSky(ClientLevel level, boolean daylight) {
        if (!daylight || level.dimension() == Level.OVERWORLD) {
            return 0.0F;
        }
        float fogLuma = 0.2126F * fogColor.x + 0.7152F * fogColor.y + 0.0722F * fogColor.z;
        return smoothstep(0.72F, 0.92F, fogLuma);
    }

    // Only rain makes things wet: not snow, not deserts, not the Nether. Checked on top of the camera's column, so
    // standing under a roof still sees the street outside get wet. About 20 s to soak, a minute to dry.
    private void updateWetness(ClientLevel level, float rain) {
        long now = System.nanoTime();
        float seconds = Mth.clamp((now - lastWetnessNanos) / 1.0E9F, 0.0F, 0.25F);
        lastWetnessNanos = now;
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(cameraPos));
        float target = rain > 0.0F && level.isRainingAt(top) ? rain : 0.0F;
        wetness = target > wetness
            ? Math.min(target, wetness + seconds / 20.0F)
            : Math.max(target, wetness - seconds / 60.0F);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
}
