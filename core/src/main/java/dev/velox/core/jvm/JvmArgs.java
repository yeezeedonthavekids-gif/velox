package dev.velox.core.jvm;

import dev.velox.core.hw.HardwareProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Heap sizing and GC selection for the game JVM.
 *
 * <p>This deliberately avoids the long flag lists from "FPS boost" videos. Most of those were
 * written for Java 8 servers (Aikar's flags) or rely on experimental options. On Java 21+
 * the defaults are good. Only these choices have a measurable client effect:
 * <ul>
 *   <li><b>GC:</b> a measured tradeoff (docs/JVM.md). In the synthetic frame-loop test,
 *       generational ZGC raised 1% lows by ~2.8x and cut the worst frame from ~250 ms to under
 *       80 ms, but lowered average throughput by ~31% on 4 cores. So ZGC is used where frame-time
 *       stability is the goal (Competitive, Balanced) and G1 where average FPS is (Max FPS). G1
 *       is also used on small machines (fewer than 6 threads or under 3 GiB heap), where ZGC's
 *       concurrent threads and headroom cost the most.</li>
 *   <li><b>-Xms = -Xmx:</b> avoids heap-resize work mid-game. The cost is committing the heap
 *       up front, which is why the heap size is kept modest.</li>
 *   <li><b>AlwaysPreTouch (optional):</b> faults in the heap's pages at startup (about 1 s per
 *       4 GiB) instead of during gameplay.</li>
 *   <li><b>Compact object headers (Java 25+):</b> a product feature (JEP 519) that shrinks every
 *       object header, cutting heap use and GC work. The launcher probes the runtime first and
 *       drops any flag it rejects.</li>
 * </ul>
 */
public final class JvmArgs {
    private JvmArgs() {}

    public enum Gc { ZGC, G1 }

    public record Plan(int heapMb, Gc gc, List<String> args, List<String> notes) {}

    /**
     * Recommended heap. A Sodium + FerriteCore client at render distance 12-16 uses roughly
     * 1.5-3 GiB of live heap. A bigger heap does not raise FPS; it only delays collections and
     * takes memory from the OS, the GPU driver and the browser you have open.
     */
    public static int recommendedHeapMb(long totalRamMb, int renderDistance) {
        int mb;
        if (totalRamMb < 6_000) mb = 2048;
        else if (totalRamMb < 12_000) mb = 3072;
        else if (totalRamMb < 24_000) mb = renderDistance > 20 ? 6144 : 4096;
        else mb = renderDistance > 20 ? 8192 : 6144;
        // Never more than half of physical RAM: the OS, driver and off-heap buffers need the rest.
        long cap = Math.max(1024, totalRamMb / 2);
        return (int) Math.min(mb, cap);
    }

    /** @param preferThroughput true when average FPS matters more than worst-case frame time */
    public static Gc recommendedGc(HardwareProfile hw, int heapMb, int javaMajor, boolean preferThroughput) {
        if (preferThroughput) return Gc.G1;
        if (javaMajor < 21) return Gc.G1; // generational ZGC arrived in 21
        if (hw.logicalCores() < 6 || heapMb < 3072) return Gc.G1;
        return Gc.ZGC;
    }

    public static Plan plan(HardwareProfile hw, int heapMb, Gc gc, int javaMajor, boolean preTouch) {
        List<String> args = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        args.add("-Xms" + heapMb + "m");
        args.add("-Xmx" + heapMb + "m");
        switch (gc) {
            case ZGC -> {
                args.add("-XX:+UseZGC");
                // In 21-22 generational mode is opt-in; from 23 it is the default and from 24 the
                // only mode, where passing the flag only prints a warning.
                if (javaMajor >= 21 && javaMajor <= 22) args.add("-XX:+ZGenerational");
                notes.add("Generational ZGC: concurrent collector with sub-millisecond pauses");
            }
            case G1 -> {
                // Default pause target: lowering it to 50 ms made no significant difference
                // in the synthetic test (docs/JVM.md), so it is not set.
                args.add("-XX:+UseG1GC");
                notes.add("G1 (highest throughput; occasional longer pauses)");
            }
        }
        if (javaMajor >= 25) {
            args.add("-XX:+UseCompactObjectHeaders");
            notes.add("Compact object headers (JEP 519) reduce heap use");
        }
        if (preTouch) {
            args.add("-XX:+AlwaysPreTouch");
            notes.add("Heap pre-touched at startup: slower launch, fewer page faults in game");
        }
        return new Plan(heapMb, gc, List.copyOf(args), List.copyOf(notes));
    }
}
