package dev.velox.mod.bench;

import dev.velox.core.bench.BenchmarkReport;
import dev.velox.core.bench.ChunkLoadTracker;
import dev.velox.core.bench.FrameStats;
import dev.velox.core.bench.FrameTimeRecorder;
import dev.velox.core.bench.GcMonitor;
import dev.velox.core.bench.ResourceSampler;
import dev.velox.core.config.VeloxConfig;
import dev.velox.mod.VeloxMod;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Records a benchmark run: warm-up, then a fixed-length recording of every frame, GC pause,
 * heap/CPU sample and client chunk count, written as JSON + CSV for {@code velox bench compare}.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>{@code static}: the player stands still; measures steady-state rendering.</li>
 *   <li>{@code flight}: singleplayer only. Holds forward + sprint at a fixed heading so new
 *       chunks stream in at a repeatable rate. Automated input is refused on servers.</li>
 *   <li>{@code free}: the player moves along their own route (villages, forests, multiplayer).</li>
 * </ul>
 */
public final class BenchmarkController {
    public static final BenchmarkController INSTANCE = new BenchmarkController();

    private enum State { IDLE, WARMUP, RECORDING }

    private State state = State.IDLE;
    private String scenario;
    private long phaseEndNanos;
    private long warmupNanos;
    private FrameTimeRecorder recorder;
    private GcMonitor gc;
    private ResourceSampler sampler;
    private final ChunkLoadTracker chunks = new ChunkLoadTracker();
    private int tickCounter;
    private float flightYaw;
    private String status;
    private long startupMs = -1;

    private BenchmarkController() {}

    /** Shown at the top of the HUD while a run is active; null when idle. */
    public String statusLine() {
        return status;
    }

    public boolean running() {
        return state != State.IDLE;
    }

    /** Render thread, every frame. Must not allocate. */
    public void onFrame(long frameNanos) {
        if (state == State.RECORDING) recorder.record(frameNanos);
    }

    public void toggle(Minecraft mc) {
        if (state == State.IDLE) start(mc, VeloxMod.config().benchmark.scenario);
        else cancel(mc, "Benchmark cancelled");
    }

    public void start(Minecraft mc, String scenarioName) {
        if (state != State.IDLE) return;
        if (mc.level == null || mc.player == null) {
            VeloxMod.message(mc, "Join a world before starting a benchmark");
            return;
        }
        if (scenarioName.equals("flight") && mc.getSingleplayerServer() == null) {
            VeloxMod.message(mc, "The flight scenario drives movement automatically, so it only runs in singleplayer");
            return;
        }
        VeloxConfig.Benchmark cfg = VeloxMod.config().benchmark;
        scenario = scenarioName;
        warmupNanos = cfg.warmupSeconds * 1_000_000_000L;
        // Sized for the whole run at up to 2000 FPS; allocated once, here, not per frame.
        recorder = new FrameTimeRecorder(Math.max(10_000, cfg.durationSeconds * 2000));
        sampler = new ResourceSampler();
        chunks.begin(System.nanoTime());
        flightYaw = mc.player.getYRot();
        state = State.WARMUP;
        phaseEndNanos = System.nanoTime() + warmupNanos;
        VeloxMod.message(mc, String.format(Locale.ROOT, "Benchmark '%s': %ds warm-up, then %ds recording. Press again to cancel.",
                scenario, cfg.warmupSeconds, cfg.durationSeconds));
    }

    public void tick(Minecraft mc) {
        if (startupMs < 0) {
            // First client tick: JVM start → game ready (title screen).
            startupMs = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime();
            VeloxMod.LOG.info("Startup took {} ms (JVM start to first client tick)", startupMs);
        }
        if (state == State.IDLE) return;
        if (mc.level == null || mc.player == null) {
            cancel(mc, "Benchmark cancelled: left the world");
            return;
        }
        long now = System.nanoTime();
        if (scenario.equals("flight")) drive(mc, true);
        if (mc.level.getChunkSource() != null) chunks.sample(now, mc.level.getChunkSource().getLoadedChunksCount());

        long remaining = Math.max(0, (phaseEndNanos - now) / 1_000_000_000L);
        if (state == State.WARMUP) {
            status = "BENCH warm-up " + remaining + "s";
            if (now >= phaseEndNanos) {
                state = State.RECORDING;
                gc = new GcMonitor(100_000).start();
                phaseEndNanos = now + VeloxMod.config().benchmark.durationSeconds * 1_000_000_000L;
            }
        } else {
            status = "REC " + remaining + "s";
            if (++tickCounter % 5 == 0) sampler.sample();
            if (now >= phaseEndNanos) finish(mc);
        }
    }

