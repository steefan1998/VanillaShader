package nl.chaos.vanillashader.client;

import com.mojang.blaze3d.buffers.Std140Builder;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import nl.chaos.vanillashader.compat.WeatherStoryCompat;
import nl.chaos.vanillashader.config.ShaderConfig;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Per-frame values shared by every pass, laid out as the std140 {@code Frame} block in
 * {@code shaders/include/frame.glsl}. Keep both in sync.
 */
final class FrameState {
    static final int SIZE = 3 * 64 + (20 + DynamicLights.MAX_LIGHTS * 2) * 16;
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
    private Vec3 cameraPos = Vec3.ZERO;
    private FogType fogType = FogType.NONE;
    private final Vector4f fogColor = new Vector4f();
    private float fogEnd = 256.0F;
    private final Vector4f fogParams = new Vector4f();
    private final DynamicLights dynamicLights = new DynamicLights();
    private final Vector4f misc = new Vector4f();
    private DimensionType.Skybox skybox = DimensionType.Skybox.NONE;
    private float sunAngle;
    private float moonAngle;
    private int skyColor;

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

    void capture(LevelRenderState state) {
        CameraRenderState cam = state.cameraRenderState;
        projection.set(cam.projectionMatrix);
        viewRotation.set(cam.viewRotationMatrix);
        cameraPos = cam.pos;
        fogType = cam.fogType;
        fogColor.set(cam.fogData.color);
        // The nearer of render-distance fog and environmental fog (which WeatherStory pulls in for mist and sand).
        fogEnd = Math.max(8.0F, Math.min(cam.fogData.renderDistanceEnd, cam.fogData.environmentalEnd));
        fogParams.set(cam.fogData.environmentalStart, cam.fogData.environmentalEnd, cam.fogData.renderDistanceStart, cam.fogData.renderDistanceEnd);
        SkyRenderState skyState = state.skyRenderState;
        skybox = skyState.skybox;
        sunAngle = skyState.sunAngle;
        moonAngle = skyState.moonAngle;
        skyColor = skyState.skyColor;
    }

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
        boolean overworld = skybox == DimensionType.Skybox.OVERWORLD;

        projection.invert(inverseProjection);
        viewRotation.invert(inverseView);
        screenSize.set(width, height, 1.0F / width, 1.0F / height);

        // Same rotation the sky renderer applies to the sun and moon quads.
        Vector3f sunWorld = new Quaternionf().rotationY((float) Math.toRadians(-90.0)).rotateX(sunAngle).transform(new Vector3f(0, 1, 0));
        Vector3f moonWorld = new Quaternionf().rotationY((float) Math.toRadians(-90.0)).rotateX(moonAngle).transform(new Vector3f(0, 1, 0));
        float dayFactor = Mth.clamp(sunWorld.y * 5.0F + 0.5F, 0.0F, 1.0F);
        float sunset = (1.0F - smoothstep(0.0F, 0.4F, sunWorld.y)) * smoothstep(-0.15F, 0.0F, sunWorld.y);

        boolean useSun = sunWorld.y > -0.05F || moonWorld.y < 0.0F;
        Vector3f lightWorld = useSun ? sunWorld : moonWorld;
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

        float underwater = fogType == FogType.WATER ? 1.0F : fogType == FogType.NONE || fogType == FogType.ATMOSPHERIC ? 0.0F : 2.0F;
        if (!overworld || underwater > 1.5F) {
            intensity = 0.0F;
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
        Vector3f color = useSun
            ? new Vector3f(1.0F, 0.94F, 0.84F).lerp(new Vector3f(1.0F, 0.52F, 0.22F), sunset)
            : new Vector3f(0.55F, 0.65F, 1.0F);
        lightColor.set(color.x, color.y, color.z, intensity);

        sky.set(ARGB.redFloat(skyColor), ARGB.greenFloat(skyColor), ARGB.blueFloat(skyColor), dayFactor);

        float skyLight = 1.0F;
        if (minecraft.gameRenderer.getMainCamera() != null) {
            skyLight = level.getBrightness(LightLayer.SKY, minecraft.gameRenderer.getMainCamera().blockPosition()) / 15.0F;
        }
        smoothedSkyLight = Mth.lerp(0.05F, smoothedSkyLight, skyLight);
        horizon.set(fogColor.x, fogColor.y, fogColor.z, overworld ? smoothedSkyLight : 0.0F);

        float time = (float) (((System.nanoTime() - startNanos) / 1.0E9) % 3600.0);
        camera.set((float) (cameraPos.x % 4096.0), (float) cameraPos.y, (float) (cameraPos.z % 4096.0), time);
        weather.set(rain, overcast, sunset, underwater);

        quality0.set(AO_SAMPLES[effects.ambientOcclusion()], SSR_STEPS[effects.reflections()], SSR_REFINE[effects.reflections()], RAY_SAMPLES[effects.godRays()]);
        quality1.set(SHADOW_STEPS[effects.shadows()], effects.bloom(), fogEnd, overworld ? 1.0F : 0.0F);
        look0.set(
            ShaderConfig.EXPOSURE.get() / 100.0F,
            ShaderConfig.SATURATION.get() / 100.0F,
            ShaderConfig.CONTRAST.get() / 100.0F,
            ShaderConfig.WARMTH.get() / 100.0F
        );
        look1.set(
            ShaderConfig.ATMOSPHERE.get() / 100.0F,
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

        misc.set(maxTerrainY, dynamicLights.count(), 0.0F, 0.0F);

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

    void write(Std140Builder builder) {
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
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
}
