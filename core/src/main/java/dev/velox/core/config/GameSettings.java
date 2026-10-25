package dev.velox.core.config;

/**
 * Video settings a preset controls. A {@code null} field means "leave the player's value alone",
 * so presets and CUSTOM overrides can be layered.
 *
 * <p>Enum constants mirror the Minecraft 26.3 / Sodium 0.9 values they map to; the names are
 * resolved by the mod and launcher at apply time so this class has no game dependency.
 */
public final class GameSettings {
    public enum Particles { ALL, DECREASED, MINIMAL }
    public enum Clouds { OFF, FAST, FANCY }
    public enum InactivityLimit { MINIMIZED, AFK }
    /** Sodium chunk-build scheduling (sodium-options.json performance.chunk_build_defer_mode). */
    public enum ChunkDefer { ALWAYS, ONE_FRAME, ZERO_FRAMES }

    // --- vanilla Options ------------------------------------------------------------------------
    public Integer renderDistance;
    public Integer simulationDistance;
    /** 0.5 .. 5.0; vanilla shows it as a percentage. */
    public Double entityDistanceScaling;
    /** 10 .. 260, where 260 means unlimited. */
    public Integer maxFps;
    public Boolean vsync;
    public Integer mipmapLevels;
    /** Radius 0..7: 0 is "off" (1x1), 2 is 5x5. */
    public Integer biomeBlendRadius;
    public Particles particles;
    public Clouds clouds;
    public Boolean entityShadows;
    public Boolean ambientOcclusion;
    /** Vanilla's former "Fabulous" transparency pipeline. Costly; off in every Velox preset. */
    public Boolean improvedTransparency;
    /** True renders leaves with transparent cut-outs (fancy); false renders them opaque. */
    public Boolean cutoutLeaves;
    public Boolean vignette;
    /** Seconds a newly built chunk section takes to fade in; 0 shows it immediately. */
    public Double chunkFadeInSeconds;
    public InactivityLimit inactivityFpsLimit;

    // --- Sodium ---------------------------------------------------------------------------------
    /** 0 lets Sodium pick from the CPU core count, which is the right default almost always. */
    public Integer sodiumChunkBuilderThreads;
    public ChunkDefer sodiumChunkDefer;
    public Boolean sodiumEntityCulling;
    public Boolean sodiumFogOcclusion;
    public Boolean sodiumBlockFaceCulling;
    public Boolean sodiumAnimateOnlyVisibleTextures;

    /** Returns a copy with every non-null field of {@code top} overriding this one. */
    public GameSettings overlay(GameSettings top) {
        GameSettings r = copy();
        if (top == null) return r;
        if (top.renderDistance != null) r.renderDistance = top.renderDistance;
        if (top.simulationDistance != null) r.simulationDistance = top.simulationDistance;
        if (top.entityDistanceScaling != null) r.entityDistanceScaling = top.entityDistanceScaling;
        if (top.maxFps != null) r.maxFps = top.maxFps;
        if (top.vsync != null) r.vsync = top.vsync;
        if (top.mipmapLevels != null) r.mipmapLevels = top.mipmapLevels;
        if (top.biomeBlendRadius != null) r.biomeBlendRadius = top.biomeBlendRadius;
        if (top.particles != null) r.particles = top.particles;
        if (top.clouds != null) r.clouds = top.clouds;
        if (top.entityShadows != null) r.entityShadows = top.entityShadows;
        if (top.ambientOcclusion != null) r.ambientOcclusion = top.ambientOcclusion;
        if (top.improvedTransparency != null) r.improvedTransparency = top.improvedTransparency;
        if (top.cutoutLeaves != null) r.cutoutLeaves = top.cutoutLeaves;
        if (top.vignette != null) r.vignette = top.vignette;
        if (top.chunkFadeInSeconds != null) r.chunkFadeInSeconds = top.chunkFadeInSeconds;
        if (top.inactivityFpsLimit != null) r.inactivityFpsLimit = top.inactivityFpsLimit;
        if (top.sodiumChunkBuilderThreads != null) r.sodiumChunkBuilderThreads = top.sodiumChunkBuilderThreads;
        if (top.sodiumChunkDefer != null) r.sodiumChunkDefer = top.sodiumChunkDefer;
        if (top.sodiumEntityCulling != null) r.sodiumEntityCulling = top.sodiumEntityCulling;
        if (top.sodiumFogOcclusion != null) r.sodiumFogOcclusion = top.sodiumFogOcclusion;
        if (top.sodiumBlockFaceCulling != null) r.sodiumBlockFaceCulling = top.sodiumBlockFaceCulling;
        if (top.sodiumAnimateOnlyVisibleTextures != null) r.sodiumAnimateOnlyVisibleTextures = top.sodiumAnimateOnlyVisibleTextures;
        return r;
    }

    public GameSettings copy() {
        GameSettings c = new GameSettings();
        c.renderDistance = renderDistance;
        c.simulationDistance = simulationDistance;
        c.entityDistanceScaling = entityDistanceScaling;
        c.maxFps = maxFps;
        c.vsync = vsync;
        c.mipmapLevels = mipmapLevels;
        c.biomeBlendRadius = biomeBlendRadius;
        c.particles = particles;
        c.clouds = clouds;
        c.entityShadows = entityShadows;
        c.ambientOcclusion = ambientOcclusion;
        c.improvedTransparency = improvedTransparency;
        c.cutoutLeaves = cutoutLeaves;
        c.vignette = vignette;
        c.chunkFadeInSeconds = chunkFadeInSeconds;
        c.inactivityFpsLimit = inactivityFpsLimit;
        c.sodiumChunkBuilderThreads = sodiumChunkBuilderThreads;
        c.sodiumChunkDefer = sodiumChunkDefer;
        c.sodiumEntityCulling = sodiumEntityCulling;
        c.sodiumFogOcclusion = sodiumFogOcclusion;
        c.sodiumBlockFaceCulling = sodiumBlockFaceCulling;
        c.sodiumAnimateOnlyVisibleTextures = sodiumAnimateOnlyVisibleTextures;
        return c;
    }

    /** Clamps every set value into the range vanilla 26.3 / Sodium accept. */
    public GameSettings clamped() {
        GameSettings c = copy();
        if (c.renderDistance != null) c.renderDistance = clamp(c.renderDistance, 2, 32);
        if (c.simulationDistance != null) c.simulationDistance = clamp(c.simulationDistance, 5, 32);
        if (c.entityDistanceScaling != null) c.entityDistanceScaling = Math.max(0.5, Math.min(5.0, c.entityDistanceScaling));
        if (c.maxFps != null) c.maxFps = clamp(c.maxFps, 10, 260);
        if (c.mipmapLevels != null) c.mipmapLevels = clamp(c.mipmapLevels, 0, 4);
        if (c.biomeBlendRadius != null) c.biomeBlendRadius = clamp(c.biomeBlendRadius, 0, 7);
        if (c.chunkFadeInSeconds != null) c.chunkFadeInSeconds = Math.max(0.0, Math.min(1.0, c.chunkFadeInSeconds));
        if (c.sodiumChunkBuilderThreads != null) c.sodiumChunkBuilderThreads = clamp(c.sodiumChunkBuilderThreads, 0, 64);
        return c;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
