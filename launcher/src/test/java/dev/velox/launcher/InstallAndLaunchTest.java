package dev.velox.launcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.velox.core.config.Preset;
import dev.velox.core.config.VeloxConfig;
import dev.velox.core.hw.HardwareProfile;
import dev.velox.launcher.launch.GameProcess;
import dev.velox.launcher.launch.LaunchPlan;
import dev.velox.launcher.mods.ModInstaller;
import dev.velox.launcher.mods.ModResolver;
import dev.velox.launcher.mojang.Platform;
import dev.velox.launcher.net.DownloadQueue;
import dev.velox.launcher.net.Http;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: resolve the game version, install vanilla + Fabric + mods from mock servers,
 * apply a preset, build a launch command, and actually start a (fake) game process.
 */
class InstallAndLaunchTest {
    static final HardwareProfile PC = new HardwareProfile(16, 15_700, "NVIDIA GeForce RTX 5060", 8151, 165);
    static final Platform LINUX = new Platform("linux", "x86_64", "6.0", Set.of());

    @TempDir Path root;
    MockServer mock;
    byte[] clientJar, libA, asm, loader, winNative, asset1, asset2;
    byte[] fabricApi, sodium, ferrite, immediatelyFast, entityCulling, dynamicFps;

    @BeforeEach
    void setUp() throws Exception {
        mock = new MockServer();
        String b = mock.base();
        clientJar = jarWithFakeGame();
        libA = Fixtures.modJar(null, "lib-a");
        asm = Fixtures.modJar(null, "asm-9.8");
        loader = Fixtures.modJar("fabricloader", "loader");
        winNative = Fixtures.modJar(null, "windows-only");
        asset1 = "sound".getBytes(StandardCharsets.UTF_8);
        asset2 = "texture".getBytes(StandardCharsets.UTF_8);

        String assetIndex = "{\"objects\":{\"minecraft/sounds/a.ogg\":{\"hash\":\"" + MockServer.sha1(asset1) + "\",\"size\":" + asset1.length
                + "},\"icons/b.png\":{\"hash\":\"" + MockServer.sha1(asset2) + "\",\"size\":" + asset2.length + "}}}";
        byte[] aiBytes = assetIndex.getBytes(StandardCharsets.UTF_8);
        String vanilla = """
                {"id":"26.3","type":"release","mainClass":"net.minecraft.client.main.Main",
                 "arguments":{
                   "game":["--username","${auth_player_name}","--version","${version_name}","--gameDir","${game_directory}",
                           "--assetsDir","${assets_root}","--assetIndex","${assets_index_name}","--uuid","${auth_uuid}",
                           "--accessToken","${auth_access_token}","--userType","${user_type}",
                           {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"}],
                   "jvm":["-Djava.library.path=${natives_directory}",
                          {"rules":[{"action":"allow","os":{"name":"windows"}}],"value":"-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump"},
                          "-cp","${classpath}"]},
                 "libraries":[
                   {"name":"com.example:lib-a:1.0","downloads":{"artifact":{"path":"com/example/lib-a/1.0/lib-a-1.0.jar","sha1":"%s","size":%d,"url":"%s/maven/com/example/lib-a/1.0/lib-a-1.0.jar"}}},
                   {"name":"org.ow2.asm:asm:9.6","downloads":{"artifact":{"path":"org/ow2/asm/asm/9.6/asm-9.6.jar","sha1":"0000","size":1,"url":"%s/never"}}},
                   {"name":"org.lwjgl:lwjgl:3.3.6:natives-windows","rules":[{"action":"allow","os":{"name":"windows"}}],
                    "downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.6/lwjgl-3.3.6-natives-windows.jar","sha1":"%s","size":%d,"url":"%s/never"}}}],
                 "downloads":{"client":{"sha1":"%s","size":%d,"url":"%s/client.jar"}},
                 "assetIndex":{"id":"30","sha1":"%s","size":%d,"totalSize":12,"url":"%s/ai.json"},
                 "assets":"30","javaVersion":{"component":"java-runtime-epsilon","majorVersion":%d}}
                """.formatted(MockServer.sha1(libA), libA.length, b, b, MockServer.sha1(winNative), winNative.length, b,
                MockServer.sha1(clientJar), clientJar.length, b, MockServer.sha1(aiBytes), aiBytes.length, b,
                Runtime.version().feature());
        byte[] vanillaBytes = vanilla.getBytes(StandardCharsets.UTF_8);
        mock.json("/mc/game/version_manifest_v2.json", """
                {"latest":{"release":"26.4"},"versions":[
                  {"id":"26.4","type":"release","url":"%s/v/26.4.json","sha1":"x"},
                  {"id":"26.4-pre1","type":"snapshot","url":"%s/v/x.json","sha1":"x"},
                  {"id":"26.3","type":"release","url":"%s/v/26.3.json","sha1":"%s"},
                  {"id":"26.2","type":"release","url":"%s/v/26.2.json","sha1":"x"}]}
                """.formatted(b, b, b, MockServer.sha1(vanillaBytes), b));
        mock.bytes("/v/26.3.json", vanillaBytes);
        mock.bytes("/client.jar", clientJar);
        mock.bytes("/maven/com/example/lib-a/1.0/lib-a-1.0.jar", libA);
        mock.bytes("/ai.json", aiBytes);
        mock.bytes("/resources/" + MockServer.sha1(asset1).substring(0, 2) + "/" + MockServer.sha1(asset1), asset1);
        mock.bytes("/resources/" + MockServer.sha1(asset2).substring(0, 2) + "/" + MockServer.sha1(asset2), asset2);

        mock.json("/fabric/v2/versions/loader/26.3", """
                [{"loader":{"version":"0.21.0-beta.1","stable":false}},{"loader":{"version":"0.20.1","stable":true}}]""");
        mock.json("/fabric/v2/versions/loader/26.3/0.20.1/profile/json", """
                {"id":"fabric-loader-0.20.1-26.3","inheritsFrom":"26.3","type":"release",
                 "mainClass":"dev.velox.launcher.fake.FakeGame",
                 "arguments":{"game":[],"jvm":["-DFabricMcEmu= net.minecraft.client.main.Main "]},
                 "libraries":[
                   {"name":"org.ow2.asm:asm:9.8","url":"%s/maven/"},
                   {"name":"net.fabricmc:fabric-loader:0.20.1","url":"%s/maven/","sha1":"%s","size":%d}]}
                """.formatted(b, b, MockServer.sha1(loader), loader.length));
        mock.bytes("/maven/org/ow2/asm/asm/9.8/asm-9.8.jar", asm);
        mock.json("/maven/org/ow2/asm/asm/9.8/asm-9.8.jar.sha1", MockServer.sha1(asm) + "  asm-9.8.jar");
        mock.bytes("/maven/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar", loader);

        fabricApi = Fixtures.modJar("fabric-api", "fapi");
        sodium = Fixtures.modJar("sodium", "sodium");
        ferrite = Fixtures.modJar("ferritecore", "fc");
        immediatelyFast = Fixtures.modJar("immediatelyfast", "if");
        entityCulling = Fixtures.modJar("entityculling", "ec");
        dynamicFps = Fixtures.modJar("dynamic_fps", "dfps");
        project("P_FAPI", "fabric-api", "Fabric API", "Apache-2.0", fabricApi, "release", null, "26.3", "26.4");
        project("P_SOD", "sodium", "Sodium", "PolyForm-Shield-1.0.0", sodium, "release",
                "{\"project_id\":\"P_FAPI\",\"dependency_type\":\"required\"}", "26.2", "26.3");
        projectOnly("P_LITH", "lithium", "Lithium", "LGPL-3.0-only", "26.3");
        mock.json("/modrinth/v2/project/P_LITH/version", "[" + Fixtures.modrinthVersion("V_LITH", "P_LITH", "0.1-alpha", "alpha",
                mock.base() + "/cdn/lith.jar", "lithium.jar", Fixtures.modJar("lithium", "l"), null) + "]");
        project("P_FC", "ferrite-core", "FerriteCore", "MIT", ferrite, "release", null, "26.3");
        project("P_IF", "immediatelyfast", "ImmediatelyFast", "LGPL-3.0", immediatelyFast, "release",
                "{\"project_id\":\"P_EC\",\"dependency_type\":\"incompatible\"}", "26.3");
        project("P_EC", "entityculling", "Entity Culling", "custom", entityCulling, "release", null, "26.3");
        projectOnly("P_MF", "modernfix", "ModernFix", "LGPL-3.0-only", "26.2");
        mock.json("/modrinth/v2/project/P_MF/version", "[]");
        project("P_DFPS", "dynamic-fps", "Dynamic FPS", "MIT", dynamicFps, "beta", null, "26.3");
    }

