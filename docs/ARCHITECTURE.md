# Architecture

```
velox/
├── core/       Minecraft-independent Java 21 library, unit-tested
│   ├── bench/    FrameTimeRecorder, RollingFrameStats, FrameStats, GcMonitor, ResourceSampler,
│   │             ChunkLoadTracker, BenchmarkReport (JSON+CSV), RunComparison (Welch t-test)
│   ├── config/   Preset, PresetEngine, GameSettings, VeloxConfig, SodiumOptionsPatcher
│   ├── hw/       HardwareProfile (vendor, iGPU detection, tier)
│   └── jvm/      JvmArgs (heap sizing, GC choice)
├── launcher/   Java 21 launcher: CLI + Swing GUI, one fat jar (Gson shaded)
│   ├── mojang/   version manifest, rules, Fabric profile merging, libraries, assets, arguments
│   ├── fabric/   Fabric meta client
│   ├── mods/     curated catalog, Modrinth client, resolver, lockfile-based installer
│   ├── java/     Mojang runtime installer, JVM flag probe
│   ├── auth/     Microsoft device-code chain, OS keystore secret storage
│   ├── launch/   command builder (token redaction), process runner with crash reporting
│   └── ui/       Swing window
├── mod/        Fabric mod for Minecraft 26.3 (Java 25, Mojang names). Separate Gradle build
│               because Loom needs Mojang and Fabric servers. Compiles core/ sources into itself.
├── bench/      compare scripts, GPU logger, synthetic GC experiment
└── docs/
```

## Why this foundation

- **Minecraft 26.3 + Fabric.** 26.3 is the current release (September 2026), and 26.x is the first
  unobfuscated line, so mods use Mojang's names directly. Fabric has the fastest-moving
  optimization ecosystem, and Sodium 0.9.x supports 26.x, including the experimental Vulkan
  backend introduced in 26.2.
- **Curate, don't rewrite.** Sodium already replaces the terrain renderer (meshing, culling,
  batching, upload scheduling). Lithium, FerriteCore, ImmediatelyFast and Entity Culling cover
  game logic, memory, immediate-mode batching and entity occlusion. Rewriting any of them
  without evidence of being faster would be wasted effort with high regression risk, so Velox
  installs them and spends its own code on what they lack: measurement, configuration,
  presets and launching.
- **The mod is thin on purpose.** It has one mixin (frame start) and otherwise uses Fabric API
  hooks (HUD element registry, key mappings, tick events) and vanilla `OptionInstance`s.
  The less it touches, the less breaks when Minecraft updates.
- **"auto" version.** A new Minecraft release often ships before Sodium supports it, so the
  launcher picks the newest release that every *required* mod supports on Fabric. The
  baseline profile pins the same version so A/B comparisons stay valid.

## Data flow at launch

1. The launcher detects hardware, resolves the version and mod set, and installs or verifies
   everything (hash-checked, incremental).
2. It writes `config/velox.json` (preset, `applyPresetOnStart`) and patches
   `config/sodium-options.json`.
3. It builds JVM arguments (heap, GC, probed against the actual runtime), adds `-Dvelox.*`
   hardware properties, signs in, and launches.
4. In game, the mod applies the preset once a world is loaded, then records frames for the HUD
   and benchmarks.

## Performance rules for Velox's own code

- Per-frame paths (`FrameClock`, `BenchmarkController.onFrame`) are allocation-free and touch
  only primitive arrays.
- The HUD formats strings 4×/s, not per frame. GC notifications arrive on a JMX thread.
  Benchmark files are written on a background thread.
- The launcher starts no background services, and its download pool exists only during a
  download.

## Mod API verification status

The mod could not be compiled against the real 26.3 jar in the build environment. Each call
falls into one of these groups:

**Seen in Fabric API 0.165.0+26.3 or Sodium (Oct 2026) sources:**
`HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker)`, `HudElementRegistry`,
`VanillaHudElements`, `KeyMappingHelper.registerKeyMapping`, `KeyMapping(String, Type, int, Category)`,
`KeyMapping.Category.register`, `SDLScancode`, `consumeClick`, `ClientTickEvents.END_CLIENT_TICK`,
`Minecraft.runTick` (mixin target), `mc.gui.setScreen`, `mc.gui.hud.isHidden()`,
`mc.gui.hud.getChat().addClientSystemMessage`, `GuiGraphicsExtractor.fill/text/centeredText/guiWidth/guiHeight`,
`Screen.extractRenderState/extractBackground`, `Button.builder(...).pos().size().build()`,
`Button.setMessage`, `Tooltip.create`, the `Options` accessors used by presets, `OptionInstance.get/set`,
`Options.save`, `mc.levelExtractor.allChanged()`, `mc.updateMaxMipLevel`, `mc.delayTextureReload`,
`mc.getSingleplayerServer()`, `mc.getConnection()`, `player.setYRot/setXRot/getAbilities`,
`Identifier.fromNamespaceAndPath/withDefaultNamespace`, `ParticleStatus`, `CloudStatus`.

**Long-standing vanilla API, not seen in those sources (highest risk on first compile):**
`ClientPacketListener.getPlayerInfo(UUID)` + `PlayerInfo.getLatency()` (ping),
`ClientLevel.getChunkSource().getLoadedChunksCount()` (chunk timing),
`Options.getCameraType().isFirstPerson()` (custom crosshair), `Options.keyUp/keySprint.setDown`
and `Abilities.mayfly/flying` (benchmark flight), `Screen.clearWidgets()`, and the value types of
`chunkSectionFadeInTime()` / `entityDistanceScaling()` (assumed `Double`).

Each is used in exactly one place, so a rename is a one-line fix.
