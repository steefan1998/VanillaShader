package nl.chaos.vanillashader.config;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config. The profile decides how expensive the effects are; the "look" values
 * (exposure, saturation, ...) apply on top of every profile.
 */
public final class ShaderConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.EnumValue<Profile> PROFILE;

    public static final ModConfigSpec.IntValue AMBIENT_OCCLUSION;
    public static final ModConfigSpec.IntValue REFLECTIONS;
    public static final ModConfigSpec.IntValue GOD_RAYS;
    public static final ModConfigSpec.IntValue BLOOM;
    public static final ModConfigSpec.IntValue SHADOWS;

    public static final ModConfigSpec.IntValue EXPOSURE;
    public static final ModConfigSpec.IntValue SATURATION;
    public static final ModConfigSpec.IntValue CONTRAST;
    public static final ModConfigSpec.IntValue WARMTH;
    public static final ModConfigSpec.IntValue ATMOSPHERE;
    public static final ModConfigSpec.IntValue WATER_REFLECTIVITY;
    public static final ModConfigSpec.IntValue VIGNETTE;

    public static final ModConfigSpec.BooleanValue WEATHER_INTEGRATION;

    // Render scale per profile (OFF always renders at native resolution).
    public static final Map<Profile, ModConfigSpec.EnumValue<RenderScale>> RENDER_SCALE = new EnumMap<>(Profile.class);
    public static final ModConfigSpec.BooleanValue FSR_ENABLED;
    public static final ModConfigSpec.IntValue FSR_SHARPNESS;
    public static final ModConfigSpec.BooleanValue DYNAMIC_LIGHTS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        PROFILE = builder
            .comment("Graphics profile: OFF, LOW, MEDIUM, HIGH, MAX, or CUSTOM to use the values in the 'custom' section.")
            .translation("vanillashader.config.profile")
            .defineEnum("profile", Profile.HIGH);

        builder.comment("Per-effect quality (0 = off, 4 = ultra). Only used when profile = CUSTOM.")
            .translation("vanillashader.config.custom")
            .push("custom");
        AMBIENT_OCCLUSION = builder.translation("vanillashader.config.ambient_occlusion").defineInRange("ambientOcclusion", 3, 0, 4);
        REFLECTIONS = builder.translation("vanillashader.config.reflections").defineInRange("waterReflections", 3, 0, 4);
        GOD_RAYS = builder.translation("vanillashader.config.god_rays").defineInRange("godRays", 3, 0, 4);
        BLOOM = builder.translation("vanillashader.config.bloom").defineInRange("bloom", 3, 0, 4);
        SHADOWS = builder.translation("vanillashader.config.shadows").defineInRange("sunShadows", 3, 0, 4);
        builder.pop();

        builder.comment("Look of the image, applied on every profile. Values are percentages.")
            .translation("vanillashader.config.look")
            .push("look");
        EXPOSURE = builder.translation("vanillashader.config.exposure").defineInRange("exposure", 100, 50, 150);
        SATURATION = builder.translation("vanillashader.config.saturation").defineInRange("saturation", 115, 50, 150);
        CONTRAST = builder.translation("vanillashader.config.contrast").defineInRange("contrast", 110, 50, 150);
        WARMTH = builder.translation("vanillashader.config.warmth").defineInRange("warmth", 100, 0, 200);
        ATMOSPHERE = builder.translation("vanillashader.config.atmosphere").defineInRange("atmosphere", 100, 0, 200);
        WATER_REFLECTIVITY = builder.translation("vanillashader.config.water_reflectivity").defineInRange("waterReflectivity", 100, 0, 150);
        VIGNETTE = builder.translation("vanillashader.config.vignette").defineInRange("vignette", 35, 0, 100);
        builder.pop();

        DYNAMIC_LIGHTS = builder
            .comment("Held and dropped light sources (torches, lanterns, glowstone, ...) and burning entities light up their surroundings.")
            .translation("vanillashader.config.dynamic_lights")
            .define("dynamicLights", true);

        WEATHER_INTEGRATION = builder
            .comment("Use WeatherStory's cloud cover and storm strength (when installed) to dim sun rays, glare and reflections.")
            .translation("vanillashader.config.weather_integration")
            .define("weatherStoryIntegration", true);

        builder.comment("Render the world at a lower resolution and upscale it with AMD FSR 1, set per profile. The HUD always stays sharp.")
            .translation("vanillashader.config.upscaling")
            .push("upscaling");
        FSR_ENABLED = builder
            .comment("Master switch for FSR 1 upscaling. When off, every profile renders at native resolution.")
            .translation("vanillashader.config.fsr_enabled")
            .define("fsrEnabled", true);
        RENDER_SCALE.put(Profile.LOW, builder.translation("vanillashader.config.render_scale.low").defineEnum("renderScaleLow", RenderScale.QUALITY));
        RENDER_SCALE.put(Profile.MEDIUM, builder.translation("vanillashader.config.render_scale.medium").defineEnum("renderScaleMedium", RenderScale.ULTRA_QUALITY));
        RENDER_SCALE.put(Profile.HIGH, builder.translation("vanillashader.config.render_scale.high").defineEnum("renderScaleHigh", RenderScale.NATIVE));
        RENDER_SCALE.put(Profile.MAX, builder.translation("vanillashader.config.render_scale.max").defineEnum("renderScaleMax", RenderScale.NATIVE));
        RENDER_SCALE.put(Profile.CUSTOM, builder.translation("vanillashader.config.render_scale.custom").defineEnum("renderScaleCustom", RenderScale.NATIVE));
        FSR_SHARPNESS = builder
            .comment("FSR 1 sharpening (RCAS) strength in percent, applied after upscaling.")
            .translation("vanillashader.config.fsr_sharpness")
            .defineInRange("fsrSharpness", 80, 0, 100);
        builder.pop();

        SPEC = builder.build();
    }

    private ShaderConfig() {
    }

    /** Effect quality levels currently in effect, resolved from the profile. */
    public static Effects effects() {
        Profile profile = PROFILE.get();
        if (profile == Profile.CUSTOM) {
            return new Effects(true, AMBIENT_OCCLUSION.get(), REFLECTIONS.get(), GOD_RAYS.get(), BLOOM.get(), SHADOWS.get(), renderScale(profile));
        }
        Profile.QualityLevels levels = profile.levels();
        return new Effects(profile != Profile.OFF, levels.ambientOcclusion(), levels.reflections(), levels.godRays(), levels.bloom(), levels.shadows(), renderScale(profile));
    }

    private static RenderScale renderScale(Profile profile) {
        ModConfigSpec.EnumValue<RenderScale> value = RENDER_SCALE.get(profile);
        return value == null || !FSR_ENABLED.get() ? RenderScale.NATIVE : value.get();
    }

    /** Puts every "look" slider back to its default. */
    public static void resetLook() {
        for (ModConfigSpec.IntValue value : List.of(EXPOSURE, SATURATION, CONTRAST, WARMTH, ATMOSPHERE, WATER_REFLECTIVITY, VIGNETTE)) {
            value.set(value.getDefault());
        }
        SPEC.save();
    }

    public static void setProfile(Profile profile) {
        PROFILE.set(profile);
        SPEC.save();
    }

    public record Effects(boolean enabled, int ambientOcclusion, int reflections, int godRays, int bloom, int shadows, RenderScale renderScale) {
    }
}
