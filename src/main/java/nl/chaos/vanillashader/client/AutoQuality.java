package nl.chaos.vanillashader.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import nl.chaos.vanillashader.compat.McCompat;
import nl.chaos.vanillashader.config.Profile;
import nl.chaos.vanillashader.config.RenderScale;
import nl.chaos.vanillashader.config.ShaderConfig;
import org.slf4j.Logger;

/**
 * The Auto profile: measures the frame rate while playing and walks a ladder of quality steps (effect levels plus
 * FSR render scale) until the target frame rate from the config is met.
 *
 * <p>It steps down quickly when the frame rate stays below the target and up slowly when it holds the target. A step
 * up that turns out too heavy is not tried again for a minute, so it does not keep bouncing between two steps. With
 * the frame rate capped at or below the target (Minecraft's limit, or vsync), holding the cap counts as meeting it.
 */
public final class AutoQuality {
    private static final Logger LOGGER = LogUtils.getLogger();

    private record Step(Profile levels, RenderScale scale) {
    }

    // Lightest first. Without FSR the render scale cannot change, so only the effect levels do.
    private static final Step[] FSR_LADDER = {
        new Step(Profile.LOW, RenderScale.PERFORMANCE),
        new Step(Profile.LOW, RenderScale.QUALITY),
        new Step(Profile.MEDIUM, RenderScale.QUALITY),
        new Step(Profile.MEDIUM, RenderScale.ULTRA_QUALITY),
        new Step(Profile.HIGH, RenderScale.ULTRA_QUALITY),
        new Step(Profile.HIGH, RenderScale.NATIVE),
        new Step(Profile.MAX, RenderScale.NATIVE),
    };
    private static final Step[] NATIVE_LADDER = {
        new Step(Profile.LOW, RenderScale.NATIVE),
        new Step(Profile.MEDIUM, RenderScale.NATIVE),
        new Step(Profile.HIGH, RenderScale.NATIVE),
        new Step(Profile.MAX, RenderScale.NATIVE),
    };

    private static final boolean DEV_CAPTURE = Boolean.getBoolean("vanillashader.devCapture");
    private static final long SECOND = 1_000_000_000L;
    // Frames this long are loading hitches or a paused window, not a sign of the effects being too heavy.
    private static final long HITCH = SECOND / 2;
    // After a change: the new settings get this long before the frame rate counts again, and the next this long
    // before it is compared with the one before the change.
    private static final long SETTLE = 3 * SECOND;
    private static final long JUDGE = 6 * SECOND;
    private static final long DOWN_AFTER = 2 * SECOND;
    private static final long UP_AFTER = 10 * SECOND;
    // A step up followed this soon by a step down was too heavy: it is blocked, twice as long each time it fails.
    private static final long FAILED_UP = 15 * SECOND;
    private static final long BLOCK = 60 * SECOND;
    // A step down that gains less than this was not what held the frame rate back (the CPU or a frame limit was):
    // it is undone and nothing lower is tried for a while, instead of sliding to the lowest step for nothing.
    private static final double USEFUL_GAIN = 1.04;
    private static final long FLOOR_TIME = 90 * SECOND;

    private static Step[] ladder = FSR_LADDER;
    private static int step = FSR_LADDER.length / 2;
    private static final long[] blockedUntil = new long[FSR_LADDER.length];
    private static final int[] failures = new int[FSR_LADDER.length];
    private static double averageNanos;
    private static long lastFrame;
    private static long lastChange;
    private static boolean lastWasUp;
    private static long belowSince;
    private static long aboveSince;
    // The frame rate just before the last step down, to see whether it helped (0 = nothing to judge).
    private static double fpsBeforeDown;
    private static int floor;
    private static long floorUntil;

    private AutoQuality() {
    }

