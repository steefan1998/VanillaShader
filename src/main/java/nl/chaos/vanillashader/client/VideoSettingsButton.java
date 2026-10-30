package nl.chaos.vanillashader.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import nl.chaos.vanillashader.VanillaShader;

/**
 * Without Sodium (which shows our settings as a page of its own), Minecraft's Video Settings get a "Vanilla Shader"
 * button in the top right corner that opens our settings, coming back to the Video Settings when done.
 */
public final class VideoSettingsButton {
    private VideoSettingsButton() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(VideoSettingsButton::onInit);
    }

    private static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof VideoSettingsScreen screen) || ModList.get().isLoaded("sodium")) {
            return;
        }
        event.addListener(Button.builder(Component.literal("Vanilla Shader..."), button -> open(screen))
            .bounds(screen.width - 110, 6, 104, 20)
            .build());
    }

    private static void open(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        ModList.get().getModContainerById(VanillaShader.MOD_ID).ifPresent(container ->
            container.getCustomExtension(IConfigScreenFactory.class).ifPresent(factory ->
                minecraft.setScreen(factory.createScreen(container, parent))));
    }
}
