package dev.velox.core.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * Compares repeated benchmark runs of a baseline against a candidate.
 *
 * <p>The unit of observation is one run, never one frame: consecutive frames are strongly
 * autocorrelated, so treating a run's frames as independent samples would make almost any
 * difference look "significant". Each metric uses Welch's t-test on the per-run values and
 * reports a 95% confidence interval for the difference of means.
 *
 * <p>A change is only called an improvement or regression when the interval excludes zero
 * <em>and</em> the relative change exceeds {@code minEffectPct}, which guards against tiny but
 * real differences (thermal drift, background noise) being reported as wins.
 */
public final class RunComparison {

    public enum Verdict { IMPROVEMENT, REGRESSION, NO_SIGNIFICANT_DIFFERENCE, INSUFFICIENT_RUNS }

    /** Whether a larger value of a metric is better. */
    public enum Direction { HIGHER_IS_BETTER, LOWER_IS_BETTER }

    public record Metric(String name, Direction direction, ToDoubleFunction<FrameStats> extractor) {}

    public record MetricResult(
            String metric,
            int baselineRuns,
            int candidateRuns,
            double baselineMean,
            double baselineStdDev,
            double candidateMean,
            double candidateStdDev,
            double diff,
            double diffPct,
            double ciLow,
            double ciHigh,
            double pValue,
            Verdict verdict) {

        public String describe() {
            return String.format(Locale.ROOT,
                    "%-14s base %9.2f +/-%-7.2f cand %9.2f +/-%-7.2f diff %+8.2f (%+6.2f%%) 95%%CI [%+.2f, %+.2f] p=%.4f  %s",
                    metric, baselineMean, baselineStdDev, candidateMean, candidateStdDev,
                    diff, diffPct, ciLow, ciHigh, pValue, verdict);
        }
    }

    public static final List<Metric> DEFAULT_METRICS = List.of(
            new Metric("avg_fps", Direction.HIGHER_IS_BETTER, FrameStats::avgFps),
            new Metric("1%_low_fps", Direction.HIGHER_IS_BETTER, FrameStats::low1PctFps),
            new Metric("0.1%_low_fps", Direction.HIGHER_IS_BETTER, FrameStats::low01PctFps),
            new Metric("p99_frame_ms", Direction.LOWER_IS_BETTER, FrameStats::p99FrameMs),
            new Metric("spikes", Direction.LOWER_IS_BETTER, s -> s.spikes()));

    private RunComparison() {}

    public static List<MetricResult> compare(List<FrameStats> baseline, List<FrameStats> candidate,
                                             double minEffectPct) {
        List<MetricResult> out = new ArrayList<>();
        for (Metric m : DEFAULT_METRICS) {
            out.add(compare(m, values(baseline, m), values(candidate, m), minEffectPct));
        }
        return out;
    }

    private static double[] values(List<FrameStats> runs, Metric m) {
        double[] v = new double[runs.size()];
        for (int i = 0; i < v.length; i++) v[i] = m.extractor().applyAsDouble(runs.get(i));
        return v;
    }

    public static MetricResult compare(Metric m, double[] a, double[] b, double minEffectPct) {
        double ma = mean(a), mb = mean(b);
        double sa = stdDev(a, ma), sb = stdDev(b, mb);
        double diff = mb - ma;
        double diffPct = ma != 0 ? diff / ma * 100.0 : 0;

        if (a.length < 2 || b.length < 2) {
            return new MetricResult(m.name(), a.length, b.length, ma, sa, mb, sb, diff, diffPct,
                    Double.NaN, Double.NaN, Double.NaN, Verdict.INSUFFICIENT_RUNS);
        }

        double va = sa * sa / a.length, vb = sb * sb / b.length;
        double se = Math.sqrt(va + vb);
        double ciLow, ciHigh, p;
        if (se == 0) {
            // Identical runs within each group: the difference is exact.
            ciLow = ciHigh = diff;
            p = diff == 0 ? 1.0 : 0.0;
        } else {
            double df = (va + vb) * (va + vb)
                    / (va * va / (a.length - 1) + vb * vb / (b.length - 1));
            double t = diff / se;
            p = 2 * (1 - studentTCdf(Math.abs(t), df));
            double tCrit = studentTQuantile(0.975, df);
            ciLow = diff - tCrit * se;
            ciHigh = diff + tCrit * se;
        }

        Verdict verdict;
        boolean excludesZero = ciLow > 0 || ciHigh < 0;
        if (!excludesZero || Math.abs(diffPct) < minEffectPct) {
            verdict = Verdict.NO_SIGNIFICANT_DIFFERENCE;
        } else {
            boolean better = (diff > 0) == (m.direction() == Direction.HIGHER_IS_BETTER);
            verdict = better ? Verdict.IMPROVEMENT : Verdict.REGRESSION;
        }
        return new MetricResult(m.name(), a.length, b.length, ma, sa, mb, sb, diff, diffPct,
                ciLow, ciHigh, p, verdict);
    }

    static double mean(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return v.length == 0 ? 0 : s / v.length;
    }

    static double stdDev(double[] v, double mean) {
        if (v.length < 2) return 0;
        double s = 0;
        for (double x : v) s += (x - mean) * (x - mean);
        return Math.sqrt(s / (v.length - 1));
    }

    // --- Student's t distribution -------------------------------------------------------------

    /** CDF of Student's t with {@code df} degrees of freedom (df may be fractional, as in Welch). */
    static double studentTCdf(double t, double df) {
        double x = df / (df + t * t);
        double tail = 0.5 * regularizedIncompleteBeta(x, df / 2.0, 0.5);
        return t >= 0 ? 1 - tail : tail;
    }

    /** Inverse CDF by bisection; monotone and accurate to ~1e-10, which is ample here. */
    static double studentTQuantile(double p, double df) {
        double lo = -1000, hi = 1000;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (studentTCdf(mid, df) < p) lo = mid; else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /** I_x(a, b) via the Lentz continued fraction (Numerical Recipes, betacf). */
    static double regularizedIncompleteBeta(double x, double a, double b) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        double lnBeta = lgamma(a + b) - lgamma(a) - lgamma(b);
        double front = Math.exp(lnBeta + a * Math.log(x) + b * Math.log(1 - x));
        if (x < (a + 1) / (a + b + 2)) {
            return front * betaContinuedFraction(x, a, b) / a;
        }
        return 1 - front * betaContinuedFraction(1 - x, b, a) / b;
    }

    private static double betaContinuedFraction(double x, double a, double b) {
        final double eps = 1e-15, fpmin = 1e-300;
        double qab = a + b, qap = a + 1, qam = a - 1;
        double c = 1, d = 1 - qab * x / qap;
        if (Math.abs(d) < fpmin) d = fpmin;
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= 500; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < fpmin) d = fpmin;
            c = 1 + aa / c;
            if (Math.abs(c) < fpmin) c = fpmin;
            d = 1 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < fpmin) d = fpmin;
            c = 1 + aa / c;
            if (Math.abs(c) < fpmin) c = fpmin;
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < eps) break;
        }
        return h;
    }

    /** Lanczos approximation of ln Γ(x), x > 0. */
    static double lgamma(double x) {
        double[] g = {676.5203681218851, -1259.1392167224028, 771.32342877765313,
                -176.61502916214059, 12.507343278686905, -0.13857109526572012,
                9.9843695780195716e-6, 1.5056327351493116e-7};
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lgamma(1 - x);
        }
        x -= 1;
        double a = 0.99999999999980993;
        double t = x + 7.5;
        for (int i = 0; i < g.length; i++) a += g[i] / (x + i + 1);
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }
}
