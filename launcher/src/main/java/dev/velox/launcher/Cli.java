package dev.velox.launcher;

import dev.velox.core.bench.BenchmarkReport;
import dev.velox.core.bench.FrameStats;
import dev.velox.core.bench.RunComparison;
import dev.velox.core.config.Preset;
import dev.velox.core.config.PresetEngine;
import dev.velox.core.hw.HardwareProfile;
import dev.velox.core.jvm.JvmArgs;
import dev.velox.launcher.auth.MicrosoftAuth;
import dev.velox.launcher.hw.HardwareDetector;
import dev.velox.launcher.launch.GameProcess;
import dev.velox.launcher.launch.LaunchPlan;
import dev.velox.launcher.mods.ModCatalog;
import dev.velox.launcher.mods.ModResolver;
import dev.velox.launcher.net.DownloadQueue;
import dev.velox.launcher.net.Http;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Command-line interface. Every GUI action is also available here, for scripts and benchmarks. */
public final class Cli {
    private final PrintStream out;
    private final LauncherPaths paths;
    private final Endpoints endpoints;

    public Cli(PrintStream out, LauncherPaths paths, Endpoints endpoints) {
        this.out = out;
        this.paths = paths;
        this.endpoints = endpoints;
    }

    static final String USAGE = """
            Velox launcher

            Usage: velox <command> [options]

            Commands:
              hardware                 Show detected hardware and the recommended preset/JVM setup
              install                  Install or repair Minecraft, Fabric and the optimization mods
              login | logout           Sign in with a Microsoft account (device code) / forget it
              launch                   Launch the game (installs first if needed)
              status                   Show the installed version and components
              bench compare --baseline <dir> --candidate <dir> [--min-effect <pct>]
                                       Compare repeated benchmark runs with a significance test
              bench show <report.json>...
              gui                      Open the launcher window (default with no command)

            Options (install / launch):
              --version <id|auto>      Minecraft release (default: newest supported by required mods)
              --loader <version>       Fabric loader version (default: newest stable)
              --instance <name>        Instance folder (default: "default")
              --game-dir <path>        Use this game directory instead of the instance folder
              --profile optimized|baseline   baseline = Fabric API + Velox only, for A/B benchmarks
              --preset max_fps|competitive|balanced|custom
              --enable <slug,...>      Also install these catalog mods (e.g. moreculling)
              --disable <slug,...>     Don't install these optional mods
              --heap <MiB>             Heap size (default: recommended for this machine)
              --gc auto|zgc|g1         Garbage collector
              --pretouch               Pre-touch the heap at startup
              --java <path>            Use this Java executable instead of Mojang's runtime
              --jvm-arg <arg>          Extra JVM argument (repeatable)
              --game-arg <arg>         Extra game argument (repeatable)
              --client-id <id>         Azure app client ID for Microsoft sign-in (see docs/AUTH.md)
              --dry-run                Print the launch command (token redacted) instead of launching
              --save                   Store the given options in launcher.json
            """;

    public int run(String[] args) {
        try {
            if (args.length == 0) {
                out.print(USAGE);
                return 0;
            }
            String cmd = args[0];
            List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
            return switch (cmd) {
                case "help", "--help", "-h" -> { out.print(USAGE); yield 0; }
                case "hardware" -> hardware();
                case "install" -> install(rest);
                case "launch" -> launch(rest);
                case "login" -> login(rest);
                case "logout" -> { launcher().signOut(); out.println("Signed out; saved sign-in removed."); yield 0; }
                case "status" -> status(rest);
                case "bench" -> bench(rest);
                default -> { out.println("Unknown command: " + cmd); out.print(USAGE); yield 2; }
            };
        } catch (IllegalArgumentException e) {
            out.println("Error: " + e.getMessage());
            return 2;
        } catch (IOException | RuntimeException e) {
            out.println("Error: " + e.getMessage());
            if (System.getenv("VELOX_DEBUG") != null) e.printStackTrace(out);
            return 1;
        }
    }

    private VeloxLauncher launcher() {
        return new VeloxLauncher(new Http(), endpoints, paths);
    }

