package nl.chaos.vanillashader.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
//? if >=26.1 {
import net.minecraft.client.renderer.LevelTargetBundle;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
//?}
import net.neoforged.neoforge.common.NeoForge;
import nl.chaos.vanillashader.client.gpu.DepthTexture;
import nl.chaos.vanillashader.client.gpu.Gpu;
import nl.chaos.vanillashader.client.gpu.Gpu.Input;
import nl.chaos.vanillashader.client.gpu.PostPipeline;
import nl.chaos.vanillashader.client.gpu.PostTexture;
import nl.chaos.vanillashader.client.gpu.TexRef;
import nl.chaos.vanillashader.client.gpu.UniformBlock;
import nl.chaos.vanillashader.compat.IrisCompat;
import nl.chaos.vanillashader.compat.McCompat;
import nl.chaos.vanillashader.compat.WeatherStoryCompat;
import nl.chaos.vanillashader.config.ShaderConfig;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Screen-space post-processing that runs right after the level is drawn (before the hand and GUI).
 *
 * <p>Two depth snapshots are taken while the level renders: one after opaque geometry and one after
 * translucent terrain. Where the second is closer than the first, something translucent (water, ice,
 * glass) sits in front, which is how water gets its reflections without touching the terrain shaders
 * that Sodium and WeatherStory modify.
 *
 * <p>Passes: lighting (AO + sun/cloud shadows, half res) and point lights (torches, lightning, half res) -> composite
 * (depth-aware upsample of both, water, atmosphere)
 * -> bloom (quarter + eighth res) and god rays (half res) -> final grade, written back to the main target.
 *
 * <p>The GPU work goes through {@code client.gpu}, which has one implementation on Minecraft's GPU API (26.1+) and
 * one on plain OpenGL (1.21.1).
 */
