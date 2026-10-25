package dev.velox.core.bench;

import java.util.Arrays;

/**
 * Tracks how quickly client-side chunks arrive after a reference point (world join or teleport).
 *
 * <p>Samples (time, loaded chunk count) are appended into primitive arrays. The report states
 * the time to reach 50%, 90% and 100% of the highest count seen, which is comparable across
 * runs at the same render distance without needing to know the exact expected chunk total.
 */
public final class ChunkLoadTracker {
    private long[] timesMs = new long[256];
    private int[] counts = new int[256];
    private int size;
    private long originNanos = -1;

    public void begin(long nowNanos) {
        originNanos = nowNanos;
        size = 0;
    }

    public boolean active() {
        return originNanos >= 0;
    }

    public void sample(long nowNanos, int loadedChunks) {
        if (originNanos < 0) return;
        if (size == timesMs.length) {
            timesMs = Arrays.copyOf(timesMs, size * 2);
            counts = Arrays.copyOf(counts, size * 2);
        }
        timesMs[size] = (nowNanos - originNanos) / 1_000_000L;
        counts[size] = loadedChunks;
        size++;
    }

    public record Result(int maxChunks, long msTo50Pct, long msTo90Pct, long msTo100Pct, int samples) {}

    public Result result() {
        int max = 0;
        for (int i = 0; i < size; i++) max = Math.max(max, counts[i]);
        return new Result(max, timeTo(max * 0.5), timeTo(max * 0.9), timeTo(max), size);
    }

    private long timeTo(double target) {
        for (int i = 0; i < size; i++) {
            if (counts[i] >= target && target > 0) return timesMs[i];
        }
        return -1;
    }
}
