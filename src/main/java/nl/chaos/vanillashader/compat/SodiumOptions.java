package nl.chaos.vanillashader.compat;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.function.Function;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.common.ModConfigSpec;
import nl.chaos.vanillashader.VanillaShader;
import nl.chaos.vanillashader.config.Profile;
import nl.chaos.vanillashader.config.RenderScale;
import nl.chaos.vanillashader.config.ShaderConfig;

/**
 * Adds a "Vanilla Shader" page to Sodium's Video Settings screen. Sodium finds this class through the
 * {@code sodium:config_api_user} mod property, so it is only ever loaded when Sodium is installed.
 */
public final class SodiumOptions implements ConfigEntryPoint {
    private static final Identifier PROFILE = VanillaShader.id("profile");
    private static final Identifier FSR_ENABLED = VanillaShader.id("fsr_enabled");

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        Function<ConfigState, Boolean> isCustom = state -> state.readEnumOption(PROFILE, Profile.class) == Profile.CUSTOM;

        OptionGroupBuilder profileGroup = builder.createOptionGroup()
            .setName(Component.translatable("vanillashader.config.profile"))
            .addOption(builder.createEnumOption(PROFILE, Profile.class)
                .setName(Component.translatable("vanillashader.config.profile"))
                .setTooltip(Component.translatable("vanillashader.config.profile.tooltip"))
                .setElementNameProvider(Profile::displayName)
                .setImpact(OptionImpact.VARIES)
                .setDefaultValue(Profile.HIGH)
                .setStorageHandler(ShaderConfig.SPEC::save)
                .setBinding(ShaderConfig.PROFILE::set, ShaderConfig.PROFILE::get))
            .addOption(builder.createBooleanOption(VanillaShader.id("dynamic_lights"))
                .setName(Component.translatable("vanillashader.config.dynamic_lights"))
                .setTooltip(Component.translatable("vanillashader.config.dynamic_lights.tooltip"))
                .setImpact(OptionImpact.LOW)
                .setDefaultValue(true)
                .setStorageHandler(ShaderConfig.SPEC::save)
                .setBinding(ShaderConfig.DYNAMIC_LIGHTS::set, ShaderConfig.DYNAMIC_LIGHTS::get));

        OptionGroupBuilder customGroup = builder.createOptionGroup()
            .setName(Component.translatable("vanillashader.config.custom"))
            .addOption(quality(builder, "ambient_occlusion", ShaderConfig.AMBIENT_OCCLUSION, OptionImpact.HIGH, isCustom))
            .addOption(quality(builder, "reflections", ShaderConfig.REFLECTIONS, OptionImpact.MEDIUM, isCustom))
            .addOption(quality(builder, "god_rays", ShaderConfig.GOD_RAYS, OptionImpact.MEDIUM, isCustom))
            .addOption(quality(builder, "bloom", ShaderConfig.BLOOM, OptionImpact.LOW, isCustom))
            .addOption(quality(builder, "shadows", ShaderConfig.SHADOWS, OptionImpact.MEDIUM, isCustom));

        OptionGroupBuilder upscalingGroup = builder.createOptionGroup()
            .setName(Component.translatable("vanillashader.config.upscaling"));
        upscalingGroup.addOption(builder.createBooleanOption(FSR_ENABLED)
            .setName(Component.translatable("vanillashader.config.fsr_enabled"))
            .setTooltip(Component.translatable("vanillashader.config.fsr_enabled.tooltip"))
            .setImpact(OptionImpact.HIGH)
            .setDefaultValue(true)
            .setStorageHandler(ShaderConfig.SPEC::save)
            .setBinding(ShaderConfig.FSR_ENABLED::set, ShaderConfig.FSR_ENABLED::get));
        Function<ConfigState, Boolean> fsrOn = state -> state.readBooleanOption(FSR_ENABLED);
        for (Profile profile : new Profile[] {Profile.LOW, Profile.MEDIUM, Profile.HIGH, Profile.MAX, Profile.CUSTOM}) {
            ModConfigSpec.EnumValue<RenderScale> value = ShaderConfig.RENDER_SCALE.get(profile);
            String key = "render_scale." + profile.name().toLowerCase(Locale.ROOT);
            upscalingGroup.addOption(builder.createEnumOption(VanillaShader.id(key), RenderScale.class)
                .setName(Component.translatable("vanillashader.config." + key))
                .setTooltip(Component.translatable("vanillashader.config.render_scale.tooltip"))
                .setElementNameProvider(RenderScale::displayName)
                .setImpact(OptionImpact.HIGH)
                .setDefaultValue(value.getDefault())
                .setEnabledProvider(fsrOn, FSR_ENABLED)
                .setStorageHandler(ShaderConfig.SPEC::save)
                .setBinding(value::set, value::get));
        }
        upscalingGroup.addOption(percent(builder, "fsr_sharpness", ShaderConfig.FSR_SHARPNESS, 0, 100).setEnabledProvider(fsrOn, FSR_ENABLED));

