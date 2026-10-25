package dev.velox.core.bench;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

/**
 * Periodic heap and process-CPU samples for a benchmark run.
 *
 * <p>Call {@link #sample()} a few times per second (not per frame). Heap usage comes from
 * {@link Runtime}, which does not allocate; CPU load comes from the JDK's OS bean when present.
 */
public final class ResourceSampler {
    private final com.sun.management.OperatingSystemMXBean os;
    private long samples;
    private double heapUsedSumMb;
    private double heapPeakMb;
    private double cpuSum;
    private long cpuSamples;

    public ResourceSampler() {
        OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        this.os = bean instanceof com.sun.management.OperatingSystemMXBean sun ? sun : null;
    }

    public void sample() {
        Runtime rt = Runtime.getRuntime();
        double usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
        samples++;
        heapUsedSumMb += usedMb;
        heapPeakMb = Math.max(heapPeakMb, usedMb);
        if (os != null) {
            double load = os.getProcessCpuLoad();
            if (load >= 0) {
                cpuSum += load;
                cpuSamples++;
            }
        }
    }

    public double avgHeapUsedMb() { return samples == 0 ? 0 : heapUsedSumMb / samples; }
    public double peakHeapUsedMb() { return heapPeakMb; }
    public static double maxHeapMb() { return Runtime.getRuntime().maxMemory() / 1048576.0; }

    /** Average process CPU load, as a percentage of all logical cores; -1 when unavailable. */
    public double avgProcessCpuPct() { return cpuSamples == 0 ? -1 : cpuSum / cpuSamples * 100.0; }
}
