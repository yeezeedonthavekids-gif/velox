package dev.velox.core.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GcMonitorTest {
    @Test
    void excludesConcurrentCycleBeans() {
        assertFalse(GcMonitor.countsPauses("ZGC Major Cycles"));
        assertTrue(GcMonitor.countsPauses("ZGC Major Pauses"));
        assertTrue(GcMonitor.countsPauses("G1 Young Generation"));
    }

    @Test
    void capturesExplicitCollections() throws Exception {
        try (GcMonitor m = new GcMonitor(1024).start()) {
            for (int i = 0; i < 3; i++) System.gc();
            // Notifications are delivered asynchronously.
            long deadline = System.currentTimeMillis() + 5000;
            while (m.summary().pauses() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertTrue(m.summary().pauses() > 0, "expected at least one recorded pause: " + m);
        }
    }

    @Test
    void chunkTrackerReportsMilestones() {
        ChunkLoadTracker t = new ChunkLoadTracker();
        t.begin(0);
        for (int i = 0; i <= 10; i++) t.sample(i * 100_000_000L, i * 10); // 0..100 chunks over 1 s
        var r = t.result();
        assertEquals(100, r.maxChunks());
        assertEquals(500, r.msTo50Pct());
        assertEquals(900, r.msTo90Pct());
        assertEquals(1000, r.msTo100Pct());
    }
}
