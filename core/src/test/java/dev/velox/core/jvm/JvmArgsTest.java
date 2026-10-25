package dev.velox.core.jvm;

import dev.velox.core.hw.HardwareProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JvmArgsTest {
    static final HardwareProfile PC = new HardwareProfile(16, 15_700, "NVIDIA GeForce RTX 5060", -1, -1);

    @Test
    void heapIsModestAndNeverOverHalfOfRam() {
        assertEquals(4096, JvmArgs.recommendedHeapMb(15_700, 12));
        assertEquals(6144, JvmArgs.recommendedHeapMb(15_700, 32));
        assertEquals(2000, JvmArgs.recommendedHeapMb(4_000, 8), "capped at half of RAM");
        assertEquals(1024, JvmArgs.recommendedHeapMb(1_500, 8));
        for (long ram = 1000; ram < 200_000; ram += 777) {
            assertTrue(JvmArgs.recommendedHeapMb(ram, 32) <= Math.max(1024, ram / 2));
        }
    }

    @Test
    void gcChoice() {
        assertEquals(JvmArgs.Gc.ZGC, JvmArgs.recommendedGc(PC, 4096, 25, false));
        assertEquals(JvmArgs.Gc.G1, JvmArgs.recommendedGc(PC, 4096, 25, true), "Max FPS prefers throughput");
        assertEquals(JvmArgs.Gc.G1, JvmArgs.recommendedGc(PC, 2048, 25, false));
        assertEquals(JvmArgs.Gc.G1, JvmArgs.recommendedGc(new HardwareProfile(4, 16_000, null, -1, -1), 4096, 25, false));
        assertFalse(JvmArgs.plan(PC, 4096, JvmArgs.Gc.G1, 25, false).args().stream().anyMatch(a -> a.contains("MaxGCPauseMillis")),
                "pause target showed no significant effect, so it is not set");
    }

    @Test
    void zgenerationalOnlyWhereMeaningful() {
        assertTrue(JvmArgs.plan(PC, 4096, JvmArgs.Gc.ZGC, 21, false).args().contains("-XX:+ZGenerational"));
        assertFalse(JvmArgs.plan(PC, 4096, JvmArgs.Gc.ZGC, 25, false).args().contains("-XX:+ZGenerational"));
        var j25 = JvmArgs.plan(PC, 4096, JvmArgs.Gc.ZGC, 25, true).args();
        assertTrue(j25.contains("-XX:+UseCompactObjectHeaders"));
        assertTrue(j25.contains("-XX:+AlwaysPreTouch"));
        assertTrue(j25.containsAll(java.util.List.of("-Xms4096m", "-Xmx4096m")));
        assertFalse(j25.stream().anyMatch(a -> a.contains("UnlockExperimental")));
    }
}
