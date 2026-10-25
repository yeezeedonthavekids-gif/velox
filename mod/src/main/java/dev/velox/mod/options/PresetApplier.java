package dev.velox.mod.options;

import dev.velox.core.config.GameSettings;
import dev.velox.core.config.VeloxConfig;
import dev.velox.core.hw.HardwareProfile;
import dev.velox.mod.VeloxMod;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;

import java.util.Objects;

/**
 * Applies a preset through vanilla's {@link OptionInstance}s, so vanilla's own change callbacks
 * run (render distance, vsync, frame limit), then performs the same reloads Sodium's settings
 * screen does for options that need them. Only values that actually differ are touched, so
 * re-applying an unchanged preset costs nothing.
 *
 * <p>Sodium-only settings (chunk build deferral, culling toggles) live in Sodium's config file,
 * which the launcher patches before launch; changes made here apply to them on next start.
 */
public final class PresetApplier {
    private PresetApplier() {}

    public static void apply(Minecraft mc, VeloxConfig cfg, boolean announce) {
        GameSettings s = cfg.effectiveSettings(hardware());
        Options o = mc.options;
        boolean rendererReload = false;
        boolean assetReload = false;

        set(o.renderDistance(), s.renderDistance);
        set(o.simulationDistance(), s.simulationDistance);
        set(o.entityDistanceScaling(), s.entityDistanceScaling);
        set(o.framerateLimit(), s.maxFps);
        set(o.enableVsync(), s.vsync);
        set(o.entityShadows(), s.entityShadows);
        set(o.vignette(), s.vignette);
        set(o.chunkSectionFadeInTime(), s.chunkFadeInSeconds);
        rendererReload |= set(o.biomeBlendRadius(), s.biomeBlendRadius);
        rendererReload |= set(o.ambientOcclusion(), s.ambientOcclusion);
        rendererReload |= set(o.improvedTransparency(), s.improvedTransparency);
        rendererReload |= set(o.cutoutLeaves(), s.cutoutLeaves);
        assetReload |= set(o.mipmapLevels(), s.mipmapLevels);
        if (s.particles != null) set(o.particles(), enumValue(ParticleStatus.class, s.particles.name()));
        if (s.clouds != null) set(o.cloudStatus(), enumValue(CloudStatus.class, s.clouds.name()));
        if (s.inactivityFpsLimit != null) set(o.inactivityFpsLimit(), enumValue(InactivityFpsLimit.class, s.inactivityFpsLimit.name()));

        // The same follow-ups Sodium's options screen performs for these flags.
        if (rendererReload && mc.level != null) mc.levelExtractor.allChanged();
        if (assetReload) {
            mc.updateMaxMipLevel(o.mipmapLevels().get());
            mc.delayTextureReload();
        }
        o.save();
        VeloxMod.LOG.info("Applied preset {} (render distance {}, renderer reload {}, asset reload {})",
                cfg.preset.displayName, s.renderDistance, rendererReload, assetReload);
        if (announce) VeloxMod.message(mc, "Applied " + cfg.preset.displayName + " preset");
    }

    /** @return true if the value changed */
    private static <T> boolean set(OptionInstance<T> option, T value) {
        if (value == null || Objects.equals(option.get(), value)) return false;
        option.set(value);
        return true;
    }

    /** Resolves by name so a renamed constant in a future version is skipped, not a crash. */
    private static <E extends Enum<E>> E enumValue(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            VeloxMod.LOG.warn("{} has no value {}; leaving that option unchanged", type.getSimpleName(), name);
            return null;
        }
    }

    /**
     * Hardware as the launcher detected it (passed as system properties), falling back to what
     * the JVM can see. The tier only scales render distance.
     */
    public static HardwareProfile hardware() {
        int cores = Integer.getInteger("velox.cores", Runtime.getRuntime().availableProcessors());
        long ram = Long.getLong("velox.ramMb", -1L);
        if (ram < 0 && java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean os) {
            ram = os.getTotalMemorySize() / (1024 * 1024);
        }
        return new HardwareProfile(cores, ram, System.getProperty("velox.gpu"), Long.getLong("velox.vramMb", -1L), -1);
    }
}
