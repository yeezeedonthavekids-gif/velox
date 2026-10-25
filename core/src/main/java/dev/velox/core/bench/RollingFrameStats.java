package dev.velox.core.bench;

import java.util.Arrays;

/**
 * Live frame statistics for the HUD over the most recent frames.
 *
 * <p>Allocation-free after construction: frames go into a ring buffer, and {@link #recompute()}
 * sorts into a preallocated scratch array. Recompute a few times per second, not every frame;
 * sorting a 2048-entry window costs microseconds.
 */
public final class RollingFrameStats {
    private final long[] ring;
    private final long[] scratch;
    private int head;
    private int size;

    private double avgFps;
    private double lastFrameMs;
    private double low1PctFps;
    private double low01PctFps;
    private double maxFrameMs;

    public RollingFrameStats(int window) {
        this.ring = new long[window];
        this.scratch = new long[window];
    }

    public void add(long frameNanos) {
        ring[head] = frameNanos;
        head = (head + 1) % ring.length;
        if (size < ring.length) size++;
        lastFrameMs = frameNanos / 1e6;
    }

    public void recompute() {
        if (size == 0) return;
        long total = 0;
        for (int i = 0; i < size; i++) {
            long v = ring[i];
            scratch[i] = v;
            total += v;
        }
        Arrays.sort(scratch, 0, size);
        avgFps = size / (total / 1e9);
        maxFrameMs = scratch[size - 1] / 1e6;
        low1PctFps = worst(0.01);
        low01PctFps = worst(0.001);
    }

    private double worst(double fraction) {
        int k = Math.max(1, (int) Math.round(size * fraction));
        long sum = 0;
        for (int i = size - k; i < size; i++) sum += scratch[i];
        return 1e9 / ((double) sum / k);
    }

    /** Frame time {@code agoFrames} frames back (0 = latest), in nanoseconds; 0 if absent. */
    public long frameAgo(int agoFrames) {
        if (agoFrames >= size) return 0;
        int idx = Math.floorMod(head - 1 - agoFrames, ring.length);
        return ring[idx];
    }

    public int size() { return size; }
    public int window() { return ring.length; }
    public double avgFps() { return avgFps; }
    public double lastFrameMs() { return lastFrameMs; }
    public double low1PctFps() { return low1PctFps; }
    public double low01PctFps() { return low01PctFps; }
    public double maxFrameMs() { return maxFrameMs; }
}