    private void project(String id, String slug, String title, String license, byte[] jar, String type, String deps, String... gv) {
        projectOnly(id, slug, title, license, gv);
        String file = slug + "-1.0.jar";
        mock.bytes("/cdn/" + file, jar);
        mock.json("/modrinth/v2/project/" + id + "/version", "[" + Fixtures.modrinthVersion("V_" + id, id, "1.0+" + slug, type,
                mock.base() + "/cdn/" + file, file, jar, deps) + "]");
    }

    private void projectOnly(String id, String slug, String title, String license, String... gv) {
        String p = Fixtures.modrinthProject(id, slug, title, license, gv);
        mock.json("/modrinth/v2/project/" + slug, p);
        mock.json("/modrinth/v2/project/" + id, p);
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    private static byte[] jarWithFakeGame() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos);
             InputStream cls = InstallAndLaunchTest.class.getResourceAsStream("/dev/velox/launcher/fake/FakeGame.class")) {
            z.putNextEntry(new ZipEntry("dev/velox/launcher/fake/FakeGame.class"));
            z.write(cls.readAllBytes());
            z.closeEntry();
        }
        return bos.toByteArray();
    }

    private static String currentJava() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    @Test
    void installsResolvesAndLaunches() throws Exception {
        LauncherPaths paths = new LauncherPaths(root);
        VeloxLauncher l = new VeloxLauncher(new Http(1), Endpoints.under(mock.base()), paths, LINUX);
        Settings s = new Settings();
        s.preset = Preset.COMPETITIVE;
        s.javaPath = currentJava();
        s.heapMb = 1024; // small, so the probe and the fake game start anywhere

        Path gameDir = s.gameDir(paths);
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        Files.write(mods.resolve("my-sodium-fork.jar"), Fixtures.modJar("sodium", "user build"));
        Files.write(mods.resolve("minimap.jar"), Fixtures.modJar("minimap", "user mod"));
        Path veloxJar = root.resolve("velox-mod.jar");
        Files.write(veloxJar, Fixtures.modJar("velox", "velox"));

        VeloxLauncher.InstallState st = l.install(s, PC, veloxJar, DownloadQueue.Progress.NONE);

        // Version selection: 26.4 is newest, but Sodium only supports up to 26.3.
        assertEquals("26.3", st.gameVersion);
        assertEquals("0.20.1", st.loaderVersion, "stable loader preferred over newer beta");
        assertEquals(Runtime.version().feature(), st.javaMajor);

        List<String> planned = st.mods.stream().map(ModResolver.Planned::slug).toList();
        assertEquals(List.of("fabric-api", "sodium", "ferrite-core", "immediatelyfast", "dynamic-fps"), planned);
        List<String> skipped = st.skipped.stream().map(sk -> sk.slug() + ": " + sk.reason()).toList();
        assertTrue(skipped.stream().anyMatch(x -> x.startsWith("lithium: no Fabric build")), skipped.toString());
        assertTrue(skipped.stream().anyMatch(x -> x.startsWith("modernfix: no Fabric build")), skipped.toString());
        assertTrue(skipped.stream().anyMatch(x -> x.startsWith("entityculling: conflicts with ImmediatelyFast")), skipped.toString());
        assertEquals("beta", st.mods.get(4).versionType(), "Dynamic FPS only has a beta; betas are accepted");

        // User jars are untouched; the user's own Sodium wins over the managed one.
        assertTrue(Files.exists(mods.resolve("my-sodium-fork.jar")));
        assertTrue(Files.exists(mods.resolve("minimap.jar")));
        assertFalse(Files.exists(mods.resolve("sodium-1.0.jar")));
        assertTrue(Files.exists(mods.resolve("fabric-api-1.0.jar")));
        assertTrue(Files.exists(mods.resolve("velox.jar")));
        ModInstaller.Lock lock = ModInstaller.readLock(mods);
        assertTrue(lock.files.stream().noneMatch(f -> f.slug.equals("sodium")));

        // Game files.
        assertTrue(Files.exists(paths.libraries().resolve("org/ow2/asm/asm/9.8/asm-9.8.jar")), "Fabric's asm downloaded");
        assertFalse(Files.exists(paths.libraries().resolve("org/ow2/asm/asm/9.6/asm-9.6.jar")), "vanilla asm replaced by Fabric's");
        assertFalse(Files.exists(paths.libraries().resolve("org/lwjgl/lwjgl/3.3.6/lwjgl-3.3.6-natives-windows.jar")), "rules respected");
        assertTrue(Files.exists(paths.assets().resolve("objects").resolve(MockServer.sha1(asset1).substring(0, 2)).resolve(MockServer.sha1(asset1))));

        // Preset: written for the mod, and Sodium options patched (Sodium is present, even if user-provided).
        VeloxConfig cfg = VeloxConfig.load(VeloxConfig.path(gameDir));
        assertEquals(Preset.COMPETITIVE, cfg.preset);
        assertTrue(cfg.applyPresetOnStart);
        JsonObject sod = JsonParser.parseString(Files.readString(gameDir.resolve("config/sodium-options.json"))).getAsJsonObject();
        assertEquals("ALWAYS", sod.getAsJsonObject("performance").get("chunk_build_defer_mode").getAsString());

        // Second install is incremental: nothing re-downloaded except metadata.
        int before = mock.hits.get();
        l.install(s, PC, veloxJar, DownloadQueue.Progress.NONE);
        long jarFetches = mock.requests.subList(before, mock.requests.size()).stream()
                .filter(r -> r.path().endsWith(".jar") || r.path().startsWith("/resources/")).count();
        assertEquals(0, jarFetches, "valid files are not downloaded again");

        // Launch the fake game for real.
        VeloxLauncher.JavaChoice java = l.java(s, st, DownloadQueue.Progress.NONE);
        String token = "SECRET-TOKEN-123";
        LaunchPlan plan = l.plan(s, PC, st, java,
                new LaunchPlan.Identity("Steve", "11111111-2222-3333-4444-555555555555", "999", token, "client"), true);
        assertTrue(plan.redacted().stream().noneMatch(a -> a.contains(token)));
        assertTrue(plan.command().contains("-Xmx1024m"));
        assertTrue(plan.command().contains("-Dvelox.gpu=NVIDIA GeForce RTX 5060"));
        assertTrue(plan.command().contains("-Dvelox.profile=OPTIMIZED"));
        assertFalse(plan.command().contains("--demo"));
        assertTrue(plan.command().stream().noneMatch(a -> a.contains("HeapDumpPath")), "windows-only arg skipped on linux");

        GameProcess.Exit exit = GameProcess.run(plan, false);
        assertEquals(0, exit.code(), exit.tail());
        List<String> gameArgs = Files.readAllLines(gameDir.resolve("args.txt"));
        assertEquals("Steve", gameArgs.get(gameArgs.indexOf("--username") + 1));
        assertEquals("fabric-loader-0.20.1-26.3", gameArgs.get(gameArgs.indexOf("--version") + 1));
        assertEquals(token, gameArgs.get(gameArgs.indexOf("--accessToken") + 1));
        assertEquals("30", gameArgs.get(gameArgs.indexOf("--assetIndex") + 1));
        String launchLog = Files.readString(gameDir.resolve("logs/velox-launch.log"));
        assertTrue(launchLog.contains("fake game started"));
        assertFalse(launchLog.contains(token), "access token must never reach the log file");
    }

    @Test
    void crashIsReportedWithLogTail() throws Exception {
        LauncherPaths paths = new LauncherPaths(root);
        VeloxLauncher l = new VeloxLauncher(new Http(1), Endpoints.under(mock.base()), paths, LINUX);
        Settings s = new Settings();
        s.javaPath = currentJava();
        s.heapMb = 1024;
        s.extraGameArgs.add("--crash");
        VeloxLauncher.InstallState st = l.install(s, PC, null, DownloadQueue.Progress.NONE);
        LaunchPlan plan = l.plan(s, PC, st, l.java(s, st, DownloadQueue.Progress.NONE),
                new LaunchPlan.Identity("Steve", "u", "0", "tok", null), false);
        GameProcess.Exit exit = GameProcess.run(plan, false);
        assertEquals(3, exit.code());
        assertTrue(exit.tail().contains("fake game started"));
    }

    @Test
    void tamperedDownloadIsRejectedAndNotLeftOnDisk() throws Exception {
        mock.bytes("/client.jar", "tampered".getBytes(StandardCharsets.UTF_8));
        LauncherPaths paths = new LauncherPaths(root);
        VeloxLauncher l = new VeloxLauncher(new Http(1), Endpoints.under(mock.base()), paths, LINUX);
        Settings s = new Settings();
        var e = assertThrows(java.io.IOException.class, () -> l.install(s, PC, null, DownloadQueue.Progress.NONE));
        assertTrue(e.getMessage().contains("mismatch"), e.getMessage());
        assertFalse(Files.exists(paths.versionDir("26.3").resolve("26.3.jar")));
        assertFalse(Files.exists(paths.versionDir("26.3").resolve("26.3.jar.part")));
    }

    @Test
    void baselineProfileInstallsOnlyFabricApiOnSameVersion() throws Exception {
        LauncherPaths paths = new LauncherPaths(root);
        VeloxLauncher l = new VeloxLauncher(new Http(1), Endpoints.under(mock.base()), paths, LINUX);
        Settings s = new Settings();
        s.instance = "baseline";
        s.profile = dev.velox.launcher.mods.ModCatalog.Profile.BASELINE;
        VeloxLauncher.InstallState st = l.install(s, PC, null, DownloadQueue.Progress.NONE);
        assertEquals("26.3", st.gameVersion, "baseline pins the same version the optimized set needs");
        assertEquals(List.of("fabric-api"), st.mods.stream().map(ModResolver.Planned::slug).toList());
        assertFalse(Files.exists(s.gameDir(paths).resolve("config/sodium-options.json")));
    }

    @Test
    void javaTooOldForTheVersionIsRefused() throws Exception {
        LauncherPaths paths = new LauncherPaths(root);
        VeloxLauncher l = new VeloxLauncher(new Http(1), Endpoints.under(mock.base()), paths, LINUX);
        Settings s = new Settings();
        s.javaPath = currentJava();
        VeloxLauncher.InstallState st = l.install(s, PC, null, DownloadQueue.Progress.NONE);
        st.javaMajor = 99;
        var e = assertThrows(java.io.IOException.class, () -> l.java(s, st, DownloadQueue.Progress.NONE));
        assertTrue(e.getMessage().contains("needs Java 99"), e.getMessage());
    }

    @Test
    void requiredModCannotBeDisabled() {
        Settings s = new Settings();
        s.disableMods.add("sodium");
        VeloxLauncher l = new VeloxLauncher(new Http(1), Endpoints.under(mock.base()), new LauncherPaths(root), LINUX);
        assertThrows(IllegalArgumentException.class, () -> l.install(s, PC, null, DownloadQueue.Progress.NONE));
    }
}
