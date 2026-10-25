package dev.velox.core.hw;

import java.util.Locale;

/**
 * What the recommendation engine knows about a machine. Every field is optional except core count
 * and RAM, which the JVM always provides; GPU fields come from OS tools and may be absent.
 *
 * @param logicalCores logical processors visible to the JVM
 * @param totalRamMb   physical memory in MiB
 * @param gpuName      adapter name as reported by the OS, or null
 * @param vramMb       dedicated video memory in MiB, or -1 if unknown (Windows caps the WMI
 *                     value at 4 GiB, so it is a lower bound on many cards)
 * @param maxRefreshHz highest display refresh rate seen, or -1 if unknown
 */
public record HardwareProfile(int logicalCores, long totalRamMb, String gpuName, long vramMb, int maxRefreshHz) {

    public enum GpuVendor { NVIDIA, AMD, INTEL, APPLE, UNKNOWN }

    public enum Tier { LOW, MID, HIGH }

    public GpuVendor gpuVendor() {
        if (gpuName == null) return GpuVendor.UNKNOWN;
        String n = gpuName.toLowerCase(Locale.ROOT);
        if (n.contains("nvidia") || n.contains("geforce") || n.contains("rtx") || n.contains("quadro")) return GpuVendor.NVIDIA;
        if (n.contains("amd") || n.contains("radeon") || n.contains("ati ")) return GpuVendor.AMD;
        if (n.contains("intel") || n.contains("iris")) return GpuVendor.INTEL;
        if (n.contains("apple")) return GpuVendor.APPLE;
        return GpuVendor.UNKNOWN;
    }

    /** True for GPUs that share system memory (integrated graphics). Unknown GPUs count as discrete. */
    public boolean integratedGpu() {
        if (gpuName == null) return false;
        String n = gpuName.toLowerCase(Locale.ROOT);
        return switch (gpuVendor()) {
            // Discrete Arc cards have an A/B model number ("Arc(TM) A770"); Core Ultra iGPUs are plain "Arc(TM) Graphics".
            case INTEL -> !n.replace("(tm)", "").replace("(r)", "").matches(".*\\barc\\s+(pro\\s+)?[ab]\\d{2,3}.*");
            // Discrete Radeons carry an "RX"/"Pro" model; APU graphics are just "Radeon(TM) Graphics" or "Vega N".
            case AMD -> !(n.contains(" rx") || n.contains("rx ") || n.contains("radeon pro") || n.contains("firepro"));
            case APPLE -> true;
            default -> false;
        };
    }

    public Tier tier() {
        boolean lowCpu = logicalCores <= 4; // chunk meshing and the game thread compete for cores
        boolean lowRam = totalRamMb < 7_000;
        if (lowCpu || lowRam || integratedGpu()) return Tier.LOW;
        boolean strongCpu = logicalCores >= 8;
        boolean plentyRam = totalRamMb >= 15_000; // "16 GB" machines report ~15.2-15.9 GiB
        boolean discreteKnown = gpuVendor() == GpuVendor.NVIDIA || gpuVendor() == GpuVendor.AMD
                || gpuVendor() == GpuVendor.INTEL;
        if (strongCpu && plentyRam && discreteKnown) return Tier.HIGH;
        return Tier.MID;
    }

    public String describe() {
        return String.format(Locale.ROOT, "%d threads, %.1f GiB RAM, GPU: %s%s, display: %s -> tier %s",
                logicalCores, totalRamMb / 1024.0,
                gpuName == null ? "unknown" : gpuName,
                vramMb > 0 ? String.format(Locale.ROOT, " (%d MiB reported)", vramMb) : "",
                maxRefreshHz > 0 ? maxRefreshHz + " Hz" : "unknown",
                tier());
    }
}
