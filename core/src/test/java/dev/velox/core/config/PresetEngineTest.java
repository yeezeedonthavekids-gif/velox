package dev.velox.core.config;

import dev.velox.core.hw.HardwareProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class PresetEngineTest {
    static final HardwareProfile USER_PC = new HardwareProfile(16, 15_700, "NVIDIA GeForce RTX 5060", 8192, 165);
    static final HardwareProfile LAPTOP = new HardwareProfile(8, 7_800, "Intel(R) UHD Graphics 620", -1, 60);

    @ParameterizedTest
    @EnumSource(Preset.class)
    void invariantsHoldForEveryPresetAndTier(Preset p) {
        for (HardwareProfile hw : new HardwareProfile[]{USER_PC, LAPTOP, null}) {
            GameSettings s = PresetEngine.resolve(p, hw, null);
            assertFalse(s.vsync, "vsync adds a frame of latency");
            assertFalse(s.improvedTransparency);
            assertTrue(s.renderDistance >= 5, "below 5 chunk streaming dominates");
            assertTrue(s.mipmapLevels >= 1, "0 mipmaps shimmers and gains nothing with Sodium");
            assertEquals(GameSettings.InactivityLimit.MINIMIZED, s.inactivityFpsLimit);
            assertEquals(0, s.sodiumChunkBuilderThreads);
        }
    }

    @Test
    void competitiveNeverHidesEntitiesOrAllParticles() {
        GameSettings s = PresetEngine.resolve(Preset.COMPETITIVE, USER_PC, null);
        assertEquals(1.0, s.entityDistanceScaling);
        assertNotEquals(GameSettings.Particles.MINIMAL, s.particles);
        assertEquals(0.0, s.chunkFadeInSeconds);
    }

    @Test
    void presetsAreOrderedByCost() {
        int max = PresetEngine.resolve(Preset.MAX_FPS, USER_PC, null).renderDistance;
        int comp = PresetEngine.resolve(Preset.COMPETITIVE, USER_PC, null).renderDistance;
        int bal = PresetEngine.resolve(Preset.BALANCED, USER_PC, null).renderDistance;
        assertTrue(max < comp && comp < bal);
    }

    @Test
    void hardwareTierScalesRenderDistance() {
        assertEquals(HardwareProfile.Tier.HIGH, USER_PC.tier());
        assertEquals(HardwareProfile.Tier.LOW, LAPTOP.tier());
        assertTrue(PresetEngine.resolve(Preset.BALANCED, USER_PC, null).renderDistance
                > PresetEngine.resolve(Preset.BALANCED, LAPTOP, null).renderDistance);
    }

    @Test
    void customOverlaysBalancedAndIsClamped() {
        GameSettings o = new GameSettings();
        o.renderDistance = 99;
        o.clouds = GameSettings.Clouds.FANCY;
        GameSettings s = PresetEngine.resolve(Preset.CUSTOM, USER_PC, o);
        assertEquals(32, s.renderDistance);
        assertEquals(GameSettings.Clouds.FANCY, s.clouds);
        assertEquals(PresetEngine.resolve(Preset.BALANCED, USER_PC, null).biomeBlendRadius, s.biomeBlendRadius);
    }
}
