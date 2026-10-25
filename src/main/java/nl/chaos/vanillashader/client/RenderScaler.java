package nl.chaos.vanillashader.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import nl.chaos.vanillashader.compat.McCompat;
import nl.chaos.vanillashader.config.RenderScale;
import nl.chaos.vanillashader.config.ShaderConfig;
import org.jspecify.annotations.Nullable;

/**
 * Renders the world at a fraction of the window size for FSR 1 upscaling.
 *
 * <p>Just before the level is drawn, Minecraft's main render target is swapped for a smaller one, so vanilla,
 * Sodium and WeatherStory all render into it without knowing. After the level (and our effects) the image is
 * upscaled into the real main target and that target is put back, so the hand, HUD and screenshots are full size.
 */
public final class RenderScaler {
    private static @Nullable TextureTarget scaled;
    private static @Nullable RenderTarget fullSize;

    private RenderScaler() {
    }

    static void init() {
        // Safety net: never leave the small target installed past the end of a frame.
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> endLevel());
    }

    /** Called from the GameRenderer mixin right before the level renders. */
    public static void beginLevel() {
        endLevel();
        ShaderConfig.Effects effects = ShaderConfig.effects();
        RenderScale scale = effects.renderScale();
        if (scale == RenderScale.NATIVE || !ShaderRenderer.active() || !ShaderRenderer.fsrUsable()) {
            return;
        }
        RenderTarget main = McCompat.mainTarget();
        int width = Math.max(1, Math.round(main.width * scale.factor()));
        int height = Math.max(1, Math.round(main.height * scale.factor()));
        if (scaled == null || !sameDepth(scaled, main)) {
            if (scaled != null) {
                scaled.destroyBuffers();
            }
            scaled = createLike(main, width, height);
        } else if (scaled.width != width || scaled.height != height) {
            //? if >=26.1 {
            scaled.resize(width, height);
            //?} else
            /*scaled.resize(width, height, Minecraft.ON_OSX);*/
        }
        fullSize = main;
        McCompat.setMainTarget(scaled);
    }

    /** A target with the same buffers as the main one (a depth buffer with or without stencil), at another size. */
    private static TextureTarget createLike(RenderTarget main, int width, int height) {
        String label = "Vanilla Shader scaled world";
        //? if >=26.3 {
        /*return new TextureTarget(label, width, height, main.getColorTexture().getFormat(), main.getDepthTexture().getFormat());
        *///?} else if >=26.2 {
        /*return new TextureTarget(label, width, height, true, main.useStencil, com.mojang.blaze3d.GpuFormat.RGBA8_UNORM);
        *///?} else if >=26.1 {
        return new TextureTarget(label, width, height, true, main.useStencil);
        //?} else {
        /*TextureTarget target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        if (main.isStencilEnabled()) {
            target.enableStencil();
        }
        return target;
        *///?}
    }

    private static boolean sameDepth(RenderTarget scaled, RenderTarget main) {
        //? if >=26.3 {
        /*return scaled.getDepthTexture() != null && main.getDepthTexture() != null && scaled.getDepthTexture().getFormat() == main.getDepthTexture().getFormat();
        *///?} else if >=26.1 {
        return scaled.useStencil == main.useStencil;
        //?} else
        /*return scaled.isStencilEnabled() == main.isStencilEnabled();*/
    }

    /** The real, window-sized main target while the scaled one is installed; null when not scaling. */
    static @Nullable RenderTarget fullSizeTarget() {
        return fullSize;
    }

    static void endLevel() {
        if (fullSize != null) {
            McCompat.setMainTarget(fullSize);
            fullSize = null;
        }
    }
}
