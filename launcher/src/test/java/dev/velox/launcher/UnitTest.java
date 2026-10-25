package dev.velox.launcher;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.velox.core.bench.BenchmarkReport;
import dev.velox.core.bench.ChunkLoadTracker;
import dev.velox.core.bench.FrameStats;
import dev.velox.core.bench.GcMonitor;
import dev.velox.launcher.java.FlagProbe;
import dev.velox.launcher.mods.ModInstaller;
import dev.velox.launcher.mods.ModResolver;
import dev.velox.launcher.mojang.ArgumentBuilder;
import dev.velox.launcher.mojang.Platform;
import dev.velox.launcher.mojang.VersionInfo;
import dev.velox.launcher.mojang.Versions;
import dev.velox.launcher.net.Http;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UnitTest {
    @TempDir Path dir;

    static final Platform WIN = new Platform("windows", "x86_64", "10.0", Set.of());
    static final Platform MAC_ARM = new Platform("osx", "arm64", "14.0", Set.of());

    private static List<VersionInfo.Rule> rules(String json) {
        return List.of(new Gson().fromJson(json, VersionInfo.Rule[].class));
    }

    @Test
    void ruleSemantics() {
        assertTrue(WIN.allows(null));
        assertTrue(WIN.allows(rules("[{\"action\":\"allow\",\"os\":{\"name\":\"windows\"}}]")));
        assertFalse(MAC_ARM.allows(rules("[{\"action\":\"allow\",\"os\":{\"name\":\"windows\"}}]")));
        // allow everything except osx: later rules win
        var exceptMac = rules("[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"osx\"}}]");
        assertTrue(WIN.allows(exceptMac));
        assertFalse(MAC_ARM.allows(exceptMac));
        assertTrue(MAC_ARM.allows(rules("[{\"action\":\"allow\",\"os\":{\"name\":\"osx\",\"arch\":\"arm64\"}}]")));
        assertFalse(WIN.allows(rules("[{\"action\":\"allow\",\"os\":{\"arch\":\"x86\"}}]")), "x86 means 32-bit only");
        assertFalse(WIN.allows(rules("[{\"action\":\"allow\",\"features\":{\"has_custom_resolution\":true}}]")));
        assertEquals("windows-x64", WIN.runtimeKey());
        assertEquals("mac-os-arm64", MAC_ARM.runtimeKey());
    }

    @Test
    void mavenPaths() {
        assertEquals("org/ow2/asm/asm/9.8/asm-9.8.jar", Versions.mavenPath("org.ow2.asm:asm:9.8"));
        assertEquals("org/lwjgl/lwjgl/3.3.6/lwjgl-3.3.6-natives-linux.jar", Versions.mavenPath("org.lwjgl:lwjgl:3.3.6:natives-linux"));
        assertEquals("a/b/1/b-1.zip", Versions.mavenPath("a:b:1@zip"));
        assertThrows(IllegalArgumentException.class, () -> Versions.mavenPath("bad"));
    }

    @Test
    void argumentsExpandAndUnknownPlaceholdersWarn() {
        JsonArray t = JsonParser.parseString("""
                ["--a","${x}","pre-${y}-post",{"rules":[{"action":"allow","os":{"name":"windows"}}],"value":["--w","${x}"]},
                 {"rules":[{"action":"allow","os":{"name":"osx"}}],"value":"--mac"}]""").getAsJsonArray();
        List<String> missing = new ArrayList<>();
        List<String> out = ArgumentBuilder.expand(t, WIN, Map.of("x", "1$\\2"), missing::add);
        assertEquals(List.of("--a", "1$\\2", "pre--post", "--w", "1$\\2"), out);
        assertEquals(List.of("y"), missing);
    }

    @Test
    void integrityFailuresLeaveNothingBehind() throws Exception {
        try (MockServer m = new MockServer()) {
            byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
            m.bytes("/f", data);
            Http http = new Http(1);
            Path target = dir.resolve("x/f.bin");
            assertThrows(Http.IntegrityException.class, () -> http.download(m.base() + "/f", target, Http.Expect.sha1("deadbeef", -1)));
            assertThrows(Http.IntegrityException.class, () -> http.download(m.base() + "/f", target, Http.Expect.sizeOnly(99)));
            assertFalse(Files.exists(target));
            assertFalse(Files.exists(target.resolveSibling("f.bin.part")));
            http.download(m.base() + "/f", target, Http.Expect.sha512(MockServer.sha512(data), data.length));
            assertArrayEquals(data, Files.readAllBytes(target));
            var e = assertThrows(Http.HttpStatusException.class, () -> http.download(m.base() + "/missing", target, Http.Expect.sizeOnly(-1)));
            assertEquals(404, e.status);
        }
    }

    @Test
    void retriesTransientServerErrors() throws Exception {
        try (MockServer m = new MockServer()) {
            int[] n = {0};
            m.route("/flaky", r -> ++n[0] < 3 ? MockServer.Reply.status(503, "busy") : MockServer.Reply.ok("{\"ok\":true}"));
            assertTrue(new Http(3).getJson(m.base() + "/flaky").getAsJsonObject().get("ok").getAsBoolean());
            assertEquals(3, n[0]);
        }
    }

    @Test
    void modInstallerOnlyRemovesItsOwnUnmodifiedFiles() throws Exception {
        try (MockServer m = new MockServer()) {
            byte[] a = Fixtures.modJar("a", "A"), b = Fixtures.modJar("b", "B");
            m.bytes("/a.jar", a);
            m.bytes("/b.jar", b);
            Path mods = dir.resolve("mods");
            ModInstaller inst = new ModInstaller(new Http(1));
            var pa = planned("a", m.base() + "/a.jar", "a.jar", a);
            var pb = planned("b", m.base() + "/b.jar", "b.jar", b);
            inst.install(new ModResolver.Plan("26.3", List.of(pa, pb), List.of()), List.of(), mods);
            assertTrue(Files.exists(mods.resolve("a.jar")) && Files.exists(mods.resolve("b.jar")));

            Files.writeString(mods.resolve("b.jar"), "user edited this");
            inst.install(new ModResolver.Plan("26.3", List.of(), List.of()), List.of(), mods);
            assertFalse(Files.exists(mods.resolve("a.jar")), "deselected managed mod removed");
            assertTrue(Files.exists(mods.resolve("b.jar")), "modified file kept");
        }
    }

    @Test
    void unsafeFileNamesRefused() {
        Path mods = dir.resolve("mods");
        var bad = planned("x", "http://127.0.0.1:1/x", "../../evil.jar", new byte[]{1});
        var e = assertThrows(java.io.IOException.class,
                () -> new ModInstaller(new Http(1)).install(new ModResolver.Plan("26.3", List.of(bad), List.of()), List.of(), mods));
        assertTrue(e.getMessage().contains("unsafe"));
    }

    private static ModResolver.Planned planned(String slug, String url, String file, byte[] data) {
        return new ModResolver.Planned(slug, slug, "P_" + slug, "V", "1", "release", url, file, MockServer.sha512(data),
                data.length, "MIT", "test");
    }

    @Test
    void flagProbeDropsWhatTheRuntimeRejects() throws Exception {
        Path java = Path.of(ProcessHandle.current().info().command().orElseThrow());
        var r = FlagProbe.filter(java, List.of("-Xms256m", "-Xmx256m"), List.of("-XX:+UseG1GC", "-XX:+ThisFlagDoesNotExist"));
        assertTrue(r.accepted().contains("-XX:+UseG1GC"));
        assertEquals(List.of("-XX:+ThisFlagDoesNotExist"), r.rejected());
    }

    @Test
    void hardwareParsers() {
        var smi = dev.velox.launcher.hw.HardwareDetectorAccess.smi("NVIDIA GeForce RTX 5060, 8151\n");
        assertEquals("NVIDIA GeForce RTX 5060", smi.name());
        assertEquals(8151, smi.vramMb());
        var wmi = dev.velox.launcher.hw.HardwareDetectorAccess.wmiPrimary(
                "[{\"Name\":\"AMD Radeon(TM) Graphics\",\"AdapterRAM\":536870912},{\"Name\":\"NVIDIA GeForce RTX 5060\",\"AdapterRAM\":4293918720}]");
        assertEquals("NVIDIA GeForce RTX 5060", wmi.name(), "discrete GPU preferred");
        var single = dev.velox.launcher.hw.HardwareDetectorAccess.wmiPrimary("{\"Name\":\"Intel(R) UHD Graphics 770\",\"AdapterRAM\":null}");
        assertEquals("Intel(R) UHD Graphics 770", single.name());
        var lspci = dev.velox.launcher.hw.HardwareDetectorAccess.lspciPrimary(
                "00:02.0 \"VGA compatible controller\" \"Intel Corporation\" \"Alder Lake-S GT1 [UHD Graphics 770]\" -r0c \"\" \"\"\n"
                        + "01:00.0 \"VGA compatible controller\" \"NVIDIA Corporation\" \"AD107 [GeForce RTX 4060]\" -ra1 \"\" \"\"\n");
        assertTrue(lspci.name().contains("NVIDIA"));
    }

    @Test
    void benchCompareCommandOnRecordedReports() throws Exception {
        Path base = dir.resolve("base"), cand = dir.resolve("cand");
        Random rnd = new Random(7);
        for (int run = 0; run < 4; run++) {
            writeRun(base, "run" + run, rnd, 6.0);   // ~166 FPS
            writeRun(cand, "run" + run, rnd, 4.0);   // ~250 FPS
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int code = new Cli(new PrintStream(bos, true, StandardCharsets.UTF_8), new LauncherPaths(dir.resolve("home")), Endpoints.OFFICIAL)
                .run(new String[]{"bench", "compare", "--baseline", base.toString(), "--candidate", cand.toString()});
        String out = bos.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out);
        assertTrue(out.lines().anyMatch(l -> l.startsWith("avg_fps") && l.endsWith("IMPROVEMENT")), out);

        bos.reset();
        new Cli(new PrintStream(bos, true, StandardCharsets.UTF_8), new LauncherPaths(dir.resolve("home")), Endpoints.OFFICIAL)
                .run(new String[]{"bench", "show", base.resolve("run0.json").toString()});
        assertTrue(bos.toString(StandardCharsets.UTF_8).contains("1% low"));
        assertTrue(Files.readAllLines(base.resolve("run0.frames.csv")).get(0).startsWith("frame,"));
    }

    private static void writeRun(Path dir, String name, Random rnd, double meanMs) throws Exception {
        long[] f = new long[3000];
        for (int i = 0; i < f.length; i++) f[i] = (long) ((meanMs + rnd.nextGaussian() * 0.5 + (rnd.nextInt(200) == 0 ? 20 : 0)) * 1e6);
        new BenchmarkReport(BenchmarkReport.SCHEMA, name, "static", "2026-10-25T00:00:00Z", 10, Map.of("gpu", "test"), List.of(),
                FrameStats.of(f), 0, GcMonitor.Summary.empty("G1"), new BenchmarkReport.Heap(4096, 1500, 2100), 30,
                new ChunkLoadTracker.Result(0, -1, -1, -1, 0)).write(dir, name, f);
    }

    @Test
    void cliRejectsBadOptionsWithUsefulErrors() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Cli cli = new Cli(new PrintStream(bos, true, StandardCharsets.UTF_8), new LauncherPaths(dir), Endpoints.OFFICIAL);
        assertEquals(2, cli.run(new String[]{"status", "--heap", "512"}));
        assertTrue(bos.toString(StandardCharsets.UTF_8).contains("1024"));
        assertEquals(2, cli.run(new String[]{"status", "--instance", "../evil"}));
        assertEquals(2, cli.run(new String[]{"frobnicate"}));
        assertEquals(1, cli.run(new String[]{"status"}), "not installed");
    }
}
