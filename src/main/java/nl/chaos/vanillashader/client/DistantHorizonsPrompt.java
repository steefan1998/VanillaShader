package nl.chaos.vanillashader.client;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import nl.chaos.vanillashader.compat.DistantHorizonsCompat;
import nl.chaos.vanillashader.compat.McCompat;
import nl.chaos.vanillashader.config.ShaderConfig;
//? if >=26.1
import net.minecraft.client.gui.layouts.LinearLayout;

/**
 * With Distant Horizons installed, asks once per game start (on the title screen, before any world is played) whether
 * to draw it this time.
 * The answer sets our "Distant Horizons" switch; "don't show again" keeps it and stops asking (the question can be
 * turned back on in the settings).
 */
public final class DistantHorizonsPrompt {
    private static boolean asked;

    private DistantHorizonsPrompt() {
    }

    /** Called every client tick. */
    public static void tick(Minecraft minecraft) {
        if (asked) {
            return;
        }
        // Only at game start: once a world has been played, the title screen after leaving it asks nothing.
        if (minecraft.level != null) {
            asked = true;
            return;
        }
        Screen screen = McCompat.screen();
        if (!(screen instanceof TitleScreen)) {
            return;
        }
        asked = true;
        if (!DistantHorizonsCompat.isLoaded() || !ShaderConfig.DISTANT_HORIZONS_ASK.get()) {
            return;
        }
        // The answer needs the prompt's own checkbox, which does not exist yet when the callback is made.
        Prompt[] prompt = new Prompt[1];
        prompt[0] = new Prompt(enabled -> {
            ShaderConfig.DISTANT_HORIZONS.set(enabled);
            if (prompt[0].dontAskAgain) {
                ShaderConfig.DISTANT_HORIZONS_ASK.set(false);
            }
            ShaderConfig.SPEC.save();
            minecraft.setScreen(screen);
        });
        minecraft.setScreen(prompt[0]);
    }

    /** Minecraft's yes/no screen with a "don't show again" checkbox under the two answers. */
    private static final class Prompt extends ConfirmScreen {
        private boolean dontAskAgain;

        private Prompt(BooleanConsumer callback) {
            super(callback,
                Component.translatable("vanillashader.prompt.distant_horizons.title"),
                Component.translatable("vanillashader.prompt.distant_horizons.message"),
                Component.translatable("vanillashader.prompt.distant_horizons.on"),
                Component.translatable("vanillashader.prompt.distant_horizons.off"));
        }

        //? if >=26.1 {
        @Override
        protected void addButtons(LinearLayout buttonLayout) {
            super.addButtons(buttonLayout);
            layout.addChild(Button.builder(checkboxLabel(), this::toggle).width(204).build());
        }
        //?} else {
        /*@Override
        protected void addButtons(int y) {
            super.addButtons(y);
            addRenderableWidget(Button.builder(checkboxLabel(), this::toggle).bounds(width / 2 - 102, y + 26, 204, 20).build());
        }
        *///?}

        private void toggle(Button button) {
            dontAskAgain = !dontAskAgain;
            button.setMessage(checkboxLabel());
        }

        private Component checkboxLabel() {
            return Component.literal(dontAskAgain ? "☑ " : "☐ ")
                .append(Component.translatable("vanillashader.prompt.distant_horizons.dont_ask"));
        }
    }
}
