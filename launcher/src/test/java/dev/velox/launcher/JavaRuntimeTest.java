package dev.velox.launcher;

import dev.velox.launcher.java.JavaRuntimes;
import dev.velox.launcher.mojang.Platform;
import dev.velox.launcher.net.DownloadQueue;
import dev.velox.launcher.net.Http;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JavaRuntimeTest {
    @TempDir Path dir;
    static final Platform LINUX = new Platform("linux", "x86_64", "6", Set.of());

    private void serve(MockServer m, String files) {
        byte[] manifest = ("{\"files\":{" + files + "}}").getBytes(StandardCharsets.UTF_8);
        m.bytes("/rt/manifest.json", manifest);
        m.json("/java-runtime/all.json", "{\"linux\":{\"java-runtime-epsilon\":[{\"manifest\":{\"sha1\":\"" + MockServer.sha1(manifest)
                + "\",\"size\":" + manifest.length + ",\"url\":\"" + m.base() + "/rt/manifest.json\"}}]},\"windows-x64\":{}}");
    }

    @Test
    void installsMojangRuntimeWithExecutableBitAndLinks() throws Exception {
        try (MockServer m = new MockServer()) {
            byte[] java = "#!/bin/sh\necho 'openjdk version \"25.0.1\" 2025-10-21' >&2\n".getBytes(StandardCharsets.UTF_8);
            byte[] lib = "libjvm".getBytes(StandardCharsets.UTF_8);
            m.bytes("/rt/java", java);
            m.bytes("/rt/lib", lib);
            serve(m, "\"bin\":{\"type\":\"directory\"},"
                    + "\"bin/java\":{\"type\":\"file\",\"executable\":true,\"downloads\":{\"raw\":{\"sha1\":\"" + MockServer.sha1(java) + "\",\"size\":" + java.length + ",\"url\":\"" + m.base() + "/rt/java\"}}},"
                    + "\"lib/libjvm.so\":{\"type\":\"file\",\"downloads\":{\"raw\":{\"sha1\":\"" + MockServer.sha1(lib) + "\",\"size\":" + lib.length + ",\"url\":\"" + m.base() + "/rt/lib\"}}},"
                    + "\"legal/libjvm.so\":{\"type\":\"link\",\"target\":\"../lib/libjvm.so\"}");
            JavaRuntimes rt = new JavaRuntimes(new Http(1), Endpoints.under(m.base()).javaRuntimes(), dir, LINUX);
            Path exe = rt.ensureMojangRuntime("java-runtime-epsilon", DownloadQueue.Progress.NONE);
            assertEquals(dir.resolve("java-runtime-epsilon/bin/java"), exe);
            assertTrue(Files.isExecutable(exe));
            assertTrue(Files.isSymbolicLink(dir.resolve("java-runtime-epsilon/legal/libjvm.so")));
            assertEquals(25, JavaRuntimes.majorVersion(exe));
        }
    }

    @Test
    void refusesPathsOutsideTheRuntimeDirectory() throws Exception {
        try (MockServer m = new MockServer()) {
            serve(m, "\"../../evil\":{\"type\":\"directory\"}");
            JavaRuntimes rt = new JavaRuntimes(new Http(1), Endpoints.under(m.base()).javaRuntimes(), dir, LINUX);
            var e = assertThrows(java.io.IOException.class, () -> rt.ensureMojangRuntime("java-runtime-epsilon", DownloadQueue.Progress.NONE));
            assertTrue(e.getMessage().contains("escapes"));
        }
    }

    @Test
    void missingComponentIsAClearError() throws Exception {
        try (MockServer m = new MockServer()) {
            serve(m, "");
            JavaRuntimes rt = new JavaRuntimes(new Http(1), Endpoints.under(m.base()).javaRuntimes(), dir, LINUX);
            var e = assertThrows(java.io.IOException.class, () -> rt.ensureMojangRuntime("java-runtime-zeta", DownloadQueue.Progress.NONE));
            assertTrue(e.getMessage().contains("no java-runtime-zeta runtime for linux"));
        }
    }
}
