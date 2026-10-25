package nl.chaos.vanillashader.client;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import nl.chaos.vanillashader.client.gpu.Gpu;
import nl.chaos.vanillashader.config.ShaderConfig;
import org.slf4j.Logger;

/**
 * Development aid, off unless {@code -Dvanillashader.profilePasses=true}: measures every pass on the GPU with
 * timer queries (and CPU work with nanoTime) and logs the averages plus the FPS every five seconds.
 */
final class PassTimer {
    static final boolean ENABLED = Boolean.getBoolean("vanillashader.profilePasses");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long REPORT_NANOS = 5_000_000_000L;

    private static final Deque<Pending> PENDING = new ArrayDeque<>();
    private static final Map<String, double[]> TOTALS = new TreeMap<>();
    private static long lastReport = System.nanoTime();
    private static int frames;

    private PassTimer() {
    }

    static Timing gpu(String label) {
        if (!ENABLED) {
            return () -> {
            };
        }
        Gpu.Timer timer = Gpu.startTimer();
        return () -> {
            timer.stop();
            PENDING.add(new Pending(label, timer));
        };
    }

    static void cpu(String label, long startNanos) {
        if (ENABLED) {
            add(label + " (cpu)", System.nanoTime() - startNanos);
        }
    }

    /** Collects finished queries and reports; call once per frame. */
    static void endFrame() {
        if (!ENABLED) {
            return;
        }
        frames++;
        while (!PENDING.isEmpty()) {
            Pending pending = PENDING.peek();
            OptionalLong value = pending.timer.nanos();
            if (value.isEmpty()) {
                break;
            }
            PENDING.poll();
            add(pending.label, value.getAsLong());
            pending.timer.close();
        }
        long now = System.nanoTime();
        if (now - lastReport >= REPORT_NANOS) {
            StringBuilder report = new StringBuilder("Vanilla Shader pass timings, profile ").append(ShaderConfig.PROFILE.get()).append(", over ").append(frames).append(" frames, ")
                .append(Minecraft.getInstance().getFps()).append(" fps:");
            double total = 0.0;
            for (Map.Entry<String, double[]> entry : TOTALS.entrySet()) {
                double ms = entry.getValue()[0] / Math.max(1.0, frames) / 1.0E6;
                if (!entry.getKey().endsWith("(cpu)")) {
                    total += ms;
                }
                report.append(String.format("%n  %-28s %6.3f ms", entry.getKey(), ms));
            }
            report.append(String.format("%n  %-28s %6.3f ms", "total gpu", total));
            LOGGER.info(report.toString());
            TOTALS.clear();
            frames = 0;
            lastReport = now;
        }
    }

    private static void add(String label, long nanos) {
        TOTALS.computeIfAbsent(label, key -> new double[1])[0] += nanos;
    }

    interface Timing extends AutoCloseable {
        @Override
        void close();
    }

    private record Pending(String label, Gpu.Timer timer) {
    }
}
