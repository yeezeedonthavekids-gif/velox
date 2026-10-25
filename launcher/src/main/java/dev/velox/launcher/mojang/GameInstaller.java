package dev.velox.launcher.mojang;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.velox.launcher.Endpoints;
import dev.velox.launcher.LauncherPaths;
import dev.velox.launcher.Log;
import dev.velox.launcher.fabric.FabricMeta;
import dev.velox.launcher.net.DownloadQueue;
import dev.velox.launcher.net.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Installs vanilla Minecraft plus the Fabric loader: version metadata, client jar, libraries,
 * assets and log config. Every file is hash-verified, and re-running skips what is already
 * correct, so "install" is also "repair".
 */
public final class GameInstaller {
    private static final Gson GSON = new Gson();

    private final Http http;
    private final Endpoints endpoints;
    private final LauncherPaths paths;
    private final Platform platform;

    public GameInstaller(Http http, Endpoints endpoints, LauncherPaths paths, Platform platform) {
        this.http = http;
        this.endpoints = endpoints;
        this.paths = paths;
        this.platform = platform;
    }

    public record Installed(String gameVersion, String loaderVersion, VersionInfo version, Path clientJar,
                            List<Path> classpath, Path assetsRoot, Path loggingConfig) {
        /** The Fabric profile id, e.g. {@code fabric-loader-0.20.1-26.3}. */
        public String profileId() { return version.id; }
    }

    public record ManifestEntry(String id, String type, String url, String sha1) {}

    public List<ManifestEntry> releases() throws IOException {
        JsonObject manifest = http.getJson(endpoints.versionManifest()).getAsJsonObject();
        List<ManifestEntry> out = new ArrayList<>();
        for (JsonElement e : manifest.getAsJsonArray("versions")) {
            ManifestEntry m = GSON.fromJson(e, ManifestEntry.class);
            if ("release".equals(m.type())) out.add(m);
        }
        return out; // newest first, as Mojang orders it
    }

    public ManifestEntry findVersion(String id) throws IOException {
        JsonObject manifest = http.getJson(endpoints.versionManifest()).getAsJsonObject();
        if (id == null || id.equals("latest")) id = manifest.getAsJsonObject("latest").get("release").getAsString();
        for (JsonElement e : manifest.getAsJsonArray("versions")) {
            ManifestEntry m = GSON.fromJson(e, ManifestEntry.class);
            if (m.id().equals(id)) return m;
        }
        throw new IOException("Minecraft version " + id + " is not in Mojang's version manifest");
    }

