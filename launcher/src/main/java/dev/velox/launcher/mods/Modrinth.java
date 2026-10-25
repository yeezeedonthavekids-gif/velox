package dev.velox.launcher.mods;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import dev.velox.launcher.net.Http;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only client for the public Modrinth API v2. No account or token is involved. */
public final class Modrinth {
    private static final Gson GSON = new Gson();
    private final Http http;
    private final String base;
    private final Map<String, Project> projectCache = new HashMap<>();

    public Modrinth(Http http, String base) {
        this.http = http;
        this.base = base;
    }

    public static final class Project {
        public String id;
        public String slug;
        public String title;
        public List<String> game_versions = new ArrayList<>();
        public List<String> loaders = new ArrayList<>();
        public License license;
        public String client_side;
    }

    public static final class License {
        public String id;
        public String name;
    }

    public static final class Version {
        public String id;
        public String project_id;
        public String name;
        public String version_number;
        public List<String> game_versions = new ArrayList<>();
        public List<String> loaders = new ArrayList<>();
        public String version_type;
        public String date_published;
        public List<File> files = new ArrayList<>();
        public List<Dependency> dependencies = new ArrayList<>();

        public File primaryFile() {
            for (File f : files) if (f.primary) return f;
            return files.isEmpty() ? null : files.get(0);
        }
    }

    public static final class File {
        public Map<String, String> hashes = new HashMap<>();
        public String url;
        public String filename;
        public boolean primary;
        public long size;
    }

    public static final class Dependency {
        public String version_id;
        public String project_id;
        public String file_name;
        /** required, optional, incompatible or embedded */
        public String dependency_type;
    }

    public Project project(String idOrSlug) throws IOException {
        Project cached = projectCache.get(idOrSlug);
        if (cached != null) return cached;
        Project p = http.getJson(base + "/v2/project/" + enc(idOrSlug), Project.class);
        if (p == null || p.id == null) throw new IOException("Modrinth returned no project for " + idOrSlug);
        projectCache.put(p.id, p);
        projectCache.put(p.slug, p);
        return p;
    }

    /** Fabric versions of a project for one game version, newest first (Modrinth's order). */
    public List<Version> fabricVersions(String idOrSlug, String gameVersion) throws IOException {
        JsonArray loaders = new JsonArray();
        loaders.add("fabric");
        JsonArray games = new JsonArray();
        games.add(gameVersion);
        String url = base + "/v2/project/" + enc(idOrSlug) + "/version?loaders=" + enc(loaders.toString())
                + "&game_versions=" + enc(games.toString());
        Version[] versions = http.getJson(url, Version[].class);
        return versions == null ? List.of() : Arrays.asList(versions);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
