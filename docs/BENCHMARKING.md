# Benchmarking Velox

Every performance claim about Velox should come from this procedure, run on your own hardware.
The repository contains **no in-game FPS results**. They could not be measured where Velox was
built (no GPU, and the game servers were unreachable), and made-up numbers are worse than none.

## What gets measured

The Velox mod records, per run:

| Metric | How |
|---|---|
| Average FPS | frames ÷ wall time (time-weighted, not the mean of per-frame FPS) |
| 1% / 0.1% low FPS | 1000 ÷ mean of the slowest 1% / 0.1% of frame times |
| Frame-time distribution | p50 / p95 / p99 / p99.9 / max, plus every frame in `*.frames.csv` |
| Stutter | frames > 2× median *and* > median + 4 ms; frames > 50 ms |
| GC pauses | count, total, max, p99, via JMX notifications (concurrent ZGC cycles excluded, since they don't stop the game) |
| Memory | heap max / average / peak |
| CPU | average process CPU load (% of all logical cores) |
| Chunk loading | time until 50% / 90% / 100% of the peak client chunk count |
| Startup | JVM start → first client tick (in the report's `environment.startup_ms`) |
| GPU | utilization, clocks and power via `bench/gpu-log.ps1` (nvidia-smi), logged alongside |

Frame time is the interval between the starts of successive `Minecraft.runTick` calls, which
is the cadence the player sees. For presentation latency (click-to-photon), use
[PresentMon](https://github.com/GameTechDev/PresentMon) (`--process_name javaw.exe`) during the
same run. Its `MsBetweenPresents` should match Velox's frame times, and `MsGPUBusy` tells you
whether you are GPU-bound.

## Baseline and candidate

Compare like with like. Only the thing under test may differ:

- **Baseline**: `velox install --profile baseline --instance baseline` installs the same Minecraft
  version with only Fabric API and the Velox recorder, with no optimization mods.
- **Candidate**: `velox install --instance default` installs the optimized set.
- Same preset (`--preset balanced` for both), same heap and GC, same resolution and window mode,
  same world and position, same driver settings.

To test a single mod, use `--disable <slug>` on a copy of the candidate instance. For a JVM flag,
use `--gc` or `--jvm-arg`, keeping everything else fixed.

## Procedure

1. **Prepare the world once.** Create a singleplayer world with a fixed seed (the docs use
   `-4172144997902289642`), creative mode, render distance from the preset. Note a start
   position and heading, e.g. `/tp @s 0 120 0 0 0`. Copy the world folder into both instances'
   `saves/`.
2. **Prepare the machine.** Plug in the laptop, close browsers and overlays, and use fullscreen
   at your native resolution. Leave the PC idle for a minute before the first run.
3. **For each run:** launch, load the world, `/tp` to the start, open Velox (Right Shift) → Benchmark,
   choose the scenario, and Start (or press F8). The 10 s warm-up is excluded. Don't touch input
   during `static` and `flight`.
4. **Repeat at least 3 times per side (5 is better) and interleave them**: baseline, candidate,
   baseline, candidate, ... Thermal drift and background tasks then hit both sides equally.
5. **Compare:**

   ```
   velox bench compare \
     --baseline  <velox-home>/instances/baseline/velox-benchmarks/static/baseline-balanced \
     --candidate <velox-home>/instances/default/velox-benchmarks/static/optimized-balanced
   ```

   Example output format:

   ```
   avg_fps   base  xxx.xx +/-x.xx   cand  yyy.yy +/-y.yy   diff +zz.zz (+zz.zz%) 95%CI [+a, +b] p=0.0001  IMPROVEMENT
   ```

   A result counts as an improvement or regression only if the 95% confidence interval of the
   difference excludes zero **and** the change is at least `--min-effect` percent (default 1%).
   Everything else is reported as `NO_SIGNIFICANT_DIFFERENCE`. With fewer than 3 runs per side
   the tool warns, and with 1 run it refuses to call a verdict.

Why per-run and not per-frame: consecutive frames are strongly correlated, so 60 000 frames from
one run are not 60 000 independent samples. Treating them as independent would make noise look
significant. Velox's statistics use each run's summary as one observation (Welch's t-test).

## Scenarios

| # | Scenario | Velox mode | Notes |
|---|---|---|---|
| 1 | Standing still, normal world | `static` | Fixed position and heading; pure rendering cost |
| 2 | Moving through new chunks | `flight` | Singleplayer only; auto forward + sprint while flying at a fixed heading. Use a **new** world copy per run so chunks are really new. |
| 3 | Dense forest / complex terrain | `static` or `free` | e.g. a dark-oak forest or jungle at the start position |
| 4 | Village / entity-heavy | `static` | Stand in a village, or spawn a fixed mob count with `/summon` before starting |
| 5 | Multiplayer | `free` | Same server, same spot, similar player count. Expect more noise; take 5+ runs |
| 6 | High render distance | any | Run 1-4 with `--preset custom` and render distance 24-32 |

The `flight` scenario automates movement, so Velox refuses it on servers. Benchmarks are
measurement only and give no gameplay advantage.

## GPU / CPU bound?

Run `bench/gpu-log.ps1` in a second terminal during a run, then `bench/summarize-gpu.py`:

- GPU utilization ≥ 95%: GPU-bound. Resolution, render distance and shaders are the levers.
- GPU utilization well below 90% with uncapped FPS: CPU- or driver-bound. Chunk meshing
  (biome blend, AO), entity count and the main game thread are the levers. Check the report's
  `avgProcessCpuPct`. A single saturated core shows as ~6% of 16 threads.

## The synthetic GC experiment

`bench/gc-sim/run-gc-sim.sh <velox-launcher.jar> [runs] [seconds]` runs a **synthetic** frame loop
(not Minecraft) that reproduces a client's allocation pattern under each GC setting, then
compares them with the same statistics. The results and their limits are in [JVM.md](JVM.md).