    private void drive(Minecraft mc, boolean on) {
        mc.options.keyUp.setDown(on);
        mc.options.keySprint.setDown(on);
        if (on) {
            mc.player.setYRot(flightYaw);
            mc.player.setXRot(0f);
            if (mc.player.getAbilities().mayfly) mc.player.getAbilities().flying = true;
        }
    }

    private void cancel(Minecraft mc, String why) {
        if (scenario != null && scenario.equals("flight")) drive(mc, false);
        if (gc != null) gc.close();
        reset();
        VeloxMod.message(mc, why);
    }

    private void reset() {
        state = State.IDLE;
        status = null;
        recorder = null;
        gc = null;
        tickCounter = 0;
    }

    private void finish(Minecraft mc) {
        if (scenario.equals("flight")) drive(mc, false);
        long[] frames = recorder.snapshot();
        long dropped = recorder.dropped();
        GcMonitor.Summary gcSummary = gc.summary();
        gc.close();
        String label = System.getProperty("velox.profile", "unknown").toLowerCase(Locale.ROOT) + "-"
                + VeloxMod.config().preset.name().toLowerCase(Locale.ROOT);
        BenchmarkReport report = new BenchmarkReport(
                BenchmarkReport.SCHEMA, label, scenario,
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                warmupNanos / 1e9,
                environment(mc),
                mods(),
                FrameStats.of(frames),
                dropped,
                gcSummary,
                new BenchmarkReport.Heap(ResourceSampler.maxHeapMb(), sampler.avgHeapUsedMb(), sampler.peakHeapUsedMb()),
                sampler.avgProcessCpuPct(),
                chunks.result());
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path dir = FabricLoader.getInstance().getGameDir().resolve(VeloxMod.config().benchmark.outputDir).resolve(scenario).resolve(label);
        FrameStats s = report.stats();
        reset();
        VeloxMod.message(mc, String.format(Locale.ROOT, "Done: avg %.0f FPS, 1%% low %.0f, 0.1%% low %.0f, %d GC pauses (max %.1f ms)",
                s.avgFps(), s.low1PctFps(), s.low01PctFps(), gcSummary.pauses(), gcSummary.maxMs()));
        // Writing tens of thousands of CSV lines would hitch a frame; do it off-thread.
        Thread writer = new Thread(() -> {
            try {
                report.write(dir, stamp, frames);
                VeloxMod.LOG.info("Benchmark saved to {}", dir.resolve(stamp + ".json"));
                mc.execute(() -> VeloxMod.message(mc, "Saved " + dir.getFileName() + "/" + stamp + ".json"));
            } catch (Exception e) {
                VeloxMod.LOG.error("Could not save benchmark", e);
                mc.execute(() -> VeloxMod.message(mc, "Could not save benchmark: " + e.getMessage()));
            }
        }, "velox-bench-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private Map<String, String> environment(Minecraft mc) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("minecraft", version("minecraft"));
        env.put("velox_profile", System.getProperty("velox.profile", "unknown"));
        env.put("preset", VeloxMod.config().preset.name());
        env.put("hardware", System.getProperty("velox.hardware", "unknown"));
        env.put("java", System.getProperty("java.runtime.version"));
        env.put("gc", GcMonitor.collectorName());
        env.put("jvm_args", String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()));
        env.put("render_distance", String.valueOf(mc.options.renderDistance().get()));
        env.put("simulation_distance", String.valueOf(mc.options.simulationDistance().get()));
        env.put("max_fps", String.valueOf(mc.options.framerateLimit().get()));
        env.put("vsync", String.valueOf(mc.options.enableVsync().get()));
        env.put("singleplayer", String.valueOf(mc.getSingleplayerServer() != null));
        env.put("startup_ms", String.valueOf(startupMs));
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        return env;
    }

    private static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    private static List<String> mods() {
        List<String> out = new ArrayList<>();
        for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
            String id = m.getMetadata().getId();
            // Skip Fabric API's dozens of submodules and built-ins; keep top-level mods.
            if (m.getContainingMod().isPresent() || id.equals("java") || id.equals("minecraft") || id.equals("fabricloader")) continue;
            out.add(id + "@" + m.getMetadata().getVersion().getFriendlyString());
        }
        out.sort(null);
        return out;
    }
}
