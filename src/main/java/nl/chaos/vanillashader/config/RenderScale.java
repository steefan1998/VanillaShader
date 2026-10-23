package nl.chaos.vanillashader.config;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Internal resolution of the world, upscaled with AMD FSR 1. Percentages follow AMD's FSR 1 quality modes. */
public enum RenderScale {
    NATIVE(1.0F),
    ULTRA_QUALITY(1.0F / 1.3F),
    QUALITY(1.0F / 1.5F),
    BALANCED(1.0F / 1.7F),
    PERFORMANCE(1.0F / 2.0F);

    private final float factor;

    RenderScale(float factor) {
        this.factor = factor;
    }

    public float factor() {
        return factor;
    }

    public Component displayName() {
        return Component.translatable("vanillashader.render_scale." + name().toLowerCase(Locale.ROOT), Math.round(factor * 100.0F));
    }
}
