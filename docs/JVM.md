# JVM settings, and the evidence for them

Velox does not use the long flag lists from "FPS boost" videos. Most were written for Java 8
servers (Aikar's flags), rely on experimental options, or were never measured on a client.
What Velox sets, and why:

| Flag | When | Evidence |
|---|---|---|
| `-Xms<N> -Xmx<N>` | always | Avoids heap resizing mid-game. N = 4 GiB on a 16 GB machine (6 GiB above render distance 20), never more than half of RAM |
| `-XX:+UseZGC` (generational) | Competitive and Balanced, on 6+ threads with ≥ 3 GiB heap | Synthetic test below: far better worst-case frames, at a throughput cost |
| `-XX:+UseG1GC` | Max FPS, and small machines | Synthetic test below: highest average throughput |
| `-XX:+ZGenerational` | only on Java 21–22 | Default from Java 23; on 24+ the flag only prints a warning |
| `-XX:+UseCompactObjectHeaders` | Java 25+ | Product feature (JEP 519): smaller object headers mean less heap and GC work. Probed per runtime |
| `-XX:+AlwaysPreTouch` | opt-in (`--pretouch`) | Moves page-fault cost to startup. Not measured yet, so not on by default |

Every flag is **probed against the actual Java runtime before launch**. The launcher runs
`java <flags> -version`, and any flag the runtime rejects or warns about is dropped and logged,
so a JVM update can never stop the game from starting.

**Not used:** `MaxGCPauseMillis` (no significant effect, see below), `UseLargePages` (needs a
Windows privilege change), `UnlockExperimentalVMOptions`-gated flags, `-XX:+DisableExplicitGC`
(no evidence of a problem to fix), and thread-priority or registry changes. None of those
reliably raise FPS.

## Synthetic GC experiment

`bench/gc-sim/GcFrameSim.java` is a **synthetic** frame loop, not Minecraft. It reproduces the
parts of a client's memory behaviour that drive GC pauses:

- a ~600 MiB long-lived object graph (like loaded world data) that full marking must trace
- ~1 MiB of short-lived garbage per frame (scratch buffers, boxed values, strings)
- a 128 MiB cache whose 64 KiB entries are replaced 3 per frame (like rebuilt chunk meshes:
  promoted to the old generation, then discarded)
- ~1.5 ms of CPU work per frame, uncapped

Setup: 4 GiB heap (Xms = Xmx), 10 s warm-up, 20 s measured, **3 interleaved runs per
configuration**, OpenJDK 21.0.12, on a **4-vCPU cloud VM (Intel Xeon @ 2.1 GHz)**.
Reproduce with `bench/gc-sim/run-gc-sim.sh <launcher.jar> 3 20`.

Per-run results:

| Config | Run | Avg FPS | 1% low | 0.1% low | Worst frame | GC pauses (max) |
|---|---|---|---|---|---|---|
| G1 default | 1 | 478 | 25 | 5 | 263.4 ms | 26 (261 ms) |
| | 2 | 501 | 33 | 5 | 233.4 ms | 19 (231 ms) |
| | 3 | 508 | 39 | 6 | 267.7 ms | 15 (265 ms) |
| G1 + `MaxGCPauseMillis=50` | 1 | 478 | 27 | 5 | 420.6 ms | 29 (417 ms) |
| | 2 | 530 | 52 | 9 | 190.0 ms | 30 (183 ms) |
| | 3 | 529 | 58 | 12 | 143.1 ms | 29 (141 ms) |
| Generational ZGC | 1 | 346 | 82 | 36 | 50.1 ms | 6 (< 1 ms) |
| | 2 | 333 | 78 | 29 | 78.3 ms | 3 (< 1 ms) |
| | 3 | 344 | 114 | 56 | 29.7 ms | 3 (< 1 ms) |

(JMX reports pause durations in whole milliseconds, so ZGC's sub-millisecond pauses read as 0.)

`velox bench compare` verdicts (95% CI must exclude 0, and |change| ≥ 1%):

| Comparison | Avg FPS | 1% low | 0.1% low | p99 frame time |
|---|---|---|---|---|
| G1 pause target 50 ms vs G1 default | +3.3%, **not significant** (p = 0.46) | +42%, **not significant** (p = 0.29) | +59%, **not significant** (p = 0.28) | not significant |
| ZGC vs G1 default | **−31.2%, regression** (p = 0.001) | **+184%, improvement** (p = 0.024) | **+672%, improvement** (p = 0.048) | **+122%, regression** (p = 0.022) |

What this supports:

- **Lowering G1's pause target is not justified.** The change was within noise; one run was
  even worse. Velox therefore does not set it, even though it appears in many guides.
- **ZGC trades average FPS for consistency.** It removed the multi-hundred-millisecond hitches,
  the stutter players notice, but its concurrent GC threads took CPU from the "game" on
  4 cores. That is why Max FPS uses G1 and the stability-focused presets use ZGC.

What this does **not** show:

- How large either effect is in real Minecraft. Sodium + FerriteCore clients allocate less than
  this stress workload, so G1 pauses there are usually much shorter.
- How the tradeoff looks on your 8-core/16-thread Ryzen 7 8700F. With 4× the threads, ZGC's
  concurrent work should cost a smaller share of the CPU, but that is a hypothesis.
- Anything about Java 25, which the game uses. The experiment ran on Java 21.

To settle it for your machine, run the same scenario 3+ times each with `--gc g1` and `--gc zgc`
and compare (see [BENCHMARKING.md](BENCHMARKING.md)). Keep the GC that wins on the metric you
care about.
