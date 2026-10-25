package dev.velox.core.bench;

import dev.velox.core.bench.RunComparison.Direction;
import dev.velox.core.bench.RunComparison.Metric;
import dev.velox.core.bench.RunComparison.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RunComparisonTest {
    private static final Metric FPS = new Metric("fps", Direction.HIGHER_IS_BETTER, FrameStats::avgFps);
    private static final Metric P99 = new Metric("p99", Direction.LOWER_IS_BETTER, FrameStats::p99FrameMs);

    @Test
    void tQuantilesMatchTables() {
        assertEquals(2.228, RunComparison.studentTQuantile(0.975, 10), 1e-3);
        assertEquals(2.776, RunComparison.studentTQuantile(0.975, 4), 1e-3);
        assertEquals(1.960, RunComparison.studentTQuantile(0.975, 1e6), 1e-3);
        assertEquals(0.5, RunComparison.studentTCdf(0, 7), 1e-12);
    }

    @Test
    void lgammaMatchesFactorials() {
        assertEquals(Math.log(24), RunComparison.lgamma(5), 1e-10);
        assertEquals(0.5 * Math.log(Math.PI), RunComparison.lgamma(0.5), 1e-10);
    }

    @Test
    void clearImprovementDetected() {
        var r = RunComparison.compare(FPS, new double[]{300, 305, 298, 302}, new double[]{420, 415, 425, 418}, 1.0);
        assertEquals(Verdict.IMPROVEMENT, r.verdict());
        assertTrue(r.ciLow() > 0);
        assertTrue(r.pValue() < 0.001);
    }

    @Test
    void noiseIsNotCalledAnImprovement() {
        var r = RunComparison.compare(FPS, new double[]{300, 320, 290, 310}, new double[]{305, 315, 295, 318}, 1.0);
        assertEquals(Verdict.NO_SIGNIFICANT_DIFFERENCE, r.verdict());
        assertTrue(r.ciLow() < 0 && r.ciHigh() > 0);
    }

    @Test
    void tinyButConsistentChangeBelowMinEffectIsNotReported() {
        var r = RunComparison.compare(FPS, new double[]{300.0, 300.1, 299.9}, new double[]{301.0, 301.1, 300.9}, 1.0);
        assertTrue(r.ciLow() > 0, "difference is statistically real");
        assertEquals(Verdict.NO_SIGNIFICANT_DIFFERENCE, r.verdict(), "but 0.33% is under the 1% bar");
    }

    @Test
    void lowerIsBetterMetricsFlipDirection() {
        var r = RunComparison.compare(P99, new double[]{10, 10.2, 9.8}, new double[]{14, 14.1, 13.9}, 1.0);
        assertEquals(Verdict.REGRESSION, r.verdict());
    }

    @Test
    void singleRunsAreInsufficient() {
        var r = RunComparison.compare(FPS, new double[]{300}, new double[]{400}, 1.0);
        assertEquals(Verdict.INSUFFICIENT_RUNS, r.verdict());
    }
}
