package dev.velox.core.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrameStatsTest {
    private static final long MS = 1_000_000L;

    @Test
    void constantFrameTimes() {
        long[] f = new long[1000];
        java.util.Arrays.fill(f, 4 * MS);
        FrameStats s = FrameStats.of(f);
        assertEquals(250.0, s.avgFps(), 1e-9);
        assertEquals(250.0, s.low1PctFps(), 1e-9);
        assertEquals(4.0, s.p99FrameMs(), 1e-9);
        assertEquals(0, s.spikes());
        assertEquals(4.0, s.durationSec(), 1e-9);
    }

    @Test
    void avgFpsIsTimeWeightedNotMeanOfInstantaneousFps() {
        // One 1 ms frame and one 9 ms frame: 2 frames in 10 ms = 200 FPS.
        // Averaging instantaneous FPS would give (1000 + 111.1) / 2 = 555 FPS, which is wrong.
        FrameStats s = FrameStats.of(new long[]{1 * MS, 9 * MS});
        assertEquals(200.0, s.avgFps(), 1e-9);
    }

    @Test
    void lowsAverageTheWorstFrames() {
        long[] f = new long[1000];
        java.util.Arrays.fill(f, 5 * MS);
        for (int i = 0; i < 10; i++) f[i * 100] = 20 * MS; // exactly 1% of frames at 20 ms
        f[500] = 40 * MS; // one of those ten becomes the single worst frame
        FrameStats s = FrameStats.of(f);
        // worst 10 frames: 40 + 9*20 = 220 ms → mean 22 ms → 45.45 FPS
        assertEquals(1000.0 / 22.0, s.low1PctFps(), 1e-6);
        // worst 0.1% = 1 frame = 40 ms → 25 FPS
        assertEquals(25.0, s.low01PctFps(), 1e-9);
        assertEquals(10, s.spikes());
        assertEquals(40.0, s.maxFrameMs(), 1e-9);
    }

    @Test
    void percentileInterpolates() {
        long[] sorted = {10, 20, 30, 40};
        assertEquals(25.0, FrameStats.percentile(sorted, 0.5), 1e-9);
        assertEquals(40.0, FrameStats.percentile(sorted, 1.0), 1e-9);
        assertEquals(10.0, FrameStats.percentile(sorted, 0.0), 1e-9);
    }

    @Test
    void emptyIsZero() {
        assertEquals(0, FrameStats.of(new long[0]).frames());
    }

    @Test
    void recorderDropsWhenFullInsteadOfGrowing() {
        FrameTimeRecorder r = new FrameTimeRecorder(3);
        for (int i = 0; i < 5; i++) r.record(i);
        assertEquals(3, r.count());
        assertEquals(2, r.dropped());
        assertArrayEquals(new long[]{0, 1, 2}, r.snapshot());
    }

    @Test
    void rollingStatsMatchBatchStatsOverWindow() {
        RollingFrameStats r = new RollingFrameStats(500);
        long[] all = new long[2000];
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < all.length; i++) {
            all[i] = (2 + rnd.nextInt(10)) * MS;
            r.add(all[i]);
        }
        r.recompute();
        FrameStats batch = FrameStats.of(java.util.Arrays.copyOfRange(all, 1500, 2000));
        assertEquals(batch.avgFps(), r.avgFps(), 1e-9);
        assertEquals(batch.low1PctFps(), r.low1PctFps(), 1e-9);
        assertEquals(all[1999], r.frameAgo(0));
        assertEquals(all[1990], r.frameAgo(9));
        assertEquals(0, r.frameAgo(600));
    }
}
