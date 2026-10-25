# Velox

A performance-focused Minecraft: Java Edition client for **Minecraft 26.3** on Fabric: a launcher,
a curated set of proven optimization mods, presets, a low-overhead performance HUD, and a
built-in benchmark recorder with proper statistics.

Velox doesn't replace Minecraft's renderer itself. Sodium already does that better than a
rewrite would. Velox:

1. **Installs the strongest compatible optimizations**: Sodium (rendering), Lithium (game logic),
   FerriteCore (memory), ImmediatelyFast (immediate-mode batching), Entity Culling, ModernFix,
   Dynamic FPS. Each is fetched from Modrinth at install time and SHA-512 verified, and
   dependencies and incompatibilities are resolved automatically.
2. **Configures them sensibly** with hardware-aware presets: Max FPS, Competitive, Balanced,
   Custom. See [docs/PRESETS.md](docs/PRESETS.md).
3. **Tunes the JVM from evidence**: right-sized heap, generational ZGC or G1, and flags probed
   against the actual runtime. See [docs/JVM.md](docs/JVM.md).
4. **Measures**: FPS, 1%/0.1% lows, frame-time distribution, GC pauses, memory, CPU and chunk
   loading, with a comparison tool that separates real changes from noise. See
   [docs/BENCHMARKING.md](docs/BENCHMARKING.md).

## Status

| Part | State |
|---|---|
| `core` (presets, statistics, reports, JVM advice) | Built and tested: 36 unit tests |
| `launcher` (install, mods, Java runtime, Microsoft sign-in, launch, CLI, GUI) | Built and tested: 26 tests, including a full install → launch run against mock Mojang/Fabric/Modrinth/Microsoft servers that starts a real JVM process |
| `mod` (HUD, crosshair, presets, benchmarks, settings screen) | Compiles against the real Minecraft 26.3 + Fabric API 0.165.0 in GitHub Actions. **Not yet run in game**; the first in-game test is yours. |
| In-game FPS results | **None yet.** They have to come from your hardware; see the benchmarking guide. |

## Install on Windows

1. Open the repo's **Releases** page and download `Velox-<version>.exe`.
2. Run it. It installs for your user only (no admin prompt) and adds Velox to the Start Menu and
   desktop. Java is bundled; you don't need to install it.
3. Windows SmartScreen may say *"Windows protected your PC"*, because the installer isn't
   code-signed yet. Click **More info → Run anyway**. (Signing needs a paid certificate, which
   commercial launchers like Lunar Client buy.)

Prefer no installer? `Velox-<version>-portable.zip` contains the same app as a folder; unzip it
and run `Velox.exe`. Uninstall from *Settings → Apps*. Your data in `%APPDATA%\.velox` is kept
unless you delete it.

## Build

Requirements: JDK 21+ for the launcher and core, JDK 25 for the mod.

```sh
./gradlew test                         # core + launcher, 62 tests
cd mod && ./gradlew build && cd ..     # Fabric mod → mod/build/libs/velox-0.1.0.jar
./gradlew :launcher:fatJar             # launcher → launcher/build/libs/velox-launcher-0.1.0.jar
                                       # (bundles the mod if it has been built)
# Windows only (needs WiX 3): installer + portable zip in dist/
.\packaging\build-windows.ps1 -Jar launcher\build\libs\velox-launcher-0.1.0.jar
```

Pushing a tag like `v0.1.0` makes CI build the Windows installer on a Windows runner and
publish it as a GitHub Release.

Or download from GitHub Actions: each push builds all three and uploads `velox-launcher` (mod bundled inside) under the run's Artifacts.

## Run

```sh
java -jar velox-launcher-0.1.0.jar                 # GUI
java -jar velox-launcher-0.1.0.jar hardware        # what Velox detects and recommends
java -jar velox-launcher-0.1.0.jar install --preset competitive
java -jar velox-launcher-0.1.0.jar login --client-id <your Azure app id> --save
java -jar velox-launcher-0.1.0.jar launch
java -jar velox-launcher-0.1.0.jar launch --dry-run # print the command (token redacted)
java -jar velox-launcher-0.1.0.jar help            # all options
```

Microsoft sign-in needs a client ID you register yourself. Velox ships none on purpose; see
[docs/AUTH.md](docs/AUTH.md).

Files live in `%APPDATA%\.velox` (Windows), `~/Library/Application Support/velox` (macOS) or
`~/.local/share/velox` (Linux); override with `VELOX_HOME`. Instances are under `instances/`. Jars
you add to an instance's `mods/` folder are never touched, and if one provides the same mod as
a managed one, yours wins.

In game: **Right Shift** opens Velox settings, **F7** toggles the HUD, **F8** starts or stops a
benchmark. All are rebindable under Controls.

## For your PC (Ryzen 7 8700F, RTX 5060 8 GB, 16 GB DDR5, Windows 11)

- Detected tier: HIGH. Competitive gives render distance 12; Balanced gives 16.
- Heap 4 GiB (6 GiB above render distance 20), with Xms = Xmx. Don't give Minecraft more than
  half your RAM; the RTX driver, Windows and your browser need the rest.
- GC depends on the preset. Max FPS uses G1 for throughput; Competitive and Balanced use
  generational ZGC for frame-time stability. In the synthetic test ZGC removed ~250 ms hitches
  but cost 31% average throughput on 4 cores. Your 16 threads should narrow that gap, but
  measure it (`--gc zgc` vs `--gc g1`).
- NVIDIA Control Panel, optional: Low Latency Mode *On* and Power management *Prefer maximum
  performance* for `javaw.exe` are safe, reversible, per-app settings. Velox changes nothing
  outside its own folder: no registry edits, priority hacks or security-software changes.
- 8 GB VRAM is ample at these render distances. If you try Minecraft's Vulkan backend (26.2+),
  benchmark it against OpenGL the same way; it is still marked experimental.

## Known limitations

- The mod compiles against Minecraft 26.3 in CI but has not been run in game yet. Compiling
  proves the API calls exist, not that they behave as expected. The calls most worth watching
  on first run are listed in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
- No in-game benchmark numbers are included. The only measurements are the synthetic GC experiment.
- Microsoft sign-in requires your own Mojang-approved Azure client ID.
- Sodium settings changed by switching presets in game take effect on the next launch (they live
  in Sodium's config file, which the launcher writes). Vanilla settings apply immediately.
- Pre-1.19 versions (legacy native extraction) are not supported; Velox targets 26.x.
- Linux/macOS launching follows the same code path but was only tested on Linux, with the fake
  game process.

## Licensing

Velox is MIT. Third-party components are downloaded from their official sources at install time
and never redistributed; see [THIRD_PARTY.md](THIRD_PARTY.md). No telemetry.
