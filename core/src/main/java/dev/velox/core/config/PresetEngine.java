package dev.velox.core.config;

import dev.velox.core.config.GameSettings.ChunkDefer;
import dev.velox.core.config.GameSettings.Clouds;
import dev.velox.core.config.GameSettings.InactivityLimit;
import dev.velox.core.config.GameSettings.Particles;
import dev.velox.core.hw.HardwareProfile;

/**
 * Turns a preset plus a hardware profile into concrete settings.
 *
 * <p>Design rules, in priority order:
 * <ol>
 *   <li>Never reduce information the player needs: entity distance stays at 100% outside
 *       MAX FPS, and particles are never fully hidden in COMPETITIVE (they carry hit, potion and
 *       hazard cues).</li>
 *   <li>Don't pick a minimum just because it is a minimum. Mipmaps stay on (0 levels causes
 *       distant-texture shimmer and more texture-cache misses for little or no gain with
 *       Sodium), and render distance never drops below 5 because chunk streaming then
 *       dominates.</li>
 *   <li>Cut the settings with real cost first. Render distance scales chunk count with the
 *       square of the distance, which is the biggest lever. Biome blend adds per-vertex color
 *       sampling during chunk meshing. Cut-out leaves add overdraw, entity shadows add an extra
 *       pass per entity, and improved transparency adds full-screen passes.</li>
 *   <li>Keep Sodium's own defaults where they are already the fast choice (builder thread
 *       auto-sizing, entity culling, fog occlusion).</li>
 * </ol>
 */
public final class PresetEngine {
    private PresetEngine() {}

    public static GameSettings resolve(Preset preset, HardwareProfile hw, GameSettings customOverrides) {
        HardwareProfile.Tier tier = hw == null ? HardwareProfile.Tier.MID : hw.tier();
        GameSettings base = switch (preset) {
            case MAX_FPS -> maxFps(tier);
            case COMPETITIVE -> competitive(tier);
            case BALANCED, CUSTOM -> balanced(tier);
        };
        if (preset == Preset.CUSTOM) base = base.overlay(customOverrides);
        return base.clamped();
    }

    private static GameSettings common() {
        GameSettings s = new GameSettings();
        s.vsync = false;
        s.maxFps = 260; // unlimited; use a driver/RTSS cap or the in-game slider for a fixed target
        s.improvedTransparency = false;
        // MINIMIZED only throttles a minimized window. AFK would also cap FPS while you stand still,
        // which surprises players (and invalidates "standing still" benchmarks).
        s.inactivityFpsLimit = InactivityLimit.MINIMIZED;
        s.sodiumChunkBuilderThreads = 0;
        s.sodiumEntityCulling = true;
        s.sodiumFogOcclusion = true;
        s.sodiumBlockFaceCulling = true;
        s.sodiumAnimateOnlyVisibleTextures = true;
        // Sodium's default: chunk uploads never block a frame, so frame times stay flat.
        s.sodiumChunkDefer = ChunkDefer.ALWAYS;
        return s;
    }

    static GameSettings maxFps(HardwareProfile.Tier tier) {
        GameSettings s = common();
        s.renderDistance = switch (tier) { case HIGH -> 10; case MID -> 8; case LOW -> 6; };
        s.simulationDistance = 5;
        s.entityDistanceScaling = 0.75;
        s.mipmapLevels = 2;
        s.biomeBlendRadius = 0;
        s.particles = Particles.DECREASED;
        s.clouds = Clouds.OFF;
        s.entityShadows = false;
        s.ambientOcclusion = false;
        s.cutoutLeaves = false;
        s.vignette = false;
        s.chunkFadeInSeconds = 0.0;
        return s;
    }

    static GameSettings competitive(HardwareProfile.Tier tier) {
        GameSettings s = common();
        s.renderDistance = switch (tier) { case HIGH -> 12; case MID -> 10; case LOW -> 8; };
        s.simulationDistance = 6;
        s.entityDistanceScaling = 1.0;  // never hide players you could otherwise see
        s.mipmapLevels = 4;
        s.biomeBlendRadius = 0;
        s.particles = Particles.DECREASED;
        s.clouds = Clouds.OFF;          // clouds can hide players and projectiles at height
        s.entityShadows = false;
        s.ambientOcclusion = true;      // depth cues on blocks are worth their small meshing cost
        s.cutoutLeaves = false;
        s.vignette = false;
        s.chunkFadeInSeconds = 0.0;     // terrain appears immediately instead of fading in
        return s;
    }

    static GameSettings balanced(HardwareProfile.Tier tier) {
        GameSettings s = common();
        s.renderDistance = switch (tier) { case HIGH -> 16; case MID -> 12; case LOW -> 8; };
        s.simulationDistance = 8;
        s.entityDistanceScaling = 1.0;
        s.mipmapLevels = 4;
        s.biomeBlendRadius = 2;
        s.particles = Particles.ALL;
        s.clouds = Clouds.FAST;
        s.entityShadows = true;
        s.ambientOcclusion = true;
        s.cutoutLeaves = true;
        s.vignette = true;
        s.chunkFadeInSeconds = null;    // keep vanilla's fade-in
        return s;
    }
}
