package nl.chaos.vanillashader.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import nl.chaos.vanillashader.client.RenderScaler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge has no event between the frame clear and the level render, which is where the world has to switch to
 * the lower-resolution target for FSR upscaling. The full-size target is restored right after the level.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void vanillashader$beginScaledLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        RenderScaler.beginLevel();
    }
}
