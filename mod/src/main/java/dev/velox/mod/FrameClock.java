package dev.velox.mod;

import dev.velox.core.bench.RollingFrameStats;
import dev.velox.mod.bench.BenchmarkController;

/**
 * Measures the interval between successive frames (start of one {@code Minecraft.runTick} to the
 * next), which is what a player perceives as frame time. Called on the render thread only,
 * and allocation-free.
 */
public final class FrameClock {
    public static final RollingFrameStats HUD_STATS = new RollingFrameStats(2048);

    private static long last;
    private static long lastRecompute;

    private FrameClock() {}

    public static void onFrameStart() {
        long now = System.nanoTime();
        if (last != 0) {
            long dt = now - last;
            HUD_STATS.add(dt);
            BenchmarkController.INSTANCE.onFrame(dt);
        }
        last = now;
        // The HUD reads these figures; recomputing 4x per second keeps their cost negligible.
        if (now - lastRecompute > 250_000_000L) {
            lastRecompute = now;
            HUD_STATS.recompute();
        }
    }
}
