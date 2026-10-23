package nl.chaos.vanillashader.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import nl.chaos.vanillashader.VanillaShader;
import nl.chaos.vanillashader.compat.IrisCompat;
import nl.chaos.vanillashader.compat.WeatherStoryCompat;
import nl.chaos.vanillashader.config.ShaderConfig;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
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
 */
public final class ShaderRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier SCREEN_QUAD = Identifier.withDefaultNamespace("core/screenquad");

    private static final RenderPipeline LIGHTING = pipeline("lighting", "Depth", "ShadowMap", "CloudShadow");
    private static final RenderPipeline POINT_LIGHTS = pipeline("point_lights", "Depth", "ShadowMap");
    private static final RenderPipeline WATER = pipeline("water", "Scene", "Depth", "OpaqueDepth", "ShadowMap");
    private static final RenderPipeline COMPOSITE = pipeline("composite", "Scene", "Depth", "OpaqueDepth", "Lighting", "PointLights", "Water", "OpaqueColor", "FeatureColor", "ShadowMap");
    private static final RenderPipeline BRIGHT = pipeline("bloom_bright", "In");
    private static final RenderPipeline BLUR = pipeline("blur", "In");
    private static final RenderPipeline GOD_RAYS = pipeline("god_rays", "Scene", "Depth");
    private static final RenderPipeline FINAL = pipeline("final", "Lit", "BloomNear", "BloomFar", "Rays");
    private static final RenderPipeline FSR_EASU = pipeline("fsr_easu", "In");
    private static final RenderPipeline FSR_RCAS = pipeline("fsr_rcas", "In");

    private static final Texture LIT = new Texture("lit", 1);
    private static final Texture LIGHTING_TEX = new Texture("lighting", 2);
    private static final Texture POINT_LIGHTS_TEX = new Texture("point_lights", 2);
    private static final Texture WATER_TEX = new Texture("water", 2);
    private static final Texture RAYS = new Texture("rays", 2);
    private static final Texture BLOOM_NEAR = new Texture("bloom_near", 4);
    private static final Texture BLOOM_NEAR_TMP = new Texture("bloom_near_tmp", 4);
    private static final Texture BLOOM_FAR = new Texture("bloom_far", 8);
    private static final Texture BLOOM_FAR_TMP = new Texture("bloom_far_tmp", 8);
    private static final Texture BLACK = new Texture("black", 0);
    private static final Texture UPSCALED = new Texture("fsr_upscaled", 1);
    // The level after opaque geometry and after translucent features: their difference shows WeatherStory's mist
    // and sand wisps (drawn without depth), so lighting can be kept off the veil in front of the terrain.
    private static final Texture OPAQUE_COLOR = new Texture("opaque_color", 1);
    private static final Texture FEATURE_COLOR = new Texture("feature_color", 1);
    private static final List<Texture> TEXTURES = List.of(LIT, LIGHTING_TEX, POINT_LIGHTS_TEX, WATER_TEX, RAYS, BLOOM_NEAR, BLOOM_NEAR_TMP, BLOOM_FAR, BLOOM_FAR_TMP, BLACK);
    private static final DepthCopy OPAQUE_DEPTH = new DepthCopy("opaque_depth");
    private static final DepthCopy FULL_DEPTH = new DepthCopy("full_depth");

    private static final FrameState FRAME = new FrameState();
    private static final ShadowHeightmap SHADOW_MAP = new ShadowHeightmap();
    private static @Nullable MappableRingBuffer frameUniforms;
    private static @Nullable GpuBuffer blurHorizontal;
    private static @Nullable GpuBuffer blurVertical;
    private static @Nullable GpuBuffer fsrConfig;
    private static int fsrSharpness = -1;
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
        NeoForge.EVENT_BUS.addListener(ShaderRenderer::onFrameGraphSetup);
        NeoForge.EVENT_BUS.addListener(ShaderRenderer::afterOpaqueFeatures);
        NeoForge.EVENT_BUS.addListener(ShaderRenderer::afterTranslucentFeatures);
        NeoForge.EVENT_BUS.addListener(ShaderRenderer::afterTranslucentBlocks);
        NeoForge.EVENT_BUS.addListener(ShaderRenderer::afterLevel);
        RenderScaler.init();
    }

    /** Called from the GameRenderer mixin with the projection the level is drawn with this frame. */
    public static void setLevelProjection(Matrix4fc projection) {
        FRAME.setLevelProjection(projection);
    }

    // The pipelines are deliberately not registered through RegisterRenderPipelinesEvent: Minecraft treats every
    // registered pipeline as required and drops ALL shaders (crashing the game) if one fails to compile on a driver.
    // Compiled on first use instead, where usable() turns a failure into one missing effect.

    private static RenderPipeline pipeline(String name, String... samplers) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(VanillaShader.id("pipeline/" + name))
            .withVertexShader(SCREEN_QUAD)
            .withFragmentShader(VanillaShader.id("post/" + name));
        String uniformBlock = uniformBlock(name);
        if (uniformBlock != null) {
            builder.withUniform(uniformBlock, UniformType.UNIFORM_BUFFER);
        }
        for (String sampler : samplers) {
            builder.withSampler(sampler + "Sampler");
        }
        return builder.build();
    }

    // The blur only needs its direction, RCAS its sharpness and EASU nothing; every other pass reads the frame block.
    private static @Nullable String uniformBlock(String pipeline) {
        return switch (pipeline) {
            case "blur" -> "BlurConfig";
            case "fsr_rcas" -> "FsrConfig";
            case "fsr_easu" -> null;
            default -> "Frame";
        };
    }

    static boolean active() {
        return !failed && ShaderConfig.effects().enabled() && !IrisCompat.shaderPackInUse() && Minecraft.getInstance().level != null;
    }

    private static void onFrameGraphSetup(FrameGraphSetupEvent event) {
        targets = event.getTargetBundle();
        opaqueCaptured = false;
        fullCaptured = false;
        opaqueColorCaptured = false;
        featureColorCaptured = false;
    }

    private static void afterOpaqueFeatures(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        if (!active()) {
            return;
        }
        FRAME.capture(event.getLevelRenderState());
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        opaqueCaptured = OPAQUE_DEPTH.copyFrom(main);
        // Mist and sand wisps only show up when fog is close; otherwise skip the two full-screen copies.
        opaqueColorCaptured = FRAME.fogIsClose() && OPAQUE_COLOR.copyFrom(main);
    }

    private static void afterTranslucentFeatures(RenderLevelStageEvent.AfterTranslucentFeatures event) {
        if (active() && opaqueColorCaptured) {
            featureColorCaptured = FEATURE_COLOR.copyFrom(Minecraft.getInstance().getMainRenderTarget());
        }
    }

    private static void afterTranslucentBlocks(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        if (!active()) {
            return;
        }
        // With Fabulous graphics, translucent terrain is drawn into its own target.
        RenderTarget source = targets != null && targets.translucent != null
            ? targets.translucent.get()
            : Minecraft.getInstance().getMainRenderTarget();
        fullCaptured = FULL_DEPTH.copyFrom(source);
    }

    private static void afterLevel(RenderLevelStageEvent.AfterLevel event) {
        targets = null;
        RenderTarget fullSize = RenderScaler.fullSizeTarget();
        try {
            if (active()) {
                render(event.getLevelRenderState());
            }
            if (fullSize != null) {
                upscale(Minecraft.getInstance().getMainRenderTarget(), fullSize);
            }
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("Vanilla Shader post-processing failed and has been disabled for this session", e);
        } finally {
            RenderScaler.endLevel();
        }
        DevCapture.afterLevel();
        PassTimer.endFrame();
    }

    private static void render(LevelRenderState levelState) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        int width = main.width;
        int height = main.height;

        for (Texture texture : TEXTURES) {
            texture.ensure(device, width, height);
        }
        if (!fullCaptured) {
            // Some renderers skip the stage events; fall back to the final depth buffer.
            fullCaptured = FULL_DEPTH.copyFrom(main);
        }
        if (!opaqueCaptured) {
            FRAME.capture(levelState);
        }
        if (!fullCaptured) {
            return;
        }
        DepthCopy opaqueDepth = opaqueCaptured ? OPAQUE_DEPTH : FULL_DEPTH;

        ShaderConfig.Effects effects = ShaderConfig.effects();
        // The height map also tells real water from ice and glass for the reflections.
        if (effects.shadows() > 0 || effects.reflections() > 0) {
            long start = System.nanoTime();
            SHADOW_MAP.update(minecraft);
            PassTimer.cpu("shadow heightmap", start);
        }
        GpuTextureView cloudShadow = cloudShadowMap(effects);
        int cloudRange = cloudShadow != null ? WeatherStoryCompat.cloudShadowRange() : -1;
        if (cloudRange <= 0) {
            cloudShadow = null;
        }
        FRAME.update(minecraft, effects, width, height, opaqueCaptured, device.isZZeroToOne(), SHADOW_MAP.minY(), SHADOW_MAP.maxTerrainY(), cloudRange);
        MappableRingBuffer uniforms = frameUniforms(device);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            FRAME.write(Std140Builder.intoBuffer(view.data()));
        }
        GpuBuffer frame = uniforms.currentBuffer();
        ensureBlurConfigs(device);

        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        // The composite writes to its own texture, so it can read the level straight from the main target.
        GpuTextureView scene = main.getColorTextureView();
        GpuTextureView heights = (effects.shadows() > 0 || effects.reflections() > 0) && SHADOW_MAP.view() != null ? SHADOW_MAP.view() : BLACK.view;

        // Every pass checks that its shader compiled on this GPU; a broken one only loses its own effect.
        if (usable(LIGHTING)) {
            draw(encoder, LIGHTING, LIGHTING_TEX.view, frame, null,
                new Input("Depth", FULL_DEPTH.view, nearest),
                new Input("ShadowMap", heights, nearest),
                new Input("CloudShadow", cloudShadow != null ? cloudShadow : BLACK.view, linear));
        } else {
            // White: no occlusion, full sun, and the farthest distance so the upsample weighs all texels alike.
            encoder.clearColorTexture(LIGHTING_TEX.texture, 0xFFFFFFFF);
        }
        if (FRAME.anyPointLights() && usable(POINT_LIGHTS)) {
            draw(encoder, POINT_LIGHTS, POINT_LIGHTS_TEX.view, frame, null,
                new Input("Depth", FULL_DEPTH.view, nearest),
                new Input("ShadowMap", heights, nearest));
        } else {
            encoder.clearColorTexture(POINT_LIGHTS_TEX.texture, 0);
        }
        if (effects.reflections() > 0 && usable(WATER)) {
            draw(encoder, WATER, WATER_TEX.view, frame, null,
                new Input("Scene", scene, linear),
                new Input("Depth", FULL_DEPTH.view, nearest),
                new Input("OpaqueDepth", opaqueDepth.view, nearest),
                new Input("ShadowMap", heights, nearest));
        } else {
            encoder.clearColorTexture(WATER_TEX.texture, 0);
        }

        if (usable(COMPOSITE)) {
            draw(encoder, COMPOSITE, LIT.view, frame, null,
                new Input("Scene", scene, linear),
                new Input("Depth", FULL_DEPTH.view, nearest),
                new Input("OpaqueDepth", opaqueDepth.view, nearest),
                new Input("Lighting", LIGHTING_TEX.view, nearest),
                new Input("PointLights", POINT_LIGHTS_TEX.view, nearest),
                new Input("Water", WATER_TEX.view, linear),
                // Without both snapshots, comparing a texture with itself means "no veil anywhere".
                new Input("OpaqueColor", featureColorCaptured ? OPAQUE_COLOR.view : scene, nearest),
                new Input("FeatureColor", featureColorCaptured ? FEATURE_COLOR.view : scene, nearest),
                new Input("ShadowMap", heights, nearest));
        } else {
            // Bloom, god rays and grading still work on the plain level.
            encoder.copyTextureToTexture(main.getColorTexture(), LIT.texture, 0, 0, 0, 0, 0, width, height);
        }

        GpuTextureView bloomNear = BLACK.view;
        GpuTextureView bloomFar = BLACK.view;
        if (effects.bloom() > 0 && usable(BRIGHT) && usable(BLUR)) {
            draw(encoder, BRIGHT, BLOOM_NEAR.view, frame, null, new Input("In", LIT.view, linear));
            blur(encoder, frame, BLOOM_NEAR, BLOOM_NEAR_TMP, linear, effects.bloom() >= 4 ? 2 : 1);
            bloomNear = BLOOM_NEAR.view;
            if (effects.bloom() >= 3) {
                draw(encoder, BLUR, BLOOM_FAR.view, frame, blurHorizontal, new Input("In", BLOOM_NEAR.view, linear));
                blur(encoder, frame, BLOOM_FAR, BLOOM_FAR_TMP, linear, 1);
                bloomFar = BLOOM_FAR.view;
            }
        }

        GpuTextureView rays = BLACK.view;
        if (effects.godRays() > 0 && FRAME.sunOnScreen() && usable(GOD_RAYS)) {
            draw(encoder, GOD_RAYS, RAYS.view, frame, null,
                new Input("Scene", scene, linear),
                new Input("Depth", FULL_DEPTH.view, nearest));
            rays = RAYS.view;
        }

        // Without the final pass nothing is written back, and the level simply shows as vanilla.
        if (usable(FINAL)) {
            draw(encoder, FINAL, main.getColorTextureView(), frame, null,
                new Input("Lit", LIT.view, linear),
                new Input("BloomNear", bloomNear, linear),
                new Input("BloomFar", bloomFar, linear),
                new Input("Rays", rays, linear));
        }

        uniforms.rotate();
    }

    /**
     * Whether the pipeline's shaders compiled on this GPU. Drivers differ in what GLSL they accept, so instead of
     * failing the whole mod, a broken pass is skipped and reported once.
     */
    private static boolean usable(RenderPipeline pipeline) {
        boolean valid = RenderSystem.getDevice().precompilePipeline(pipeline).isValid();
        if (!valid && BROKEN.add(pipeline.getLocation().toString())) {
            LOGGER.warn("Vanilla Shader: {} did not compile on {} ({}); that effect is turned off. The shader error is logged above.",
                pipeline.getLocation(), RenderSystem.getDevice().getRenderer(), RenderSystem.getDevice().getVersion());
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
    private static @Nullable GpuTextureView cloudShadowMap(ShaderConfig.Effects effects) {
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
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        UPSCALED.ensureSize(device, destination.width, destination.height);
        GpuBuffer config = fsrConfig(device);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        drawWith(encoder, FSR_EASU, UPSCALED.view, null, null, new Input("In", source.getColorTextureView(), nearest));
        drawWith(encoder, FSR_RCAS, destination.getColorTextureView(), "FsrConfig", config, new Input("In", UPSCALED.view, nearest));
    }

    private static GpuBuffer fsrConfig(GpuDevice device) {
        int sharpness = ShaderConfig.FSR_SHARPNESS.get();
        if (fsrConfig == null || sharpness != fsrSharpness) {
            if (fsrConfig != null) {
                fsrConfig.close();
            }
            // RCAS takes sharpness in stops (0 = sharpest); 100% maps to 0 stops and 0% to 2 stops.
            float stops = (1.0F - sharpness / 100.0F) * 2.0F;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                Std140Builder builder = Std140Builder.onStack(stack, 16);
                builder.putVec4((float) Math.pow(2.0, -stops), 0.0F, 0.0F, 0.0F);
                fsrConfig = device.createBuffer(() -> "Vanilla Shader FSR", GpuBuffer.USAGE_UNIFORM, builder.get());
            }
            fsrSharpness = sharpness;
        }
        return fsrConfig;
    }

    private static void blur(CommandEncoder encoder, GpuBuffer frame, Texture target, Texture temp, GpuSampler sampler, int iterations) {
        for (int i = 0; i < iterations; i++) {
            draw(encoder, BLUR, temp.view, frame, blurHorizontal, new Input("In", target.view, sampler));
            draw(encoder, BLUR, target.view, frame, blurVertical, new Input("In", temp.view, sampler));
        }
    }

    private static void draw(
        CommandEncoder encoder, RenderPipeline pipeline, GpuTextureView output, GpuBuffer frame, @Nullable GpuBuffer blurConfig, Input... inputs
    ) {
        if (blurConfig != null) {
            drawWith(encoder, pipeline, output, "BlurConfig", blurConfig, inputs);
        } else {
            drawWith(encoder, pipeline, output, "Frame", frame, inputs);
        }
    }

    private static void drawWith(CommandEncoder encoder, RenderPipeline pipeline, GpuTextureView output, @Nullable String uniformName, @Nullable GpuBuffer uniforms, Input... inputs) {
        try (PassTimer.Timing timing = PassTimer.gpu(encoder, pipeline.getLocation().getPath());
             RenderPass pass = encoder.createRenderPass(() -> "Vanilla Shader " + pipeline.getLocation(), output, OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            if (uniformName != null && uniforms != null) {
                pass.setUniform(uniformName, uniforms);
            }
            for (Input input : inputs) {
                pass.bindTexture(input.name + "Sampler", input.view, input.sampler);
            }
            pass.draw(0, 3);
        }
    }

    private static MappableRingBuffer frameUniforms(GpuDevice device) {
        if (frameUniforms == null) {
            frameUniforms = new MappableRingBuffer(() -> "Vanilla Shader frame", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, FrameState.SIZE);
        }
        return frameUniforms;
    }

    private static void ensureBlurConfigs(GpuDevice device) {
        if (blurHorizontal == null) {
            blurHorizontal = blurConfig(device, 1.0F, 0.0F);
            blurVertical = blurConfig(device, 0.0F, 1.0F);
        }
    }

    private static GpuBuffer blurConfig(GpuDevice device, float x, float y) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, 16);
            builder.putVec4(x, y, 0.0F, 0.0F);
            return device.createBuffer(() -> "Vanilla Shader blur", GpuBuffer.USAGE_UNIFORM, builder.get());
        }
    }

    private record Input(String name, GpuTextureView view, GpuSampler sampler) {
    }

    /** An RGBA8 render texture at a fraction of the screen size ({@code divisor} 0 = a fixed 1x1 black texture). */
    private static final class Texture {
        private final String name;
        private final int divisor;
        private @Nullable GpuTexture texture;
        private @Nullable GpuTextureView view;

        private Texture(String name, int divisor) {
            this.name = name;
            this.divisor = divisor;
        }

        private void ensure(GpuDevice device, int screenWidth, int screenHeight) {
            ensureSize(
                device,
                divisor == 0 ? 1 : Math.max(1, screenWidth / divisor),
                divisor == 0 ? 1 : Math.max(1, screenHeight / divisor)
            );
        }

        private boolean copyFrom(RenderTarget source) {
            GpuTexture color = source.getColorTexture();
            if (color == null) {
                return false;
            }
            GpuDevice device = RenderSystem.getDevice();
            ensureSize(device, source.width, source.height);
            device.createCommandEncoder().copyTextureToTexture(color, texture, 0, 0, 0, 0, 0, source.width, source.height);
            return true;
        }

        private void ensureSize(GpuDevice device, int width, int height) {
            if (texture != null && texture.getWidth(0) == width && texture.getHeight(0) == height) {
                return;
            }
            close();
            int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
            texture = device.createTexture(() -> "Vanilla Shader " + name, usage, TextureFormat.RGBA8, width, height, 1, 1);
            view = device.createTextureView(texture);
            if (divisor == 0) {
                device.createCommandEncoder().clearColorTexture(texture, 0);
            }
        }

        private void close() {
            if (view != null) {
                view.close();
            }
            if (texture != null) {
                texture.close();
            }
            texture = null;
            view = null;
        }
    }

    /** A depth texture with the same format as the source it copies from. */
    private static final class DepthCopy {
        private final String name;
        private @Nullable GpuTexture texture;
        private @Nullable GpuTextureView view;

        private DepthCopy(String name) {
            this.name = name;
        }

        private boolean copyFrom(RenderTarget source) {
            GpuTexture depth = source.getDepthTexture();
            if (depth == null) {
                return false;
            }
            int width = depth.getWidth(0);
            int height = depth.getHeight(0);
            if (texture == null || texture.getWidth(0) != width || texture.getHeight(0) != height || texture.getFormat() != depth.getFormat()) {
                if (view != null) {
                    view.close();
                }
                if (texture != null) {
                    texture.close();
                }
                GpuDevice device = RenderSystem.getDevice();
                int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
                texture = device.createTexture(() -> "Vanilla Shader " + name, usage, depth.getFormat(), width, height, 1, 1);
                view = device.createTextureView(texture);
            }
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(depth, texture, 0, 0, 0, 0, 0, width, height);
            return true;
        }
    }
}