    /**
     * @param loaderVersion Fabric loader version, or null for the newest stable one
     */
    public Installed install(String gameVersion, String loaderVersion, boolean verifyExisting,
                             DownloadQueue.Progress progress) throws IOException {
        ManifestEntry entry = findVersion(gameVersion);
        gameVersion = entry.id();

        Path vanillaJson = paths.versionDir(gameVersion).resolve(gameVersion + ".json");
        if (!Http.matches(vanillaJson, Http.Expect.sha1(entry.sha1(), -1))) {
            http.download(entry.url(), vanillaJson, Http.Expect.sha1(entry.sha1(), -1));
        }
        VersionInfo vanilla = read(vanillaJson);

        FabricMeta fabric = new FabricMeta(http, endpoints.fabricMeta());
        if (loaderVersion == null) loaderVersion = fabric.latestLoader(gameVersion);
        String profile = fabric.profileJson(gameVersion, loaderVersion);
        VersionInfo fabricProfile = GSON.fromJson(profile, VersionInfo.class);
        if (fabricProfile == null || fabricProfile.id == null || !gameVersion.equals(fabricProfile.inheritsFrom)) {
            throw new IOException("Fabric profile for " + gameVersion + "/" + loaderVersion + " is malformed");
        }
        Path fabricJson = paths.versionDir(fabricProfile.id).resolve(fabricProfile.id + ".json");
        Files.createDirectories(fabricJson.getParent());
        Files.writeString(fabricJson, profile, StandardCharsets.UTF_8);

        VersionInfo merged = Versions.merge(vanilla, fabricProfile);
        Log.info("Installing Minecraft %s with Fabric Loader %s", gameVersion, loaderVersion);

        DownloadQueue q = new DownloadQueue(http, 8);

        VersionInfo.Download client = vanilla.downloads == null ? null : vanilla.downloads.get("client");
        if (client == null) throw new IOException("Version " + gameVersion + " has no client download");
        Path clientJar = paths.versionDir(gameVersion).resolve(gameVersion + ".jar");
        q.add(client.url, clientJar, Http.Expect.sha1(client.sha1, client.size));

        Map<String, String> sha1Cache = new ConcurrentHashMap<>();
        List<Versions.ResolvedLibrary> libs = Versions.resolveLibraries(merged, platform, paths.libraries(),
                url -> sha1Cache.computeIfAbsent(url, this::fetchSha1));
        List<Path> classpath = new ArrayList<>();
        for (Versions.ResolvedLibrary l : libs) {
            q.add(l.url(), l.file(), Http.Expect.sha1(l.sha1(), l.size()));
            classpath.add(l.file());
        }
        classpath.add(clientJar);

        Path loggingConfig = null;
        if (merged.logging != null && merged.logging.client != null && merged.logging.client.file != null) {
            VersionInfo.LoggingFile lf = merged.logging.client.file;
            loggingConfig = paths.assets().resolve("log_configs").resolve(lf.id);
            q.add(lf.url, loggingConfig, Http.Expect.sha1(lf.sha1, lf.size));
        }

        VersionInfo.AssetIndexRef ai = merged.assetIndex;
        if (ai == null) throw new IOException("Version " + gameVersion + " has no asset index");
        Path indexFile = paths.assets().resolve("indexes").resolve(ai.id + ".json");
        if (!Http.matches(indexFile, Http.Expect.sha1(ai.sha1, ai.size))) {
            http.download(ai.url, indexFile, Http.Expect.sha1(ai.sha1, ai.size));
        }
        JsonObject objects = GSON.fromJson(Files.readString(indexFile), JsonObject.class).getAsJsonObject("objects");
        for (Map.Entry<String, JsonElement> e : objects.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            String hash = o.get("hash").getAsString();
            long size = o.get("size").getAsLong();
            String rel = hash.substring(0, 2) + "/" + hash;
            q.add(endpoints.resources() + "/" + rel, paths.assets().resolve("objects").resolve(rel), Http.Expect.sha1(hash, size));
        }

        int total = q.size();
        int fetched = q.run(progress, verifyExisting);
        Log.info("Game files ready: %d checked, %d downloaded", total, fetched);
        return new Installed(gameVersion, loaderVersion, merged, clientJar, classpath, paths.assets(), loggingConfig);
    }

    /**
     * Rebuilds an installation from files already on disk, with no network access. Used at launch
     * so playing never depends on Mojang or Fabric being reachable once installed.
     */
    public Installed loadInstalled(String gameVersion, String loaderVersion, String profileId) throws IOException {
        VersionInfo vanilla = read(paths.versionDir(gameVersion).resolve(gameVersion + ".json"));
        VersionInfo fabric = read(paths.versionDir(profileId).resolve(profileId + ".json"));
        VersionInfo merged = Versions.merge(vanilla, fabric);
        List<Versions.ResolvedLibrary> libs = Versions.resolveLibraries(merged, platform, paths.libraries(), url -> null);
        List<Path> classpath = new ArrayList<>();
        for (Versions.ResolvedLibrary l : libs) {
            if (!Files.isRegularFile(l.file())) throw new IOException("Missing library " + l.name() + "; run install again");
            classpath.add(l.file());
        }
        Path clientJar = paths.versionDir(gameVersion).resolve(gameVersion + ".jar");
        if (!Files.isRegularFile(clientJar)) throw new IOException("Missing client jar for " + gameVersion + "; run install again");
        classpath.add(clientJar);
        Path loggingConfig = null;
        if (merged.logging != null && merged.logging.client != null && merged.logging.client.file != null) {
            Path lc = paths.assets().resolve("log_configs").resolve(merged.logging.client.file.id);
            if (Files.isRegularFile(lc)) loggingConfig = lc;
        }
        return new Installed(gameVersion, loaderVersion, merged, clientJar, classpath, paths.assets(), loggingConfig);
    }

    private String fetchSha1(String url) {
        try {
            String s = http.getString(url + ".sha1", Map.of()).trim();
            // Some repositories append the file name after the hash.
            int sp = s.indexOf(' ');
            return sp > 0 ? s.substring(0, sp) : s;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("No checksum published for " + url + "; refusing to install an unverifiable library", e);
        }
    }

    static VersionInfo read(Path json) throws IOException {
        VersionInfo v = GSON.fromJson(Files.readString(json, StandardCharsets.UTF_8), VersionInfo.class);
        if (v == null || v.id == null) throw new IOException("Malformed version JSON: " + json);
        return v;
    }
}
