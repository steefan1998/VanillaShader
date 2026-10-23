package nl.chaos.vanillashader.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import nl.chaos.vanillashader.client.RenderScaler;
import nl.chaos.vanillashader.client.ShaderRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /**
     * NeoForge has no event between the frame clear and the level render, which is where the world has to switch to
     * the lower-resolution target for FSR upscaling. The full-size target is restored right after the level.
     */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void vanillashader$beginScaledLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        RenderScaler.beginLevel();
    }

    /**
     * The projection the level is actually drawn with: the camera's, plus view bobbing, the hurt shake and the
     * nausea/portal wobble. Screen-space effects must use exactly this one, or reflections slide around while walking.
     */
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
            ordinal = 0
        )
    )
    private void vanillashader$captureLevelProjection(DeltaTracker deltaTracker, CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix) {
        ShaderRenderer.setLevelProjection(projectionMatrix);
    }
}
