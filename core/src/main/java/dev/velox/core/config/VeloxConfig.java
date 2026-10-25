package dev.velox.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The single Velox configuration file ({@code <gameDir>/config/velox.json}), shared by the
 * launcher (which writes the chosen preset before launch) and the mod (which applies it).
 */
public final class VeloxConfig {
    public static final String FILE_NAME = "velox.json";

    public int schema = 1;
    public Preset preset = Preset.BALANCED;
    /** Set by the launcher or the settings screen; the mod applies the preset once and clears it. */
    public boolean applyPresetOnStart = true;
    /** Only consulted for {@link Preset#CUSTOM}. */
    public GameSettings custom = new GameSettings();
    public Hud hud = new Hud();
    public Crosshair crosshair = new Crosshair();
    public Ui ui = new Ui();
    public Benchmark benchmark = new Benchmark();

    public static final class Hud {
        public boolean enabled = true;
        public boolean fps = true;
        public boolean frameTime = true;
        public boolean lows = true;
        public boolean memory = false;
        public boolean ping = true;
        public boolean graph = false;
        public Corner corner = Corner.TOP_LEFT;
        /** Hide non-essential vanilla HUD parts (see {@link #hiddenElements}). */
        public boolean minimalHud = false;
        /** Vanilla HUD element ids (Fabric's VanillaHudElements paths) hidden in minimal mode. */
        public List<String> hiddenElements = new ArrayList<>(List.of("mob_effects", "held_item_tooltip"));
        /** Text color, ARGB. */
        public int color = 0xFFE8E8E8;
        public boolean background = true;
    }

    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public static final class Crosshair {
        public boolean custom = false;
        public Style style = Style.CROSS;
        public int length = 5;
        public int thickness = 1;
        public int gap = 2;
        public int color = 0xFFFFFFFF;
        public boolean outline = true;
        public int outlineColor = 0xC0000000;
        public boolean centerDot = false;

        public enum Style { CROSS, DOT, T_SHAPE, CIRCLE }
    }

    public static final class Ui {
        /** Animated menu transitions; off by default. */
        public boolean animations = false;
    }

    public static final class Benchmark {
        /** static, flight (singleplayer only) or free; see docs/BENCHMARKING.md. */
        public String scenario = "static";
        public int warmupSeconds = 10;
        public int durationSeconds = 60;
        public String outputDir = "velox-benchmarks";
    }

    /** Brings out-of-range or missing values (from hand edits or older files) back into range. */
    public VeloxConfig sanitized() {
        if (preset == null) preset = Preset.BALANCED;
        if (custom == null) custom = new GameSettings();
        custom = custom.clamped();
        if (hud == null) hud = new Hud();
        if (hud.corner == null) hud.corner = Corner.TOP_LEFT;
        if (hud.hiddenElements == null) hud.hiddenElements = new ArrayList<>();
        if (crosshair == null) crosshair = new Crosshair();
        if (crosshair.style == null) crosshair.style = Crosshair.Style.CROSS;
        crosshair.length = clamp(crosshair.length, 1, 32);
        crosshair.thickness = clamp(crosshair.thickness, 1, 8);
        crosshair.gap = clamp(crosshair.gap, 0, 32);
        if (ui == null) ui = new Ui();
        if (benchmark == null) benchmark = new Benchmark();
        benchmark.warmupSeconds = clamp(benchmark.warmupSeconds, 0, 600);
        benchmark.durationSeconds = clamp(benchmark.durationSeconds, 5, 3600);
        if (benchmark.outputDir == null || benchmark.outputDir.isBlank()) benchmark.outputDir = "velox-benchmarks";
        if (!java.util.List.of("static", "flight", "free").contains(benchmark.scenario)) benchmark.scenario = "static";
        return this;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path path(Path gameDir) {
        return gameDir.resolve("config").resolve(FILE_NAME);
    }

    /**
     * Loads the config, or returns defaults when the file is missing. A corrupt file is moved
     * aside to {@code velox.json.broken} rather than silently overwritten, so hand edits are not lost.
     */
    public static VeloxConfig load(Path file) throws IOException {
        if (!Files.exists(file)) return new VeloxConfig();
        String json = Files.readString(file, StandardCharsets.UTF_8);
        try {
            VeloxConfig c = GSON.fromJson(json, VeloxConfig.class);
            return (c == null ? new VeloxConfig() : c).sanitized();
        } catch (JsonParseException e) {
            Files.move(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            return new VeloxConfig();
        }
    }

    /** Writes via a temp file and rename so a crash mid-write never leaves a truncated config. */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public GameSettings effectiveSettings(dev.velox.core.hw.HardwareProfile hw) {
        return PresetEngine.resolve(preset, hw, custom);
    }
}