public final class ShaderRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final PostPipeline LIGHTING = pipeline("lighting", "Depth", "ShadowMap", "CloudShadow");
    private static final PostPipeline POINT_LIGHTS = pipeline("point_lights", "Depth", "ShadowMap");
    private static final PostPipeline WATER = pipeline("water", "Scene", "Depth", "OpaqueDepth", "ShadowMap");
    private static final PostPipeline DENOISE = pipeline("denoise", "In", "Guide");
    private static final PostPipeline RT_LIGHT = pipeline("rt_light", "Depth", "Volume");
    private static final PostPipeline RT_REFLECT = pipeline("rt_reflect", "Scene", "Depth", "Volume");
    private static final PostPipeline COMPOSITE = pipeline("composite", "Scene", "Depth", "OpaqueDepth", "Lighting", "PointLights", "Water", "OpaqueColor", "FeatureColor", "ShadowMap", "RtLight", "RtReflect");
    private static final PostPipeline BRIGHT = pipeline("bloom_bright", "In");
    private static final PostPipeline BLUR = pipeline("blur", "In");
    private static final PostPipeline GOD_RAYS = pipeline("god_rays", "Scene", "Depth");
    private static final PostPipeline FINAL = pipeline("final", "Lit", "BloomNear", "BloomFar", "Rays");
    private static final PostPipeline FSR_EASU = pipeline("fsr_easu", "In");
    private static final PostPipeline FSR_RCAS = pipeline("fsr_rcas", "In");

    private static final Texture LIT = new Texture("lit", 1);
    private static final Texture LIGHTING_TEX = new Texture("lighting", 2);
    private static final Texture POINT_LIGHTS_TEX = new Texture("point_lights", 2);
    private static final Texture WATER_TEX = new Texture("water", 2);
    private static final Texture LIGHTING_TMP = new Texture("lighting_tmp", 2);
    private static final Texture POINT_LIGHTS_TMP = new Texture("point_lights_tmp", 2);
    private static final Texture RAYS_TMP = new Texture("rays_tmp", 2);
    private static final Texture RT_LIGHT_TEX = new Texture("rt_light", 2);
    private static final Texture RT_LIGHT_TMP = new Texture("rt_light_tmp", 2);
    private static final Texture RT_REFLECT_TEX = new Texture("rt_reflect", 2);
    private static final Texture RAYS = new Texture("rays", 2);
    private static final Texture BLOOM_NEAR = new Texture("bloom_near", 4);
    private static final Texture BLOOM_NEAR_TMP = new Texture("bloom_near_tmp", 4);
    private static final Texture BLOOM_FAR = new Texture("bloom_far", 8);
    private static final Texture BLOOM_FAR_TMP = new Texture("bloom_far_tmp", 8);
    private static final Texture BLACK = new Texture("black", 0);
    private static final PostTexture UPSCALED = new PostTexture("fsr_upscaled");
    // The level after opaque geometry and after translucent features: their difference shows WeatherStory's mist
    // and sand wisps (drawn without depth), so lighting can be kept off the veil in front of the terrain.
    private static final PostTexture OPAQUE_COLOR = new PostTexture("opaque_color");
    private static final PostTexture FEATURE_COLOR = new PostTexture("feature_color");
    private static final List<Texture> TEXTURES = List.of(LIT, LIGHTING_TEX, POINT_LIGHTS_TEX, WATER_TEX, LIGHTING_TMP, POINT_LIGHTS_TMP, RAYS_TMP, RT_LIGHT_TEX, RT_LIGHT_TMP, RT_REFLECT_TEX, RAYS, BLOOM_NEAR, BLOOM_NEAR_TMP, BLOOM_FAR, BLOOM_FAR_TMP, BLACK);
    private static final DepthTexture OPAQUE_DEPTH = new DepthTexture("opaque_depth");
    private static final DepthTexture FULL_DEPTH = new DepthTexture("full_depth");
    //? if >=26.3 {
    /*private static final OitDepth OIT_DEPTH = new OitDepth();
    private static boolean oitCaptured;
    *///?}

    private static final FrameState FRAME = new FrameState();
    private static final ShadowHeightmap SHADOW_MAP = new ShadowHeightmap();
    private static final MaterialVolume VOLUME = new MaterialVolume();
    private static @Nullable UniformBlock frameUniforms;
    private static @Nullable UniformBlock blurHorizontal;
    private static @Nullable UniformBlock blurVertical;
    // Denoise directions that leave the packed distances in BA untouched (the plain blur ones blur all channels).
    private static @Nullable UniformBlock denoiseHorizontalKeep;
    private static @Nullable UniformBlock denoiseVerticalKeep;
    private static @Nullable UniformBlock fsrConfig;
    private static int fsrSharpness = -1;
    //? if >=26.1
    private static @Nullable LevelTargetBundle targets;
    private static boolean opaqueCaptured;
    private static boolean fullCaptured;
    private static boolean opaqueColorCaptured;
    private static boolean featureColorCaptured;
    private static boolean failed;
    private static final Set<String> BROKEN = new HashSet<>();

    private ShaderRenderer() {
    }

    public static void init() {
        //? if >=26.1 {
        NeoForge.EVENT_BUS.addListener(ShaderRenderer::onFrameGraphSetup);
        NeoForge.EVENT_BUS.addListener(ShaderRenderer::afterOpaqueFeatures);
        NeoForge.EVENT_BUS.addListener(ShaderRenderer::afterTranslucentFeatures);
        NeoForge.EVENT_BUS.addListener(ShaderRenderer::afterTranslucentBlocks);
        NeoForge.EVENT_BUS.addListener(ShaderRenderer::afterLevel);
        //?} else
        /*NeoForge.EVENT_BUS.addListener(ShaderRenderer::onRenderStage);*/
        RenderScaler.init();
    }

    /** Called from the GameRenderer mixin with the projection the level is drawn with this frame. */
    public static void setLevelProjection(Matrix4fc projection) {
        FRAME.setLevelProjection(projection);
    }

    private static PostPipeline pipeline(String name, String... samplers) {
        return PostPipeline.create(name, uniformBlock(name), samplers);
    }

    // The blur only needs its direction, RCAS its sharpness and EASU nothing; every other pass reads the frame block.
    static @Nullable String uniformBlock(String pipeline) {
        return switch (pipeline) {
            case "blur" -> "BlurConfig";
            case "fsr_rcas" -> "FsrConfig";
            case "denoise" -> "DenoiseConfig";
            case "fsr_easu" -> null;
            default -> "Frame";
        };
    }

    static boolean active() {
        return !failed && ShaderConfig.effects().enabled() && !IrisCompat.shaderPackInUse() && Minecraft.getInstance().level != null;
    }

    /** Whether this frame needs the snapshot after opaque geometry (LevelRendererMixin splits a render pass for it). */
    public static boolean wantsOpaqueSnapshot() {
        return active();
    }

    /** Snapshots the level after opaque geometry: its depth (for the water mask) and, in close fog, its colour. */
    public static void captureOpaque() {
        if (!active()) {
            return;
        }
        RenderTarget main = McCompat.mainTarget();
        opaqueCaptured = OPAQUE_DEPTH.copyFrom(main);
        // Mist and sand wisps only show up when fog is close; otherwise skip the two full-screen copies. Skipped from 26.3,
        // where they would need yet another pass split and WeatherStory (whose mist this is for) does not exist.
        //? if <26.3
        opaqueColorCaptured = FRAME.fogIsClose() && OPAQUE_COLOR.copyColorFrom(main);
    }

    /** Snapshots the depth with translucent terrain (from 26.3 called by LevelRendererMixin, between render passes). */
    public static void captureTranslucent() {
        if (active()) {
            fullCaptured = FULL_DEPTH.copyFrom(McCompat.mainTarget());
        }
    }

    private static void startLevel() {
        opaqueCaptured = false;
        fullCaptured = false;
        opaqueColorCaptured = false;
        featureColorCaptured = false;
        //? if >=26.3
        /*oitCaptured = false;*/
    }

    //? if >=26.1 {
    private static void onFrameGraphSetup(FrameGraphSetupEvent event) {
        targets = event.getTargetBundle();
        startLevel();
    }

    //? if >=26.3 {
    /*// With improved transparency (OIT) translucent surfaces leave the depth buffer alone; called right after OIT, while
    // its depth bounds still hold the nearest translucent surface per pixel.
    public static void captureOit() {
        if (active() && targets != null) {
            oitCaptured = OIT_DEPTH.capture(targets);
        }
    }
    *///?}

    private static void afterOpaqueFeatures(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        if (!active()) {
            return;
        }
        FRAME.capture(event.getLevelRenderState());
        // From 26.3 this event fires inside the level's render pass, where nothing can be copied; LevelRendererMixin
        // calls captureOpaque() between passes instead.
        //? if <26.3
        captureOpaque();
    }

    private static void afterTranslucentFeatures(RenderLevelStageEvent.AfterTranslucentFeatures event) {
        if (active() && opaqueColorCaptured) {
            featureColorCaptured = FEATURE_COLOR.copyColorFrom(McCompat.mainTarget());
        }
    }

    private static void afterTranslucentBlocks(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        if (!active()) {
            return;
        }
        // From 26.3 this runs inside a render pass; LevelRendererMixin calls captureTranslucent() instead.
        //? if <26.3 {
        // With Fabulous graphics, translucent terrain is drawn into its own target.
        RenderTarget source = targets != null && targets.translucent != null
            ? targets.translucent.get()
            : McCompat.mainTarget();
        fullCaptured = FULL_DEPTH.copyFrom(source);
        //?}
    }

    private static void afterLevel(RenderLevelStageEvent.AfterLevel event) {
        targets = null;
        finishLevel(() -> FRAME.capture(event.getLevelRenderState()));
    }
    //?} else {
    /*// 1.21.1 has a single event with stages: the sky starts a level, block entities end the opaque geometry.
    private static void onRenderStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            startLevel();
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            if (active()) {
                captureFrame(event);
                captureOpaque();
            }
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            if (active()) {
                // With Fabulous graphics, translucent terrain is drawn into its own target.
                RenderTarget translucent = event.getLevelRenderer().getTranslucentTarget();
                fullCaptured = FULL_DEPTH.copyFrom(translucent != null ? translucent : McCompat.mainTarget());
            }
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            finishLevel(() -> captureFrame(event));
        }
    }

    private static void captureFrame(RenderLevelStageEvent event) {
        FRAME.capture(event.getCamera(), event.getProjectionMatrix(), event.getModelViewMatrix(), Minecraft.getInstance().level,
            event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }
    *///?}

    // After the level: our passes, then the FSR upscale back to window size.
    private static void finishLevel(Runnable captureFrame) {
        RenderTarget fullSize = RenderScaler.fullSizeTarget();
        try {
            if (active()) {
                render(captureFrame);
            }
            if (fullSize != null) {
                upscale(McCompat.mainTarget(), fullSize);
            }
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("Vanilla Shader post-processing failed and has been disabled for this session", e);
        } finally {
            Gpu.finish();
            RenderScaler.endLevel();
        }
        DevCapture.afterLevel();
        PassTimer.endFrame();
    }

    // captureFrame reads the camera and sky when the snapshot after opaque geometry did not happen.
    private static void render(Runnable captureFrame) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = McCompat.mainTarget();
        int width = main.width;
        int height = main.height;

        for (Texture texture : TEXTURES) {
            texture.ensure(width, height);
        }
        if (!fullCaptured) {
            // Some renderers skip the stage events; fall back to the final depth buffer.
            fullCaptured = FULL_DEPTH.copyFrom(main);
        }
        if (!opaqueCaptured) {
            captureFrame.run();
        }
        if (!fullCaptured) {
            return;
        }
        TexRef opaqueDepth = (opaqueCaptured ? OPAQUE_DEPTH : FULL_DEPTH).ref();

        ShaderConfig.Effects effects = ShaderConfig.effects();
        // The height map also tells real water from ice and glass for the reflections.
        if (effects.shadows() > 0 || effects.reflections() > 0) {
            long start = System.nanoTime();
            SHADOW_MAP.update(minecraft);
            PassTimer.cpu("shadow heightmap", start);
        }
        TexRef cloudShadow = cloudShadowMap(effects);
        int cloudRange = cloudShadow != null ? WeatherStoryCompat.cloudShadowRange() : -1;
        if (cloudRange <= 0) {
            cloudShadow = null;
        }
        FRAME.update(minecraft, effects, width, height, opaqueCaptured, Gpu.isZZeroToOne(), SHADOW_MAP.minY(), SHADOW_MAP.maxTerrainY(), cloudRange);
        UniformBlock frame = frameUniforms();
        frame.write(data -> FRAME.write(Std140.into(data)));
        ensureBlurConfigs();

        TexRef fullDepth = FULL_DEPTH.ref();
        //? if >=26.3 {
        /*if (oitCaptured) {
            // Translucent surfaces put back in front of the opaque depth; null when its shader did not compile.
            TexRef resolved = OIT_DEPTH.resolve(frame, opaqueDepth, width, height);
            fullDepth = resolved != null ? resolved : fullDepth;
        }
        *///?}

        // The composite writes to its own texture, so it can read the level straight from the main target.
        TexRef scene = TexRef.color(main);
        TexRef heights = (effects.shadows() > 0 || effects.reflections() > 0) && SHADOW_MAP.ref() != null ? SHADOW_MAP.ref() : BLACK.ref();

        // Every pass checks that its shader compiled on this GPU; a broken one only loses its own effect.
        if (usable(LIGHTING)) {
            draw(LIGHTING, LIGHTING_TEX, frame, null,
                new Input("Depth", fullDepth, false),
                new Input("ShadowMap", heights, false),
                new Input("CloudShadow", cloudShadow != null ? cloudShadow : BLACK.ref(), true));
        } else {
            // White: no occlusion, full sun, and the farthest distance so the upsample weighs all texels alike.
            LIGHTING_TEX.texture.clear(0xFFFFFFFF);
        }
        boolean denoise = usable(DENOISE) && (effects.ambientOcclusion() > 0 || effects.shadows() > 0);
        if (denoise && usable(LIGHTING)) {
            denoise(LIGHTING_TEX, LIGHTING_TMP, LIGHTING_TEX, true);
        }
        if (FRAME.anyPointLights() && usable(POINT_LIGHTS)) {
            draw(POINT_LIGHTS, POINT_LIGHTS_TEX, frame, null,
                new Input("Depth", fullDepth, false),
                new Input("ShadowMap", heights, false));
            if (usable(DENOISE)) {
                denoise(POINT_LIGHTS_TEX, POINT_LIGHTS_TMP, LIGHTING_TEX, false);
            }
        } else {
            POINT_LIGHTS_TEX.texture.clear(0);
        }
        if (effects.reflections() > 0 && usable(WATER)) {
            draw(WATER, WATER_TEX, frame, null,
                new Input("Scene", scene, true),
                new Input("Depth", fullDepth, false),
                new Input("OpaqueDepth", opaqueDepth, false),
                new Input("ShadowMap", heights, false));
        } else {
            WATER_TEX.texture.clear(0);
        }

        // Ray-traced lighting: rays through a voxel copy of the nearby blocks for light from emissive blocks and
        // reflections on glossy ones.
        boolean tracing = ShaderConfig.RAY_TRACING.get();
        if (tracing) {
            long start = System.nanoTime();
            VOLUME.update(minecraft.level, McCompat.cameraPosition());
            PassTimer.cpu("material volume", start);
        }
        TexRef volume = VOLUME.ref() != null ? VOLUME.ref() : BLACK.ref();
        if (tracing && usable(RT_LIGHT)) {
            draw(RT_LIGHT, RT_LIGHT_TEX, frame, null,
                new Input("Depth", fullDepth, false),
                new Input("Volume", volume, false));
            if (usable(DENOISE)) {
                denoise(RT_LIGHT_TEX, RT_LIGHT_TMP, LIGHTING_TEX, false);
            }
        } else {
            RT_LIGHT_TEX.texture.clear(0);
        }
        if (tracing && usable(RT_REFLECT)) {
            draw(RT_REFLECT, RT_REFLECT_TEX, frame, null,
                new Input("Scene", scene, true),
                new Input("Depth", fullDepth, false),
                new Input("Volume", volume, false));
        } else {
            RT_REFLECT_TEX.texture.clear(0);
        }

        if (usable(COMPOSITE)) {
            draw(COMPOSITE, LIT, frame, null,
                new Input("Scene", scene, true),
                new Input("Depth", fullDepth, false),
                new Input("OpaqueDepth", opaqueDepth, false),
                new Input("Lighting", LIGHTING_TEX.ref(), false),
                new Input("PointLights", POINT_LIGHTS_TEX.ref(), false),
                new Input("Water", WATER_TEX.ref(), true),
                // Without both snapshots, comparing a texture with itself means "no veil anywhere".
                new Input("OpaqueColor", featureColorCaptured ? OPAQUE_COLOR.ref() : scene, false),
                new Input("FeatureColor", featureColorCaptured ? FEATURE_COLOR.ref() : scene, false),
                new Input("ShadowMap", heights, false),
                new Input("RtLight", RT_LIGHT_TEX.ref(), false),
                new Input("RtReflect", RT_REFLECT_TEX.ref(), true));
        } else {
            // Bloom, god rays and grading still work on the plain level.
            LIT.texture.copyColorFrom(main);
        }

        TexRef bloomNear = BLACK.ref();
        TexRef bloomFar = BLACK.ref();
        if (effects.bloom() > 0 && usable(BRIGHT) && usable(BLUR)) {
            draw(BRIGHT, BLOOM_NEAR, frame, null, new Input("In", LIT.ref(), true));
            blur(BLOOM_NEAR, BLOOM_NEAR_TMP, effects.bloom() >= 4 ? 2 : 1);
            bloomNear = BLOOM_NEAR.ref();
            if (effects.bloom() >= 3) {
                draw(BLUR, BLOOM_FAR, frame, blurHorizontal, new Input("In", BLOOM_NEAR.ref(), true));
                blur(BLOOM_FAR, BLOOM_FAR_TMP, 1);
                bloomFar = BLOOM_FAR.ref();
            }
        }

        TexRef rays = BLACK.ref();
        if (effects.godRays() > 0 && FRAME.sunOnScreen() && usable(GOD_RAYS)) {
            draw(GOD_RAYS, RAYS, frame, null,
                new Input("Scene", scene, true),
                new Input("Depth", fullDepth, false));
            // The rays march with per-pixel jitter; a light blur hides the grain.
            if (usable(BLUR)) {
                blur(RAYS, RAYS_TMP, 1);
            }
            rays = RAYS.ref();
        }

        // Without the final pass nothing is written back, and the level simply shows as vanilla.
        if (usable(FINAL)) {
            try (PassTimer.Timing timing = PassTimer.gpu(FINAL.path())) {
                Gpu.draw(FINAL, main, "Frame", frame,
                    new Input("Lit", LIT.ref(), true),
                    new Input("BloomNear", bloomNear, true),
                    new Input("BloomFar", bloomFar, true),
                    new Input("Rays", rays, true));
            }
        }

        frame.rotate();
    }

    /**
     * Whether the pipeline's shaders compiled on this GPU. Drivers differ in what GLSL they accept, so instead of
     * failing the whole mod, a broken pass is skipped and reported once.
     */
    static boolean usable(PostPipeline pipeline) {
        if (BROKEN.contains(pipeline.location())) {
            return false;
        }
        boolean valid = pipeline.compiles();
        if (!valid && BROKEN.add(pipeline.location())) {
            LOGGER.warn("Vanilla Shader: {} did not compile on {}; that effect is turned off. The shader error is logged above.",
                pipeline.location(), Gpu.describe());
        }
        return valid;
    }

    /** FSR needs both of its passes; without them the scaler keeps rendering at native resolution. */
    static boolean fsrUsable() {
        return usable(FSR_EASU) && usable(FSR_RCAS);
    }

    /**
     * WeatherStory draws cloud shadows only in the vanilla terrain shader. Sodium replaces that shader, so with
     * Sodium installed we apply WeatherStory's cloud shadow map ourselves; without it, WeatherStory already does.
     */
    private static @Nullable TexRef cloudShadowMap(ShaderConfig.Effects effects) {
        if (effects.shadows() <= 0 || !ShaderConfig.WEATHER_INTEGRATION.get() || !ModList.get().isLoaded("sodium")) {
            return null;
        }
        return WeatherStoryCompat.cloudShadowMap();
    }

    /**
     * AMD FSR 1: edge-adaptive spatial upsampling (EASU) from the scaled world to window size, then robust
     * contrast-adaptive sharpening (RCAS) into the real main target.
     */
    private static void upscale(RenderTarget source, RenderTarget destination) {
        UPSCALED.ensureSize(destination.width, destination.height);
        try (PassTimer.Timing timing = PassTimer.gpu(FSR_EASU.path())) {
            Gpu.draw(FSR_EASU, UPSCALED, null, null, new Input("In", TexRef.color(source), false));
        }
        try (PassTimer.Timing timing = PassTimer.gpu(FSR_RCAS.path())) {
            Gpu.draw(FSR_RCAS, destination, "FsrConfig", fsrConfig(), new Input("In", UPSCALED.ref(), false));
        }
    }

    private static UniformBlock fsrConfig() {
        int sharpness = ShaderConfig.FSR_SHARPNESS.get();
        if (fsrConfig == null || sharpness != fsrSharpness) {
            if (fsrConfig != null) {
                fsrConfig.close();
            }
            // RCAS takes sharpness in stops (0 = sharpest); 100% maps to 0 stops and 0% to 2 stops.
            float stops = (1.0F - sharpness / 100.0F) * 2.0F;
            fsrConfig = UniformBlock.constant("FSR", 16, data -> Std140.into(data).putVec4((float) Math.pow(2.0, -stops), 0.0F, 0.0F, 0.0F));
            fsrSharpness = sharpness;
        }
        return fsrConfig;
    }

    // Depth-aware blur, horizontal into temp then vertical back; distances for the edge test come from the guide.
    private static void denoise(Texture target, Texture temp, Texture guide, boolean keepDistances) {
        UniformBlock horizontal = keepDistances ? denoiseHorizontalKeep : blurHorizontal;
        UniformBlock vertical = keepDistances ? denoiseVerticalKeep : blurVertical;
        // When the guide is the target itself, the temp copy carries the (unchanged) distances for the second pass.
        Texture secondGuide = guide == target ? temp : guide;
        drawWith(DENOISE, temp, "DenoiseConfig", horizontal, new Input("In", target.ref(), false), new Input("Guide", guide.ref(), false));
        drawWith(DENOISE, target, "DenoiseConfig", vertical, new Input("In", temp.ref(), false), new Input("Guide", secondGuide.ref(), false));
    }

    private static void blur(Texture target, Texture temp, int iterations) {
        for (int i = 0; i < iterations; i++) {
            drawWith(BLUR, temp, "BlurConfig", blurHorizontal, new Input("In", target.ref(), true));
            drawWith(BLUR, target, "BlurConfig", blurVertical, new Input("In", temp.ref(), true));
        }
    }

    private static void draw(PostPipeline pipeline, Texture output, UniformBlock frame, @Nullable UniformBlock blurConfig, Input... inputs) {
        if (blurConfig != null) {
            drawWith(pipeline, output, "BlurConfig", blurConfig, inputs);
        } else {
            drawWith(pipeline, output, "Frame", frame, inputs);
        }
    }

    private static void drawWith(PostPipeline pipeline, Texture output, @Nullable String block, @Nullable UniformBlock uniforms, Input... inputs) {
        try (PassTimer.Timing timing = PassTimer.gpu(pipeline.path())) {
            Gpu.draw(pipeline, output.texture, block, uniforms, inputs);
        }
    }

    private static UniformBlock frameUniforms() {
        if (frameUniforms == null) {
            frameUniforms = UniformBlock.perFrame("frame", FrameState.SIZE);
        }
        return frameUniforms;
    }

    private static void ensureBlurConfigs() {
        if (blurHorizontal == null) {
            blurHorizontal = blurConfig(1.0F, 0.0F, 0.0F);
            blurVertical = blurConfig(0.0F, 1.0F, 0.0F);
            denoiseHorizontalKeep = blurConfig(1.0F, 0.0F, 1.0F);
            denoiseVerticalKeep = blurConfig(0.0F, 1.0F, 1.0F);
        }
    }

    private static UniformBlock blurConfig(float x, float y, float flag) {
        return UniformBlock.constant("blur", 16, data -> Std140.into(data).putVec4(x, y, flag, 0.0F));
    }

    /** An RGBA8 render texture at a fraction of the screen size ({@code divisor} 0 = a fixed 1x1 black texture). */
    private static final class Texture {
        private final PostTexture texture;
        private final int divisor;

        private Texture(String name, int divisor) {
            this.texture = new PostTexture(name);
            this.divisor = divisor;
        }

        private void ensure(int screenWidth, int screenHeight) {
            boolean created = texture.ensureSize(
                divisor == 0 ? 1 : Math.max(1, screenWidth / divisor),
                divisor == 0 ? 1 : Math.max(1, screenHeight / divisor)
            );
            if (created && divisor == 0) {
                texture.clear(0);
            }
        }

        private TexRef ref() {
            return texture.ref();
        }
    }
}
