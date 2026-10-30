package nl.chaos.vanillashader.compat;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeApplyShaderRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import nl.chaos.vanillashader.client.gpu.TexRef;
import nl.chaos.vanillashader.config.ShaderConfig;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
//? if >=26.1 {
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import nl.chaos.vanillashader.client.gpu.ExternalTexture;
//?}

/**
 * Distant Horizons draws its far-away terrain (LODs) into its own depth buffer and then copies only the colour into
 * Minecraft's image, so to our effects that terrain looks like sky: the sky colouring and god rays went straight
 * through distant mountains. This reads DH's depth and projection each frame so the effects can leave LODs alone.
 * It also lets our settings skip DH's drawing.
 *
 * <p>Everything that touches DH's classes lives in {@link Hooks}, which is only loaded when DH is installed.
 */
public final class DistantHorizonsCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DISPLAY_NAME = "Vanilla Shader";

    private static @Nullable Boolean loaded;
    private static boolean failed;

    private DistantHorizonsCompat() {
    }

    /** What the effects need from DH this frame: its depth buffer and how to turn a depth value into a distance. */
    public record Frame(TexRef depth, Matrix4f inverseProjection, float ndcScale, float ndcOffset, boolean reversedZ) {
    }

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded("distanthorizons");
        }
        return loaded;
    }

    public static void init() {
        if (isLoaded()) {
            guard(Hooks::register);
        }
    }

    /** Called every client tick: applies the "Distant Horizons" switch from our settings once DH is ready. */
    public static void tick() {
        if (isLoaded()) {
            guard(Hooks::applyRenderingSwitch);
        }
    }

    /** DH's depth and projection when it rendered this frame; null without DH, or with its rendering off. */
    public static @Nullable Frame frame(long frameIndex) {
        if (!isLoaded() || failed || !ShaderConfig.DISTANT_HORIZONS.get()) {
            return null;
        }
        try {
            return Hooks.frame(frameIndex);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return null;
        }
    }

    private static void guard(Runnable action) {
        if (failed) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    private static void fail(Throwable e) {
        failed = true;
        LOGGER.warn("Vanilla Shader: the Distant Horizons integration failed and has been turned off; distant terrain is treated as sky", e);
    }

    private static final class Hooks {
        // Written by DH's render callback, read by our passes later in the same frame.
        private static final Matrix4f PROJECTION = new Matrix4f();
        private static long renderedFrame = -1;
        private static long currentFrame;
        private static @Nullable Boolean appliedSwitch;
        private static boolean warnedEngine;
        private static boolean reportedDepth;
        private static boolean repairChecked;

        static void register() {
            DhApi.events.bind(DhApiBeforeApplyShaderRenderEvent.class, new DhApiBeforeApplyShaderRenderEvent() {
                @Override
                public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                    DhApiMat4f m = event.value.dhProjectionMatrix;
                    // DH's matrices are row-major (m<row><column>), JOML's constructor takes columns.
                    PROJECTION.set(
                        m.m00, m.m10, m.m20, m.m30,
                        m.m01, m.m11, m.m21, m.m31,
                        m.m02, m.m12, m.m22, m.m32,
                        m.m03, m.m13, m.m23, m.m33
                    );
                    renderedFrame = currentFrame;
                }
            });
            // Our "Distant Horizons" switch skips DH's drawing frame by frame. DH's own rendering setting is left
            // alone: it is tied to its renderer mode, and overriding it through the API writes that mode to DH's
            // config file for good, so DH would stay off after the switch went back on.
            DhApi.events.bind(DhApiBeforeRenderEvent.class, new DhApiBeforeRenderEvent() {
                @Override
                public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                    if (!ShaderConfig.DISTANT_HORIZONS.get()) {
                        event.cancelEvent();
                    }
                }
            });
            LOGGER.info("Distant Horizons detected, distant terrain is kept apart from the sky");
        }

        // While DH draws, it turns vanilla fog off; with its drawing skipped the chunks would end in a hard edge, so
        // vanilla fog is asked back for as long as the switch is off.
        static void applyRenderingSwitch() {
            if (DhApi.Delayed.configs == null) {
                return;
            }
            repairOnce();
            boolean enabled = ShaderConfig.DISTANT_HORIZONS.get();
            if (appliedSwitch != null && appliedSwitch == enabled) {
                return;
            }
            IDhApiConfigValue<Boolean> vanillaFog = DhApi.Delayed.configs.graphics().fog().enableVanillaFog();
            if (enabled) {
                // Back to whatever the player set in DH's own menu.
                vanillaFog.clearValue();
            } else {
                vanillaFog.setValue(true, DISPLAY_NAME);
            }
            appliedSwitch = enabled;
        }

        // A test build turned DH off through its rendering setting, which DH then saved to its own config for good, so
        // DH stayed off after our switch went back on. Once per installation: with our switch on and DH's rendering
        // off, turn it back on. Setting it through the API saves the linked renderer mode too, so clearing our value
        // afterwards leaves DH on as the player's own setting. Not again after that, so a later choice in DH's own
        // menu to turn it off is respected.
        private static void repairOnce() {
            if (repairChecked) {
                return;
            }
            repairChecked = true;
            Path marker = FMLPaths.CONFIGDIR.get().resolve("vanillashader-dh-repaired");
            if (Files.exists(marker)) {
                return;
            }
            IDhApiConfigValue<Boolean> rendering = DhApi.Delayed.configs.graphics().renderingEnabled();
            if (ShaderConfig.DISTANT_HORIZONS.get() && Boolean.FALSE.equals(rendering.getTrueValue())) {
                rendering.setValue(true, DISPLAY_NAME);
                rendering.clearValue();
                LOGGER.info("Vanilla Shader: turned Distant Horizons' rendering back on (an earlier build of this mod had switched it off)");
            }
            try {
                Files.writeString(marker, "Distant Horizons' rendering setting was checked once by Vanilla Shader.\n");
            } catch (IOException e) {
                LOGGER.warn("Vanilla Shader: could not write {}", marker, e);
            }
        }

        static @Nullable Frame frame(long frameIndex) {
            // DH renders before our passes, inside the same frame; a stale projection means it did not draw.
            boolean rendered = renderedFrame == frameIndex;
            currentFrame = frameIndex + 1;
            if (!rendered || DhApi.Delayed.renderProxy == null) {
                return null;
            }
            TexRef depth = depthTexture();
            if (depth == null) {
                return null;
            }
            boolean zeroToOne;
            boolean reversed;
            try {
                zeroToOne = DhApi.Delayed.renderProxy.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE;
                reversed = DhApi.Delayed.renderProxy.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z;
            } catch (IllegalStateException notReady) {
                return null;
            }
            if (!reportedDepth) {
                reportedDepth = true;
                LOGGER.info("Vanilla Shader: reading Distant Horizons' depth ({}, {})", zeroToOne ? "0..1" : "-1..1", reversed ? "reversed Z" : "forward Z");
            }
            return new Frame(depth, new Matrix4f(PROJECTION).invert(), zeroToOne ? 1.0F : 2.0F, zeroToOne ? 0.0F : -1.0F, reversed);
        }

        private static @Nullable TexRef depthTexture() {
            //? if >=26.1 {
            if (DhApi.Delayed.renderProxy.getRenderingEngine() != EDhApiRenderingEngine.BLAZE_3D) {
                // DH drawing with raw OpenGL next to Minecraft's own GPU API: its texture cannot be bound to our passes.
                if (!warnedEngine) {
                    warnedEngine = true;
                    LOGGER.warn("Vanilla Shader: Distant Horizons renders with {}, its depth cannot be read; distant terrain is treated as sky",
                        DhApi.Delayed.renderProxy.getRenderingEngine());
                }
                return null;
            }
            DhApiResult<IDhApiBlazeTextureWrapper> result = DhApi.Delayed.renderProxy.getDhDepthTextureBlazeWrapper();
            return result.success && result.payload != null ? ExternalTexture.DH_DEPTH.wrap(result.payload.getWrappedMcObject()) : null;
            //?} else {
            /*DhApiResult<Integer> result = DhApi.Delayed.renderProxy.getDhDepthTextureGlId();
            return result.success && result.payload != null && result.payload > 0 ? TexRef.of(result.payload) : null;
            *///?}
        }
    }
}
