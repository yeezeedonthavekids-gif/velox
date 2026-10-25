package dev.velox.core.bench;

import java.util.Arrays;

/**
 * Summary statistics for a sequence of frame times.
 *
 * <p>Definitions (kept explicit because tools disagree):
 * <ul>
 *   <li>{@code avgFps}: frames divided by total wall time. This is time-weighted, unlike the mean
 *       of per-frame FPS values, which overweights fast frames.</li>
 *   <li>{@code low1PctFps} / {@code low01PctFps}: 1000 / mean of the slowest 1% (0.1%) of frame
 *       times ("average of the worst N%", as reported by CapFrameX and similar tools).</li>
 *   <li>{@code p99FrameMs} / {@code p999FrameMs}: percentile frame times. 1000 / p99 is the
 *       "P1" FPS figure some tools report instead of the average-based 1% low.</li>
 *   <li>{@code spikes}: frames longer than twice the median frame time and at least 4 ms over it,
 *       so tiny jitter at very high FPS does not count as stutter.</li>
 * </ul>
 */
public record FrameStats(
        int frames,
        double durationSec,
        double avgFps,
        double meanFrameMs,
        double stdDevFrameMs,
        double p50FrameMs,
        double p95FrameMs,
        double p99FrameMs,
        double p999FrameMs,
        double maxFrameMs,
        double low1PctFps,
        double low01PctFps,
        int spikes,
        int framesOver50Ms) {

    public static FrameStats of(long[] frameNanos) {
        int n = frameNanos.length;
        if (n == 0) {
            return new FrameStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        long[] sorted = frameNanos.clone();
        Arrays.sort(sorted);

        double totalNs = 0;
        for (long v : frameNanos) totalNs += v;
        double meanNs = totalNs / n;
        double var = 0;
        for (long v : frameNanos) {
            double d = v - meanNs;
            var += d * d;
        }
        double stdNs = n > 1 ? Math.sqrt(var / (n - 1)) : 0;

        double medianNs = percentile(sorted, 0.50);
        double spikeThreshold = Math.max(medianNs * 2.0, medianNs + 4_000_000.0);
        int spikes = 0;
        int over50 = 0;
        for (long v : frameNanos) {
            if (v > spikeThreshold) spikes++;
            if (v > 50_000_000L) over50++;
        }

        return new FrameStats(
                n,
                totalNs / 1e9,
                n / (totalNs / 1e9),
                meanNs / 1e6,
                stdNs / 1e6,
                medianNs / 1e6,
                percentile(sorted, 0.95) / 1e6,
                percentile(sorted, 0.99) / 1e6,
                percentile(sorted, 0.999) / 1e6,
                sorted[n - 1] / 1e6,
                worstFractionFps(sorted, 0.01),
                worstFractionFps(sorted, 0.001),
                spikes,
                over50);
    }

    /** Linear-interpolated percentile over an ascending array. */
    static double percentile(long[] sorted, double q) {
        if (sorted.length == 1) return sorted[0];
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, sorted.length - 1);
        double frac = pos - lo;
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac;
    }

    /** 1000 / mean of the slowest {@code fraction} of frames (at least one frame). */
    static double worstFractionFps(long[] sorted, double fraction) {
        int k = Math.max(1, (int) Math.round(sorted.length * fraction));
        double sum = 0;
        for (int i = sorted.length - k; i < sorted.length; i++) sum += sorted[i];
        double meanNs = sum / k;
        return 1e9 / meanNs;
    }
}
