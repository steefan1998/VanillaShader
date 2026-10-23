package nl.chaos.vanillashader.client;

import com.mojang.logging.LogUtils;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import nl.chaos.vanillashader.config.Profile;
import nl.chaos.vanillashader.config.ShaderConfig;
import org.slf4j.Logger;

/**
 * Development aid, off unless {@code -Dvanillashader.devCapture=true} is set. Once a singleplayer world is
 * loaded it plays through the scenes in {@code vanillashader.devCapture.commands} (scenes separated by '|',
 * commands within a scene by ';') and saves one screenshot per scene and profile to
 * {@code screenshots/vanillashader_<scene>_<profile>.png}. Shots are taken right after the level and our
 * post-processing are drawn, so the hand and HUD are never in them.
 *
 * <p>A command prefixed with {@code client:} runs as a client command (e.g. WeatherStory's previews), and one
 * prefixed with {@code each:} runs again shortly before every shot (e.g. to summon lightning for each profile).
 */
public final class DevCapture {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Profile[] PROFILES = profiles();
    private static final int SETTLE_TICKS = 200;
    private static final int TICKS_PER_SHOT = Integer.getInteger("vanillashader.devCapture.ticksPerShot", 30);

    private static String[] scenes = new String[0];
    private static int ticks;
    private static int scene;
    private static int shot;
    private static boolean pendingShot;
    private static Profile originalProfile;

    private DevCapture() {
    }

    // -Dvanillashader.devCapture.profiles=MAX,OFF changes which profiles are shot, and in what order.
    private static Profile[] profiles() {
        String list = System.getProperty("vanillashader.devCapture.profiles", "OFF,LOW,MEDIUM,HIGH,MAX");
        return Arrays.stream(list.split(",")).map(name -> Profile.valueOf(name.trim().toUpperCase(Locale.ROOT))).toArray(Profile[]::new);
    }

    public static void init() {
        if (Boolean.getBoolean("vanillashader.devCapture")) {
            scenes = System.getProperty("vanillashader.devCapture.commands", "").split("\\|");
            NeoForge.EVENT_BUS.addListener(DevCapture::tick);
        }
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (minecraft.player == null || server == null || scene >= scenes.length) {
            return;
        }
        if (ticks < 0) {
            ticks++;
            return;
        }
        if (ticks == 0) {
            if (originalProfile == null) {
                originalProfile = ShaderConfig.PROFILE.get();
            }
            // Otherwise any mouse movement over the window turns the camera between shots.
            minecraft.mouseHandler.releaseMouse();
            runCommands(minecraft, server, false);
        }
        ticks++;
        if (ticks >= SETTLE_TICKS - 3 && (ticks - SETTLE_TICKS + 3) % TICKS_PER_SHOT == 0) {
            runCommands(minecraft, server, true);
        }
        if (ticks < SETTLE_TICKS || (ticks - SETTLE_TICKS) % TICKS_PER_SHOT != 0) {
            return;
        }
        if (shot < PROFILES.length) {
            ShaderConfig.PROFILE.set(PROFILES[shot]);
            pendingShot = true;
        }
    }

    private static void runCommands(Minecraft minecraft, IntegratedServer server, boolean beforeShot) {
        for (String entry : scenes[scene].split(";")) {
            String command = entry.trim();
            boolean each = command.startsWith("each:");
            if (each) {
                command = command.substring(5);
            }
            if (command.isBlank() || (beforeShot && !each)) {
                continue;
            }
            if (command.startsWith("client:")) {
                ClientCommandHandler.runCommand(command.substring(7));
            } else {
                String serverCommand = command;
                server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), serverCommand));
            }
        }
    }

    /** Called after the level and our effects are drawn, before the hand and GUI. */
    static void afterLevel() {
        if (!pendingShot) {
            return;
        }
        pendingShot = false;
        Minecraft minecraft = Minecraft.getInstance();
        String name = "vanillashader_" + scene + "_" + PROFILES[shot].name().toLowerCase(Locale.ROOT) + ".png";
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), 1, message -> LOGGER.info("Dev capture: {}", message.getString()));
        shot++;
        if (shot >= PROFILES.length) {
            shot = 0;
            // Screenshots are read back asynchronously; give the last one time before the next scene moves the camera.
            ticks = -40;
            scene++;
            if (scene >= scenes.length) {
                ShaderConfig.PROFILE.set(originalProfile);
                LOGGER.info("Dev capture finished");
            }
        }
    }
}
