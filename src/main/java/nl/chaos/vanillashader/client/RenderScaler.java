package nl.chaos.vanillashader.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import nl.chaos.vanillashader.config.RenderScale;
import nl.chaos.vanillashader.config.ShaderConfig;
import nl.chaos.vanillashader.mixin.MinecraftAccessor;
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
        Minecraft minecraft = Minecraft.getInstance();
        ShaderConfig.Effects effects = ShaderConfig.effects();
        RenderScale scale = effects.renderScale();
        if (scale == RenderScale.NATIVE || !ShaderRenderer.active() || !ShaderRenderer.fsrUsable()) {
            return;
        }
        RenderTarget main = minecraft.getMainRenderTarget();
        int width = Math.max(1, Math.round(main.width * scale.factor()));
        int height = Math.max(1, Math.round(main.height * scale.factor()));
        if (scaled == null || scaled.useStencil != main.useStencil) {
            if (scaled != null) {
                scaled.destroyBuffers();
            }
            scaled = new TextureTarget("Vanilla Shader scaled world", width, height, true, main.useStencil);
        } else if (scaled.width != width || scaled.height != height) {
            scaled.resize(width, height);
        }
        fullSize = main;
        ((MinecraftAccessor) minecraft).vanillashader$setMainRenderTarget(scaled);
    }

    /** The real, window-sized main target while the scaled one is installed; null when not scaling. */
    static @Nullable RenderTarget fullSizeTarget() {
        return fullSize;
    }

    static void endLevel() {
        if (fullSize != null) {
            ((MinecraftAccessor) Minecraft.getInstance()).vanillashader$setMainRenderTarget(fullSize);
            fullSize = null;
        }
    }
}
