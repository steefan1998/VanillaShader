package nl.chaos.vanillashader.config;

import net.minecraft.network.chat.Component;

/**
 * Graphics presets. Each preset fixes the quality level (0 = off .. 4 = ultra) of every effect;
 * {@link #AUTO} picks between the presets to hold a target frame rate, and {@link #CUSTOM} uses the per-effect
 * values from the config instead.
 */
public enum Profile {
    OFF(0, 0, 0, 0, 0),
    LOW(0, 1, 0, 0, 0),
    MEDIUM(1, 2, 1, 2, 1),
    HIGH(3, 3, 3, 3, 3),
    MAX(4, 4, 4, 4, 4),
    AUTO(-1, -1, -1, -1, -1),
    CUSTOM(-1, -1, -1, -1, -1);

    private final int ambientOcclusion;
    private final int reflections;
    private final int godRays;
    private final int bloom;
    private final int shadows;

    Profile(int ambientOcclusion, int reflections, int godRays, int bloom, int shadows) {
        this.ambientOcclusion = ambientOcclusion;
        this.reflections = reflections;
        this.godRays = godRays;
        this.bloom = bloom;
        this.shadows = shadows;
    }

    public Component displayName() {
        return Component.translatable("vanillashader.profile." + name().toLowerCase(java.util.Locale.ROOT));
    }

    public Profile next() {
        Profile[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    QualityLevels levels() {
        return new QualityLevels(ambientOcclusion, reflections, godRays, bloom, shadows);
    }

    record QualityLevels(int ambientOcclusion, int reflections, int godRays, int bloom, int shadows) {
    }
}
