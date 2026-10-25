package nl.chaos.vanillashader.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Swaps the main render target for FSR; it lives on Minecraft up to 26.1 and on GameRenderer from 26.2. */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.renderer.GameRenderer.class)
*///?} else
@Mixin(net.minecraft.client.Minecraft.class)
public interface MainTargetAccessor {
    @Mutable
    @Accessor("mainRenderTarget")
    void vanillashader$setMainRenderTarget(RenderTarget target);
}