    static void init() {
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> onFrame());
    }

    /** The effect levels and render scale of the current step. */
    public static ShaderConfig.Effects effects() {
        Step current = currentLadder()[step];
        ShaderConfig.Effects levels = ShaderConfig.effectsOf(current.levels());
        return new ShaderConfig.Effects(true, levels.ambientOcclusion(), levels.reflections(), levels.godRays(), levels.bloom(), levels.shadows(), current.scale());
    }

    /** Ray tracing (when turned on) only runs on the top steps; below them it is the first thing to go. */
    public static boolean allowsRayTracing() {
        return currentLadder()[step].levels() == Profile.MAX;
    }

    private static Step[] currentLadder() {
        Step[] wanted = ShaderConfig.FSR_ENABLED.get() && ShaderRenderer.fsrUsable() ? FSR_LADDER : NATIVE_LADDER;
        if (wanted != ladder) {
            // Keep roughly the same place on the other ladder.
            step = Math.round(step * (wanted.length - 1) / (float) (ladder.length - 1));
            ladder = wanted;
            java.util.Arrays.fill(blockedUntil, 0L);
            java.util.Arrays.fill(failures, 0);
            floorUntil = 0;
            fpsBeforeDown = 0;
        }
        return ladder;
    }

    private static void onFrame() {
        long now = System.nanoTime();
        long frame = now - lastFrame;
        lastFrame = now;
        Minecraft minecraft = Minecraft.getInstance();
        // Only gameplay counts: menus, an unfocused window (which Minecraft throttles) and loading say nothing. Dev
        // capture runs count unfocused too, since their window usually sits behind the editor.
        if (ShaderConfig.PROFILE.get() != Profile.AUTO || minecraft.level == null || McCompat.screen() != null
            || (!minecraft.isWindowActive() && !DEV_CAPTURE) || !ShaderRenderer.active() || frame <= 0 || frame > HITCH) {
            belowSince = 0;
            aboveSince = 0;
            averageNanos = 0;
            return;
        }
        averageNanos = averageNanos == 0 ? frame : averageNanos * 0.95 + frame * 0.05;
        if (now - lastChange < SETTLE) {
            return;
        }
        double fps = SECOND / averageNanos;
        double target = effectiveTarget(minecraft);
        Step[] steps = currentLadder();

        // Did the last step down actually buy frame rate? Nothing else changes until that is known.
        if (fpsBeforeDown > 0.0 && now - lastChange < JUDGE) {
            return;
        }
        if (fpsBeforeDown > 0.0) {
            boolean helped = fps >= fpsBeforeDown * USEFUL_GAIN;
            double before = fpsBeforeDown;
            fpsBeforeDown = 0.0;
            if (!helped && step < steps.length - 1) {
                LOGGER.info("Vanilla Shader Auto: stepping down gained nothing ({} -> {} fps), going back", Math.round(before), Math.round(fps));
                floor = step + 1;
                floorUntil = now + FLOOR_TIME;
                change(step + 1, false, now, fps, target);
                return;
            }
        }
        int lowest = floorUntil > now ? floor : 0;

        if (fps < target * 0.9) {
            aboveSince = 0;
            if (belowSince == 0) {
                belowSince = now;
            } else if (now - belowSince >= DOWN_AFTER && step > lowest) {
                if (lastWasUp && now - lastChange < FAILED_UP) {
                    failures[step] = Math.min(failures[step] + 1, 5);
                    blockedUntil[step] = now + (BLOCK << (failures[step] - 1));
                }
                double before = fps;
                change(step - 1, false, now, fps, target);
                fpsBeforeDown = before;
            }
        } else if (fps >= target * 0.97) {
            belowSince = 0;
            if (aboveSince == 0) {
                aboveSince = now;
            } else if (now - aboveSince >= UP_AFTER && step < steps.length - 1 && blockedUntil[step + 1] < now) {
                change(step + 1, true, now, fps, target);
            }
        } else {
            belowSince = 0;
            aboveSince = 0;
        }
    }

    // The configured target, or what the frame rate cannot get past anyway when that is lower: Minecraft's own limit
    // (260 means unlimited) and, with vsync, the monitor's refresh rate.
    private static double effectiveTarget(Minecraft minecraft) {
        int target = ShaderConfig.AUTO_TARGET_FPS.get();
        int limit = minecraft.options.framerateLimit().get();
        if (limit > 0 && limit < 260) {
            target = Math.min(target, limit);
        }
        int refresh = McCompat.refreshRate();
        if (minecraft.options.enableVsync().get() && refresh > 0) {
            target = Math.min(target, refresh);
        }
        return target;
    }

    private static void change(int newStep, boolean up, long now, double fps, double target) {
        Step from = ladder[step];
        step = newStep;
        lastWasUp = up;
        lastChange = now;
        belowSince = 0;
        aboveSince = 0;
        fpsBeforeDown = 0.0;
        // The new step needs a fresh average; the old one belongs to the previous settings.
        averageNanos = 0;
        Step to = ladder[step];
        LOGGER.info("Vanilla Shader Auto: {} fps for a target of {}, {} {} -> {} {}",
            Math.round(fps), Math.round(target), from.levels(), from.scale(), to.levels(), to.scale());
    }
}