        OptionGroupBuilder lookGroup = builder.createOptionGroup()
            .setName(Component.translatable("vanillashader.config.look"))
            .addOption(builder.createExternalButtonOption(VanillaShader.id("reset_look"))
                .setName(Component.translatable("vanillashader.config.reset_look"))
                .setTooltip(Component.translatable("vanillashader.config.reset_look.tooltip"))
                .setScreenConsumer(SodiumOptions::confirmResetLook))
            .addOption(percent(builder, "exposure", ShaderConfig.EXPOSURE, 50, 150))
            .addOption(percent(builder, "saturation", ShaderConfig.SATURATION, 50, 150))
            .addOption(percent(builder, "contrast", ShaderConfig.CONTRAST, 50, 150))
            .addOption(percent(builder, "warmth", ShaderConfig.WARMTH, 0, 200))
            .addOption(percent(builder, "atmosphere", ShaderConfig.ATMOSPHERE, 0, 200))
            .addOption(percent(builder, "water_reflectivity", ShaderConfig.WATER_REFLECTIVITY, 0, 150))
            .addOption(percent(builder, "vignette", ShaderConfig.VIGNETTE, 0, 100))
            .addOption(builder.createBooleanOption(VanillaShader.id("weather_integration"))
                .setName(Component.translatable("vanillashader.config.weather_integration"))
                .setTooltip(Component.translatable("vanillashader.config.weather_integration.tooltip"))
                .setImpact(OptionImpact.LOW)
                .setDefaultValue(true)
                .setStorageHandler(ShaderConfig.SPEC::save)
                .setBinding(ShaderConfig.WEATHER_INTEGRATION::set, ShaderConfig.WEATHER_INTEGRATION::get));

        builder.registerOwnModOptions()
            .setName("Vanilla Shader")
            .addPage(builder.createOptionPage()
                .setName(Component.translatable("vanillashader.config.page.quality"))
                .addOptionGroup(profileGroup)
                .addOptionGroup(customGroup)
                .addOptionGroup(upscalingGroup))
            .addPage(builder.createOptionPage()
                .setName(Component.translatable("vanillashader.config.look"))
                .addOptionGroup(lookGroup));
    }

    private static void confirmResetLook(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ConfirmScreen(
            reset -> {
                if (reset) {
                    ShaderConfig.resetLook();
                }
                // Reopen Sodium's settings so the sliders show the new values.
                minecraft.setScreen(reset ? net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen.createScreen(screenBefore(parent)) : parent);
            },
            Component.translatable("vanillashader.config.reset_look"),
            Component.translatable("vanillashader.config.reset_look.confirm")
        ));
    }

    // The screen Sodium's settings were opened from, so "Done" after the reopen still leads back there.
    private static Screen screenBefore(Screen sodiumScreen) {
        try {
            Field field = sodiumScreen.getClass().getDeclaredField("prevScreen");
            field.setAccessible(true);
            Screen previous = (Screen) field.get(sodiumScreen);
            return previous != null ? previous : sodiumScreen;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return sodiumScreen;
        }
    }

    private static IntegerOptionBuilder quality(
        ConfigBuilder builder, String key, ModConfigSpec.IntValue value, OptionImpact impact, Function<ConfigState, Boolean> isCustom
    ) {
        return builder.createIntegerOption(VanillaShader.id(key))
            .setName(Component.translatable("vanillashader.config." + key))
            .setTooltip(Component.translatable("vanillashader.config." + key + ".tooltip"))
            .setRange(0, 4, 1)
            .setValueFormatter(level -> Component.translatable("vanillashader.quality." + level))
            .setImpact(impact)
            .setDefaultValue(value.getDefault())
            .setEnabledProvider(isCustom, PROFILE)
            .setStorageHandler(ShaderConfig.SPEC::save)
            .setBinding(value::set, value::get);
    }

    private static IntegerOptionBuilder percent(ConfigBuilder builder, String key, ModConfigSpec.IntValue value, int min, int max) {
        return builder.createIntegerOption(VanillaShader.id(key))
            .setName(Component.translatable("vanillashader.config." + key))
            .setTooltip(Component.translatable("vanillashader.config." + key + ".tooltip"))
            .setRange(min, max, 5)
            .setValueFormatter(v -> Component.literal(v + "%"))
            .setImpact(OptionImpact.LOW)
            .setDefaultValue(value.getDefault())
            .setStorageHandler(ShaderConfig.SPEC::save)
            .setBinding(value::set, value::get);
    }
}
