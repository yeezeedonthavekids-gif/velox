package dev.velox.launcher.mojang;

import com.google.gson.JsonArray;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merging inherited versions and resolving libraries to files on disk. */
public final class Versions {
    private Versions() {}

    /**
     * Merges a child profile (e.g. Fabric loader) into its parent (vanilla). The child's main
     * class wins; arguments are appended after the parent's; libraries are de-duplicated by
     * {@code group:artifact[:classifier]} with the child's version kept, so Fabric's newer ASM
     * replaces vanilla's instead of both landing on the classpath.
     */
    public static VersionInfo merge(VersionInfo parent, VersionInfo child) {
        VersionInfo m = new VersionInfo();
        m.id = child.id;
        m.inheritsFrom = null;
        m.type = child.type != null ? child.type : parent.type;
        m.mainClass = child.mainClass != null ? child.mainClass : parent.mainClass;
        m.downloads = parent.downloads;
        m.assetIndex = child.assetIndex != null ? child.assetIndex : parent.assetIndex;
        m.assets = child.assets != null ? child.assets : parent.assets;
        m.javaVersion = child.javaVersion != null ? child.javaVersion : parent.javaVersion;
        m.logging = child.logging != null ? child.logging : parent.logging;

        m.arguments = new VersionInfo.Arguments();
        m.arguments.game = concat(parent.arguments == null ? null : parent.arguments.game,
                child.arguments == null ? null : child.arguments.game);
        m.arguments.jvm = concat(parent.arguments == null ? null : parent.arguments.jvm,
                child.arguments == null ? null : child.arguments.jvm);

        Map<String, VersionInfo.Library> libs = new LinkedHashMap<>();
        for (VersionInfo.Library l : child.libraries) libs.put(libraryKey(l.name), l);
        for (VersionInfo.Library l : parent.libraries) libs.putIfAbsent(libraryKey(l.name), l);
        m.libraries = new ArrayList<>(libs.values());
        return m;
    }

    private static JsonArray concat(JsonArray a, JsonArray b) {
        JsonArray r = new JsonArray();
        if (a != null) r.addAll(a);
        if (b != null) r.addAll(b);
        return r;
    }

    /** {@code group:artifact[:classifier]}, i.e. the coordinate without its version. */
    static String libraryKey(String coord) {
        String[] p = coord.split(":");
        if (p.length < 3) return coord;
        return p.length >= 4 ? p[0] + ":" + p[1] + ":" + p[3] : p[0] + ":" + p[1];
    }

    /** Maven repository path for a coordinate, e.g. {@code org/ow2/asm/asm/9.8/asm-9.8.jar}. */
    public static String mavenPath(String coord) {
        String[] p = coord.split(":");
        if (p.length < 3) throw new IllegalArgumentException("Bad Maven coordinate: " + coord);
        String ext = "jar";
        String last = p[p.length - 1];
        int at = last.indexOf('@');
        if (at >= 0) {
            ext = last.substring(at + 1);
            p[p.length - 1] = last.substring(0, at);
        }
        String group = p[0].replace('.', '/');
        String artifact = p[1];
        String version = p[2];
        String classifier = p.length >= 4 ? "-" + p[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + "." + ext;
    }

    /** A library resolved to a concrete download. {@code sha1} may be null for Maven libraries without one. */
    public record ResolvedLibrary(String name, Path file, String url, String sha1, long size) {}

    public interface Sha1Lookup {
        /** Fetches {@code <url>.sha1} for Maven libraries whose profile omitted the hash. */
        String sha1For(String url) throws IOException;
    }

    public static List<ResolvedLibrary> resolveLibraries(VersionInfo v, Platform platform, Path libraryRoot,
                                                         Sha1Lookup sha1Lookup) throws IOException {
        List<ResolvedLibrary> out = new ArrayList<>();
        for (VersionInfo.Library l : v.libraries) {
            if (!platform.allows(l.rules)) continue;
            if (l.natives != null && !l.natives.isEmpty()) {
                // Pre-1.19 native extraction; no supported version uses it.
                throw new IOException("Library " + l.name + " uses legacy natives, which this launcher does not support");
            }
            if (l.downloads != null && l.downloads.artifact != null) {
                VersionInfo.Artifact a = l.downloads.artifact;
                String path = a.path != null ? a.path : mavenPath(l.name);
                out.add(new ResolvedLibrary(l.name, libraryRoot.resolve(path), a.url, a.sha1, a.size));
            } else if (l.url != null) {
                String path = mavenPath(l.name);
                String base = l.url.endsWith("/") ? l.url : l.url + "/";
                String url = base + path;
                String sha1 = l.sha1 != null ? l.sha1 : sha1Lookup.sha1For(url);
                out.add(new ResolvedLibrary(l.name, libraryRoot.resolve(path), url, sha1, l.size == null ? -1 : l.size));
            } else if (l.downloads == null) {
                throw new IOException("Library " + l.name + " has neither downloads nor a repository url");
            }
            // downloads present but no artifact (classifier-only entries): nothing on the classpath.
        }
        return out;
    }
}