    /** Parses shared options into settings (loaded from launcher.json first). */
    Settings settings(List<String> args, List<String> flagsOut) throws IOException {
        Settings s = Settings.load(paths.settingsFile());
        boolean save = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--version" -> s.gameVersion = value(args, ++i, a);
                case "--loader" -> s.loaderVersion = value(args, ++i, a);
                case "--instance" -> s.instance = value(args, ++i, a);
                case "--game-dir" -> s.gameDirOverride = value(args, ++i, a);
                case "--profile" -> s.profile = ModCatalog.Profile.valueOf(value(args, ++i, a).toUpperCase(Locale.ROOT));
                case "--preset" -> s.preset = Preset.valueOf(value(args, ++i, a).toUpperCase(Locale.ROOT).replace('-', '_'));
                case "--enable" -> s.enableMods.addAll(Arrays.asList(value(args, ++i, a).split(",")));
                case "--disable" -> s.disableMods.addAll(Arrays.asList(value(args, ++i, a).split(",")));
                case "--heap" -> s.heapMb = Integer.parseInt(value(args, ++i, a));
                case "--gc" -> s.gc = value(args, ++i, a);
                case "--pretouch" -> s.preTouchHeap = true;
                case "--java" -> s.javaPath = value(args, ++i, a);
                case "--jvm-arg" -> s.extraJvmArgs.add(value(args, ++i, a));
                case "--game-arg" -> s.extraGameArgs.add(value(args, ++i, a));
                case "--client-id" -> s.msaClientId = value(args, ++i, a);
                case "--save" -> save = true;
                case "--dry-run", "--new-account", "--verify" -> flagsOut.add(a);
                default -> throw new IllegalArgumentException("Unknown option " + a);
            }
        }
        if (s.heapMb != 0 && s.heapMb < 1024) throw new IllegalArgumentException("--heap below 1024 MiB will not run Minecraft");
        if (!List.of("auto", "zgc", "g1").contains(s.gc.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("--gc must be auto, zgc or g1");
        paths.instance(s.instance); // validates the name
        if (save) {
            s.save(paths.settingsFile());
            out.println("Saved settings to " + paths.settingsFile());
        }
        return s;
    }

    private static String value(List<String> args, int i, String opt) {
        if (i >= args.size()) throw new IllegalArgumentException(opt + " needs a value");
        return args.get(i);
    }

    private int hardware() {
        HardwareProfile hw = HardwareDetector.detect();
        out.println("Hardware: " + hw.describe());
        for (Preset p : new Preset[]{Preset.MAX_FPS, Preset.COMPETITIVE, Preset.BALANCED}) {
            var gs = PresetEngine.resolve(p, hw, null);
            int heap = JvmArgs.recommendedHeapMb(hw.totalRamMb(), gs.renderDistance);
            JvmArgs.Plan jp = JvmArgs.plan(hw, heap, JvmArgs.recommendedGc(hw, heap, 25, p == Preset.MAX_FPS), 25, false);
            out.printf(Locale.ROOT, "  %-12s render %2d, simulation %d, heap %d MiB, GC %s%n",
                    p.displayName, gs.renderDistance, gs.simulationDistance, heap, jp.gc());
        }
        out.println("Recommendation: start with Competitive or Balanced; use Max FPS only if frame rate matters more than view distance.");
        return 0;
    }

    private DownloadQueue.Progress progress() {
        long[] last = {0};
        return (done, total, bytes) -> {
            long now = System.currentTimeMillis();
            if (now - last[0] > 1000 || done == total) {
                last[0] = now;
                out.printf(Locale.ROOT, "\r  %d/%d files, %.1f MiB downloaded   ", done, total, bytes / 1048576.0);
                if (done == total) out.println();
            }
        };
    }

    private int install(List<String> args) throws IOException {
        List<String> flags = new ArrayList<>();
        Settings s = settings(args, flags);
        HardwareProfile hw = HardwareDetector.detect();
        VeloxLauncher l = launcher();
        Path mod = VeloxLauncher.findBundledMod(paths.root().resolve("cache"));
        VeloxLauncher.InstallState st = l.install(s, hw, mod, progress());
        printState(st, s.gameDir(paths));
        return 0;
    }

    private void printState(VeloxLauncher.InstallState st, Path gameDir) {
        out.println("Minecraft " + st.gameVersion + " + Fabric Loader " + st.loaderVersion + " (" + st.profile + ")");
        out.println("Game directory: " + gameDir);
        out.println("Java: " + (st.javaComponent == null ? "?" : st.javaComponent) + " (Java " + st.javaMajor + ")");
        out.println("Mods:");
        for (ModResolver.Planned m : st.mods) {
            out.printf(Locale.ROOT, "  %-18s %-22s %-8s %s%n", m.title(), m.versionNumber(), m.license(), m.reason());
        }
        for (ModResolver.Skipped sk : st.skipped) out.printf(Locale.ROOT, "  (skipped) %-12s %s%n", sk.slug(), sk.reason());
    }

    private int status(List<String> args) throws IOException {
        Settings s = settings(args, new ArrayList<>());
        VeloxLauncher.InstallState st = VeloxLauncher.readState(s.gameDir(paths));
        if (st == null) {
            out.println("Not installed yet. Run: velox install");
            return 1;
        }
        printState(st, s.gameDir(paths));
        return 0;
    }

    private int login(List<String> args) throws IOException {
        List<String> flags = new ArrayList<>();
        Settings s = settings(args, flags);
        MicrosoftAuth.Session session = launcher().signIn(s, this::showCode, true);
        out.println("Signed in as " + session.username());
        return 0;
    }

    private void showCode(MicrosoftAuth.DeviceCode dc) {
        out.println();
        out.println("To sign in, open " + dc.verificationUri() + " and enter the code: " + dc.userCode());
        out.println("(Velox never sees your password; sign-in happens on Microsoft's website.)");
    }

    private int launch(List<String> args) throws IOException {
        List<String> flags = new ArrayList<>();
        Settings s = settings(args, flags);
        boolean dryRun = flags.contains("--dry-run");
        HardwareProfile hw = HardwareDetector.detect();
        VeloxLauncher l = launcher();
        Path gameDir = s.gameDir(paths);
        VeloxLauncher.InstallState st = VeloxLauncher.readState(gameDir);
        if (st == null) {
            out.println("Not installed yet; installing first.");
            st = l.install(s, hw, VeloxLauncher.findBundledMod(paths.root().resolve("cache")), progress());
        } else {
            VeloxLauncher.applyPreset(s, hw, gameDir, new ModResolver.Plan(st.gameVersion, st.mods, st.skipped));
        }
        VeloxLauncher.JavaChoice java = l.java(s, st, progress());
        LaunchPlan.Identity id;
        if (dryRun) {
            id = new LaunchPlan.Identity("Player", "00000000-0000-0000-0000-000000000000", "0", "<access-token>", s.msaClientId);
        } else {
            MicrosoftAuth.Session session = l.signIn(s, this::showCode, flags.contains("--new-account"));
            id = new LaunchPlan.Identity(session.username(), session.uuid(), session.xuid(), session.accessToken(), s.msaClientId);
        }
        LaunchPlan plan = l.plan(s, hw, st, java, id, true);
        if (dryRun) {
            out.println(String.join(" ", plan.redacted()));
            return 0;
        }
        GameProcess.Exit exit = GameProcess.run(plan, false);
        if (exit.code() != 0) {
            out.println("Minecraft exited with code " + exit.code() + ". Last log lines:");
            out.println(exit.tail());
            if (exit.crashReport() != null) out.println("Crash report: " + exit.crashReport());
        }
        return exit.code() == 0 ? 0 : 1;
    }

    private int bench(List<String> args) throws IOException {
        if (args.isEmpty()) throw new IllegalArgumentException("bench needs a subcommand: compare or show");
        switch (args.get(0)) {
            case "show" -> {
                for (String f : args.subList(1, args.size())) printReport(BenchmarkReport.read(Path.of(f)), f);
                return 0;
            }
            case "compare" -> {
                Path base = null, cand = null;
                double minEffect = 1.0;
                for (int i = 1; i < args.size(); i++) {
                    switch (args.get(i)) {
                        case "--baseline" -> base = Path.of(value(args, ++i, "--baseline"));
                        case "--candidate" -> cand = Path.of(value(args, ++i, "--candidate"));
                        case "--min-effect" -> minEffect = Double.parseDouble(value(args, ++i, "--min-effect"));
                        default -> throw new IllegalArgumentException("Unknown option " + args.get(i));
                    }
                }
                if (base == null || cand == null) throw new IllegalArgumentException("bench compare needs --baseline and --candidate");
                List<BenchmarkReport> a = reports(base), b = reports(cand);
                checkComparable(a, b);
                out.printf(Locale.ROOT, "Baseline: %d runs from %s%nCandidate: %d runs from %s%n", a.size(), base, b.size(), cand);
                if (a.size() < 3 || b.size() < 3) out.println("Note: fewer than 3 runs per side; confidence intervals will be wide.");
                for (RunComparison.MetricResult r : RunComparison.compare(stats(a), stats(b), minEffect)) {
                    out.println(r.describe());
                }
                out.printf(Locale.ROOT, "Verdicts need the 95%% CI to exclude zero AND |change| >= %.1f%%.%n", minEffect);
                return 0;
            }
            default -> throw new IllegalArgumentException("Unknown bench subcommand " + args.get(0));
        }
    }

    private static void checkComparable(List<BenchmarkReport> a, List<BenchmarkReport> b) {
        java.util.Set<String> scenarios = new java.util.TreeSet<>();
        for (BenchmarkReport r : a) scenarios.add(r.scenario());
        for (BenchmarkReport r : b) scenarios.add(r.scenario());
        if (scenarios.size() > 1) {
            throw new IllegalArgumentException("Runs come from different scenarios " + scenarios + "; compare like with like");
        }
    }

    private static List<BenchmarkReport> reports(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) throw new IOException("Not a directory: " + dir);
        List<BenchmarkReport> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".json")).sorted().toList()) out.add(BenchmarkReport.read(p));
        }
        if (out.isEmpty()) throw new IOException("No benchmark reports (*.json) in " + dir);
        return out;
    }

    private static List<FrameStats> stats(List<BenchmarkReport> rs) {
        List<FrameStats> out = new ArrayList<>();
        for (BenchmarkReport r : rs) out.add(r.stats());
        return out;
    }

    private void printReport(BenchmarkReport r, String file) {
        FrameStats s = r.stats();
        out.printf(Locale.ROOT, "%s [%s / %s]%n", file, r.label(), r.scenario());
        out.printf(Locale.ROOT, "  %d frames over %.1f s: avg %.1f FPS, 1%% low %.1f, 0.1%% low %.1f%n",
                s.frames(), s.durationSec(), s.avgFps(), s.low1PctFps(), s.low01PctFps());
        out.printf(Locale.ROOT, "  frame time p50 %.2f / p95 %.2f / p99 %.2f / max %.2f ms, %d spikes, %d frames > 50 ms%n",
                s.p50FrameMs(), s.p95FrameMs(), s.p99FrameMs(), s.maxFrameMs(), s.spikes(), s.framesOver50Ms());
        if (r.gc() != null) {
            out.printf(Locale.ROOT, "  GC (%s): %d pauses, total %.1f ms, max %.2f ms%n",
                    r.gc().collector(), r.gc().pauses(), r.gc().totalMs(), r.gc().maxMs());
        }
        if (r.heap() != null) {
            out.printf(Locale.ROOT, "  heap: avg %.0f / peak %.0f of %.0f MiB; process CPU %.1f%%%n",
                    r.heap().avgUsedMb(), r.heap().peakUsedMb(), r.heap().maxMb(), r.avgProcessCpuPct());
        }
        if (r.chunks() != null && r.chunks().maxChunks() > 0) {
            out.printf(Locale.ROOT, "  chunks: %d loaded; 50%% at %d ms, 90%% at %d ms%n",
                    r.chunks().maxChunks(), r.chunks().msTo50Pct(), r.chunks().msTo90Pct());
        }
    }
}
