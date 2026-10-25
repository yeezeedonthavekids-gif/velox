# Third-party components and licenses

Velox's own code (`core/`, `launcher/`, `mod/`, `bench/`) is MIT-licensed (see `LICENSE`).

## Nothing third-party is redistributed

The launcher downloads every third-party file from its official source at install time, on your
machine, and verifies each against the hash that source publishes:

| Component | Source | Verified by |
|---|---|---|
| Minecraft client, libraries, assets | Mojang (`piston-meta` / `piston-data` / `resources.download.minecraft.net`) | SHA-1 from Mojang's metadata |
| Java runtime | Mojang's runtime manifest (the official launcher's source) | SHA-1 per file |
| Fabric Loader + its libraries | `meta.fabricmc.net`, `maven.fabricmc.net` | SHA-1 (profile or `.sha1` sidecar; unverifiable files are refused) |
| Mods | Modrinth (`api.modrinth.com`, `cdn.modrinth.com`) | SHA-512 from Modrinth |

The resolver prints the license Modrinth lists for each mod at install time. As of October 2026:

| Mod | License (verify on the project page) | Notes |
|---|---|---|
| Fabric API | Apache-2.0 | required |
| Sodium | PolyForm Shield 1.0.0 | required. Shield allows use and distribution but not building a competing product from Sodium's code; Velox only installs it unmodified from Modrinth |
| Lithium | LGPL-3.0 | |
| FerriteCore | MIT | |
| ImmediatelyFast | LGPL-3.0 | |
| Entity Culling | author's own license, see its Modrinth page | |
| ModernFix | LGPL-3.0 | dropped automatically if it has no build for the chosen version |
| Dynamic FPS | MIT | |
| More Culling, C2ME | see project pages | off by default |

Minecraft itself is Mojang's software under the [Minecraft EULA](https://www.minecraft.net/eula).
You need a Microsoft account that owns Minecraft: Java Edition to play.

## Build-time dependencies

| | License |
|---|---|
| Gson 2.13.2 (shaded into the launcher jar; Minecraft provides it in game) | Apache-2.0 |
| JUnit 5 (tests only) | EPL-2.0 |
| Fabric Loom, Fabric Loader, Fabric API, Mixin (mod build) | Apache-2.0 / MIT |
| Gradle wrapper in `mod/` (copied from FabricMC/fabric-example-mod, CC0-1.0) | Apache-2.0 |

## Telemetry

None. The launcher contacts only the services listed above, and the mod contacts nothing.
Benchmark reports are local files.
