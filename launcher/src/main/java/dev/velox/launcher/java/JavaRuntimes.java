package dev.velox.launcher.java;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.velox.launcher.Log;
import dev.velox.launcher.mojang.Platform;
import dev.velox.launcher.net.DownloadQueue;
import dev.velox.launcher.net.Http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides a Java runtime for the game.
 *
 * <p>The runtime is the one Mojang publishes for the version ({@code javaVersion.component}),
 * fetched from Mojang's runtime manifest with every file SHA-1 verified, the same source the
 * official launcher uses. A user-chosen Java install is accepted instead if its major version
 * is high enough.
 */
public final class JavaRuntimes {
    private final Http http;
    private final String allJsonUrl;
    private final Path runtimesDir;
    private final Platform platform;

    public JavaRuntimes(Http http, String allJsonUrl, Path runtimesDir, Platform platform) {
        this.http = http;
        this.allJsonUrl = allJsonUrl;
        this.runtimesDir = runtimesDir;
        this.platform = platform;
    }

    public Path javaExecutable(Path home) {
        boolean win = platform.os().equals("windows");
        // javaw avoids a console window on Windows; we capture output through pipes anyway.
        Path bin = home.resolve("bin").resolve(win ? "javaw.exe" : "java");
        if (Files.exists(bin)) return bin;
        // macOS runtimes are bundles.
        Path mac = home.resolve("jre.bundle/Contents/Home/bin/java");
        return Files.exists(mac) ? mac : bin;
    }

    /** Installs (or verifies) Mojang's runtime {@code component}, returning its java executable. */
    public Path ensureMojangRuntime(String component, DownloadQueue.Progress progress) throws IOException {
        JsonObject all = http.getJson(allJsonUrl).getAsJsonObject();
        JsonObject forPlatform = all.getAsJsonObject(platform.runtimeKey());
        if (forPlatform == null || !forPlatform.has(component)) {
            throw new IOException("Mojang publishes no " + component + " runtime for " + platform.runtimeKey());
        }
        JsonArray entries = forPlatform.getAsJsonArray(component);
        if (entries.isEmpty()) throw new IOException("Mojang's " + component + " runtime list for " + platform.runtimeKey() + " is empty");
        JsonObject manifestRef = entries.get(0).getAsJsonObject().getAsJsonObject("manifest");
        String manifestUrl = manifestRef.get("url").getAsString();
        String manifestSha1 = manifestRef.get("sha1").getAsString();

        Path home = runtimesDir.resolve(component);
        Path manifestFile = runtimesDir.resolve(component + ".manifest.json");
        http.download(manifestUrl, manifestFile, Http.Expect.sha1(manifestSha1, -1));
        JsonObject files = com.google.gson.JsonParser.parseString(Files.readString(manifestFile))
                .getAsJsonObject().getAsJsonObject("files");

        DownloadQueue q = new DownloadQueue(http, 8);
        List<Path> executables = new ArrayList<>();
        List<Map.Entry<Path, String>> links = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : files.entrySet()) {
            Path target = safeResolve(home, e.getKey());
            JsonObject f = e.getValue().getAsJsonObject();
            switch (f.get("type").getAsString()) {
                case "directory" -> Files.createDirectories(target);
                case "file" -> {
                    JsonObject raw = f.getAsJsonObject("downloads").getAsJsonObject("raw");
                    q.add(raw.get("url").getAsString(), target,
                            Http.Expect.sha1(raw.get("sha1").getAsString(), raw.get("size").getAsLong()));
                    if (f.has("executable") && f.get("executable").getAsBoolean()) executables.add(target);
                }
                case "link" -> links.add(Map.entry(target, f.get("target").getAsString()));
                default -> Log.warn("Unknown runtime entry type for %s", e.getKey());
            }
        }
        q.run(progress, false);
        for (Path p : executables) p.toFile().setExecutable(true, false);
        if (!platform.os().equals("windows")) {
            for (Map.Entry<Path, String> l : links) {
                Path link = l.getKey();
                Path resolvedTarget = link.getParent().resolve(l.getValue()).normalize();
                if (!resolvedTarget.startsWith(home)) throw new IOException("Runtime link escapes runtime dir: " + link);
                Files.deleteIfExists(link);
                Files.createDirectories(link.getParent());
                Files.createSymbolicLink(link, Path.of(l.getValue()));
            }
        }
        return javaExecutable(home);
    }

    private static Path safeResolve(Path root, String rel) throws IOException {
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root)) throw new IOException("Runtime manifest path escapes runtime dir: " + rel);
        return p;
    }

    private static final Pattern VERSION = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?");

    /** Major version of a java executable ({@code java -version}), or -1 if it cannot run. */
    public static int majorVersion(Path java) {
        try {
            Process p = new ProcessBuilder(java.toString(), "-version").redirectErrorStream(true).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().reduce("", (a, b) -> a + b + "\n");
            }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            Matcher m = VERSION.matcher(out);
            if (!m.find()) return -1;
            int major = Integer.parseInt(m.group(1));
            return major == 1 && m.group(2) != null ? Integer.parseInt(m.group(2)) : major;
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
