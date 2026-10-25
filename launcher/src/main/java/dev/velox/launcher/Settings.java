package dev.velox.launcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.velox.core.config.Preset;
import dev.velox.launcher.mods.ModCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Launcher settings ({@code launcher.json}). No secrets are stored here. */
public final class Settings {
    public String instance = "default";
    /** A Minecraft release id, or "auto" for the newest release all required mods support. */
    public String gameVersion = "auto";
    public String loaderVersion;
    public ModCatalog.Profile profile = ModCatalog.Profile.OPTIMIZED;
    public Preset preset = Preset.BALANCED;
    /** 0 = recommended for this machine. */
    public int heapMb;
    /** "auto", "zgc" or "g1". */
    public String gc = "auto";
    public boolean preTouchHeap;
    /** A specific java executable instead of Mojang's runtime. */
    public String javaPath;
    /** Use this directory as the game directory instead of the instance folder. */
    public String gameDirOverride;
    public List<String> extraJvmArgs = new ArrayList<>();
    public List<String> extraGameArgs = new ArrayList<>();
    public Set<String> enableMods = new LinkedHashSet<>();
    public Set<String> disableMods = new LinkedHashSet<>();
    /** Azure application (client) ID approved for the Minecraft API. See docs/AUTH.md. */
    public String msaClientId;
    /** Remember sign-in using the OS keystore. */
    public boolean rememberAccount = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Settings load(Path file) throws IOException {
        if (!Files.exists(file)) return new Settings();
        try {
            Settings s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Settings.class);
            return s == null ? new Settings() : s.normalized();
        } catch (JsonParseException e) {
            throw new IOException("launcher.json is not valid JSON (" + e.getMessage() + "); fix or delete " + file, e);
        }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    }

    private Settings normalized() {
        if (instance == null || instance.isBlank()) instance = "default";
        if (gameVersion == null || gameVersion.isBlank()) gameVersion = "auto";
        if (profile == null) profile = ModCatalog.Profile.OPTIMIZED;
        if (preset == null) preset = Preset.BALANCED;
        if (gc == null) gc = "auto";
        if (extraJvmArgs == null) extraJvmArgs = new ArrayList<>();
        if (extraGameArgs == null) extraGameArgs = new ArrayList<>();
        if (enableMods == null) enableMods = new LinkedHashSet<>();
        if (disableMods == null) disableMods = new LinkedHashSet<>();
        if (heapMb < 0) heapMb = 0;
        return this;
    }

    public Path gameDir(LauncherPaths paths) {
        return gameDirOverride != null && !gameDirOverride.isBlank() ? Path.of(gameDirOverride) : paths.instance(instance);
    }
}
