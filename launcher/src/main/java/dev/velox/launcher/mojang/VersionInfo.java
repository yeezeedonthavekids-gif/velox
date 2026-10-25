package dev.velox.launcher.mojang;

import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A version JSON, as served by Mojang ({@code versions/<id>/<id>.json}) or as a Fabric
 * loader profile (which sets {@link #inheritsFrom}). Only the fields the launcher needs.
 */
public final class VersionInfo {
    public String id;
    public String inheritsFrom;
    public String type;
    public String mainClass;
    public Arguments arguments;
    public List<Library> libraries = new ArrayList<>();
    public Map<String, Download> downloads;
    public AssetIndexRef assetIndex;
    public String assets;
    public JavaVersion javaVersion;
    public Logging logging;

    public static final class Arguments {
        /** Strings, or objects {@code {"rules": [...], "value": string | [string]}}. */
        public JsonArray game = new JsonArray();
        public JsonArray jvm = new JsonArray();
    }

    public static final class Library {
        /** Maven coordinate {@code group:artifact:version[:classifier]}. */
        public String name;
        public LibraryDownloads downloads;
        /** Maven repository base (Fabric profiles) when {@link #downloads} is absent. */
        public String url;
        public String sha1;
        public Long size;
        public List<Rule> rules;
        /** Legacy (pre-1.19) native classifiers; not used by 26.x versions. */
        public Map<String, String> natives;
    }

    public static final class LibraryDownloads {
        public Artifact artifact;
        public Map<String, Artifact> classifiers;
    }

    public static final class Artifact {
        public String path;
        public String sha1;
        public long size;
        public String url;
    }

    public static final class Download {
        public String sha1;
        public long size;
        public String url;
    }

    public static final class AssetIndexRef {
        public String id;
        public String sha1;
        public long size;
        public long totalSize;
        public String url;
    }

    public static final class JavaVersion {
        public String component;
        public int majorVersion;
    }

    public static final class Logging {
        public LoggingEntry client;
    }

    public static final class LoggingEntry {
        public String argument;
        public LoggingFile file;
        public String type;
    }

    public static final class LoggingFile {
        public String id;
        public String sha1;
        public long size;
        public String url;
    }

    public static final class Rule {
        public String action;
        public Os os;
        public Map<String, Boolean> features;
    }

    public static final class Os {
        public String name;
        public String arch;
        public String version;
    }
}
