# Presets

Presets are computed by `core/.../PresetEngine.java` from the preset name and a hardware tier.
The tier comes from logical cores, RAM and GPU, as detected by the launcher and passed to the game.
They are applied in game through vanilla's own option objects, so vanilla's change handling
runs, followed by the same renderer and texture reloads Sodium's settings screen performs.
Sodium's own settings are written to `config/sodium-options.json` before launch, touching only
the keys Velox owns.

Your machine (Ryzen 7 8700F, 8 cores/16 threads, 16 GB, RTX 5060) is tier **HIGH**.

| Setting | Max FPS | Competitive | Balanced | Why |
|---|---|---|---|---|
| Render distance (HIGH / MID / LOW tier) | 10 / 8 / 6 | 12 / 10 / 8 | 16 / 12 / 8 | Chunk count grows with the square of distance: the biggest lever |
| Simulation distance | 5 | 6 | 8 | Singleplayer tick cost; servers decide their own |
| Entity distance | 75% | **100%** | 100% | Competitive never hides players you could otherwise see |
| Max FPS / VSync | unlimited / off | unlimited / off | unlimited / off | VSync adds up to a frame of latency; cap with the driver or slider if you want a fixed rate |
| Mipmaps | 2 | 4 | 4 | Not 0: removing mipmaps causes shimmer and texture-cache misses for no real gain with Sodium |
| Biome blend | off | off | 5×5 | Blending is per-vertex work on every chunk rebuild |
| Particles | decreased | decreased | all | Never *minimal* in Competitive: particles carry hit and potion cues |
| Clouds | off | off | fast | Clouds can also hide players at height |
| Smooth lighting (AO) | off | on | on | Depth cue worth its meshing cost outside Max FPS |
| Entity shadows | off | off | on | Extra pass per entity |
| Fancy (cut-out) leaves | off | off | on | Opaque leaves avoid overdraw |
| Improved transparency | off | off | off | Full-screen passes; off everywhere |
| Vignette | off | off | on | |
| Chunk fade-in | instant | instant | vanilla | Terrain appears immediately |
| Inactivity FPS limit | minimized | minimized | minimized | "AFK" would cap FPS while you stand still and wreck static benchmarks |
| Sodium chunk builder threads | auto | auto | auto | Sodium's core-count heuristic is already right |
| Sodium chunk updates | deferred (always) | deferred | deferred | Uploads never block a frame, so frame times stay flat |
| Sodium entity culling, fog occlusion, face culling | on | on | on | Sodium defaults; re-enabled if you'd turned them off |

**Custom** starts from Balanced and applies your overrides, from the in-game screen
(Right Shift → Presets → Custom) or `custom` in `config/velox.json`.

These presets are reasoned defaults, not measured optima. Measure them on your machine with the
procedure in [BENCHMARKING.md](BENCHMARKING.md) and adjust.

## JVM

| | Your PC (16 GB) | 8 GB machine | Why |
|---|---|---|---|
| Heap | 4096 MiB (6144 at render distance > 20) | 3072 MiB | Enough live-data headroom; more doesn't raise FPS. Never more than half of RAM |
| GC | Max FPS: G1. Competitive/Balanced: generational ZGC | same rule, but G1 with fewer than 6 threads | Measured tradeoff: ZGC removes long hitches but costs throughput. See [JVM.md](JVM.md) |

## Features that are measurement or UI only

- Performance HUD (FPS, frame time, 1%/0.1% lows, memory, ping, graph). Strings are rebuilt
  4×/s, not per frame.
- Minimal HUD: hides chosen clutter elements (effects icons, held-item name, titles, ...).
  Health, hunger, armor, hotbar and chat are never offered.
- Custom crosshair: shape and color only, drawn where vanilla's would be.
- Key bindings (rebindable in Controls → Velox): Right Shift menu, F7 HUD, F8 benchmark,
  unbound preset cycle.

There are no cheats, automation (except the singleplayer-only benchmark flight), or anti-cheat
evasion.
