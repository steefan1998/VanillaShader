package nl.chaos.vanillashader;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import nl.chaos.vanillashader.client.DevCapture;
import nl.chaos.vanillashader.client.ShaderRenderer;
import nl.chaos.vanillashader.config.Profile;
import nl.chaos.vanillashader.config.ShaderConfig;
import org.lwjgl.glfw.GLFW;

@Mod(value = VanillaShader.MOD_ID, dist = Dist.CLIENT)
public final class VanillaShader {
    public static final String MOD_ID = "vanillashader";

    private static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(id("keys"));
    private static final KeyMapping CYCLE_PROFILE = new KeyMapping(
        "key.vanillashader.cycle_profile", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, KEY_CATEGORY
    );

    public VanillaShader(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ShaderConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modBus.addListener(VanillaShader::registerKeys);
        NeoForge.EVENT_BUS.addListener(VanillaShader::clientTick);
        ShaderRenderer.init();
        DevCapture.init();
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(KEY_CATEGORY);
        event.register(CYCLE_PROFILE);
    }

    private static void clientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (CYCLE_PROFILE.consumeClick()) {
            Profile next = ShaderConfig.PROFILE.get().next();
            ShaderConfig.setProfile(next);
            if (minecraft.player != null) {
                minecraft.player.sendOverlayMessage(Component.translatable("vanillashader.message.profile", next.displayName()));
            }
        }
    }
}
