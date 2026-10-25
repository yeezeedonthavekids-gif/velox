import dev.velox.core.bench.BenchmarkReport;
import dev.velox.core.bench.ChunkLoadTracker;
import dev.velox.core.bench.FrameStats;
import dev.velox.core.bench.FrameTimeRecorder;
import dev.velox.core.bench.GcMonitor;
import dev.velox.core.bench.ResourceSampler;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SYNTHETIC frame-loop workload for comparing GC settings. It is NOT Minecraft: it only
 * reproduces the allocation pattern that matters for GC pauses in a game client.
 * <ul>
 *   <li>a large long-lived "world" (~600 MiB of small objects) that every full marking must trace</li>
 *   <li>per-frame short-lived garbage (~1 MiB/frame: vertex scratch, strings, boxed values)</li>
 *   <li>a "chunk mesh cache" whose entries (64 KiB) are replaced a few per frame, so data
 *       survives long enough to be promoted and then dies (old-generation churn)</li>
 *   <li>~1.5 ms of CPU work per frame</li>
 * </ul>
 * Usage: java [gc flags] -cp velox-launcher.jar:. GcFrameSim <label> <seconds> <outDir>
 */
public final class GcFrameSim {
    static final class Node { long a, b; Node next; Object payload; }

    static volatile long sink;

    public static void main(String[] args) throws Exception {
        String label = args[0];
        int seconds = Integer.parseInt(args[1]);
        Path out = Path.of(args[2]);

        Node[] world = new Node[6_000_000];          // ~600 MiB retained
        for (int i = 0; i < world.length; i++) { world[i] = new Node(); world[i].a = i; if (i > 0) world[i].next = world[i - 1]; }
        byte[][] meshCache = new byte[2048][];       // up to 128 MiB, churned
        for (int i = 0; i < meshCache.length; i++) meshCache[i] = new byte[64 * 1024];

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // 10 s warm-up (JIT, heap sizing), then the measured window.
        long warmupEnd = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < warmupEnd) frame(world, meshCache, rnd);

        FrameTimeRecorder rec = new FrameTimeRecorder(5_000_000);
        ResourceSampler sampler = new ResourceSampler();
        GcMonitor gc = new GcMonitor(1_000_000).start();
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        long last = System.nanoTime(), lastSample = last;
        while (last < end) {
            frame(world, meshCache, rnd);
            long now = System.nanoTime();
            rec.record(now - last);
            last = now;
            if (now - lastSample > 250_000_000L) { sampler.sample(); lastSample = now; }
        }
        Thread.sleep(200); // let the last GC notifications arrive
        long[] frames = rec.snapshot();
        GcMonitor.Summary s = gc.summary();
        gc.close();
        new BenchmarkReport(BenchmarkReport.SCHEMA, label, "gc-sim", java.time.Instant.now().toString(), 10,
                Map.of("jvm_args", String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()),
                        "java", System.getProperty("java.runtime.version"),
                        "cores", String.valueOf(Runtime.getRuntime().availableProcessors())),
                List.of(), FrameStats.of(frames), rec.dropped(), s,
                new BenchmarkReport.Heap(ResourceSampler.maxHeapMb(), sampler.avgHeapUsedMb(), sampler.peakHeapUsedMb()),
                sampler.avgProcessCpuPct(), new ChunkLoadTracker.Result(0, -1, -1, -1, 0))
                .write(out, label + "-" + System.currentTimeMillis(), frames);
        FrameStats f = FrameStats.of(frames);
        System.out.printf("%s: avg %.0f fps, 1%% low %.0f, 0.1%% low %.0f, max %.1f ms, GC %d pauses max %.2f ms%n",
                label, f.avgFps(), f.low1PctFps(), f.low01PctFps(), f.maxFrameMs(), s.pauses(), s.maxMs());
        sink += world.length;
    }

    static void frame(Node[] world, byte[][] meshCache, ThreadLocalRandom rnd) {
        long acc = 0;
        // Short-lived garbage: ~1 MiB per frame.
        for (int i = 0; i < 12_000; i++) {
            Node n = new Node();
            n.a = rnd.nextLong();
            n.payload = (i & 7) == 0 ? Long.toString(n.a) : null;
            acc += n.a;
        }
        float[] scratch = new float[64 * 1024];  // 256 KiB vertex scratch
        for (int i = 0; i < scratch.length; i += 64) scratch[i] = i;
        // Mesh rebuilds: replace 3 cached entries; old ones become old-gen garbage.
        for (int i = 0; i < 3; i++) meshCache[rnd.nextInt(meshCache.length)] = new byte[64 * 1024];
        // Occasional mutation of the long-lived world (keeps card marking / barriers honest).
        for (int i = 0; i < 200; i++) world[rnd.nextInt(world.length)].payload = (i & 1) == 0 ? null : new Node();
        // ~1.5 ms of CPU work.
        long until = System.nanoTime() + 1_500_000;
        while (System.nanoTime() < until) acc += acc * 31 + 7;
        sink += acc + (long) scratch[64];
    }
}
