package dev.velox.core.hw;

import org.junit.jupiter.api.Test;

import static dev.velox.core.hw.HardwareProfile.GpuVendor.*;
import static org.junit.jupiter.api.Assertions.*;

class HardwareProfileTest {
    private static HardwareProfile gpu(String name) {
        return new HardwareProfile(16, 32_000, name, -1, -1);
    }

    @Test
    void vendorsAndIntegration() {
        assertEquals(NVIDIA, gpu("NVIDIA GeForce RTX 5060").gpuVendor());
        assertFalse(gpu("NVIDIA GeForce RTX 5060").integratedGpu());
        assertEquals(AMD, gpu("AMD Radeon RX 7800 XT").gpuVendor());
        assertFalse(gpu("AMD Radeon RX 7800 XT").integratedGpu());
        assertTrue(gpu("AMD Radeon(TM) Graphics").integratedGpu(), "8700F has no iGPU, but APUs report this");
        assertTrue(gpu("Intel(R) UHD Graphics 770").integratedGpu());
        assertTrue(gpu("Intel(R) Arc(TM) Graphics").integratedGpu());
        assertFalse(gpu("Intel(R) Arc(TM) A770 Graphics").integratedGpu());
        assertFalse(gpu("Intel(R) Arc(TM) B580 Graphics").integratedGpu());
        assertEquals(UNKNOWN, gpu(null).gpuVendor());
    }

    @Test
    void tiers() {
        assertEquals(HardwareProfile.Tier.HIGH, new HardwareProfile(16, 15_600, "NVIDIA GeForce RTX 5060", -1, -1).tier());
        assertEquals(HardwareProfile.Tier.MID, new HardwareProfile(6, 15_600, "NVIDIA GeForce GTX 1660", -1, -1).tier());
        assertEquals(HardwareProfile.Tier.MID, new HardwareProfile(16, 15_600, null, -1, -1).tier());
        assertEquals(HardwareProfile.Tier.LOW, new HardwareProfile(4, 7_500, "NVIDIA GeForce RTX 3060", -1, -1).tier());
        assertEquals(HardwareProfile.Tier.LOW, new HardwareProfile(2, 32_000, "NVIDIA GeForce RTX 3060", -1, -1).tier());
    }
}
