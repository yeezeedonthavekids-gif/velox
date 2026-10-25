package dev.velox.core.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One benchmark run as written to disk: {@code <name>.json} (this record) plus
 * {@code <name>.frames.csv} (every frame time, so results can be re-analysed with other tools).
 */
public record BenchmarkReport(
        int schema,
        String label,
        String scenario,
        String startedAt,
        double warmupSec,
        Map<String, String> environment,
        List<String> mods,
        FrameStats stats,
        long droppedFrames,
        GcMonitor.Summary gc,
        Heap heap,
        double avgProcessCpuPct,
        ChunkLoadTracker.Result chunks) {

    public static final int SCHEMA = 1;

    public record Heap(double maxMb, double avgUsedMb, double peakUsedMb) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();

    public void write(Path dir, String baseName, long[] frameNanos) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(baseName + ".json"), GSON.toJson(this), StandardCharsets.UTF_8);
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve(baseName + ".frames.csv"), StandardCharsets.UTF_8)) {
            w.write("frame,frame_time_ms\n");
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < frameNanos.length; i++) {
                sb.setLength(0);
                sb.append(i).append(',').append(frameNanos[i] / 1e6).append('\n');
                w.write(sb.toString());
            }
        }
    }

    public static BenchmarkReport read(Path json) throws IOException {
        try (Reader r = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
            BenchmarkReport report = GSON.fromJson(r, BenchmarkReport.class);
            if (report == null || report.stats() == null) {
                throw new IOException("Not a Velox benchmark report: " + json);
            }
            if (report.schema() != SCHEMA) {
                throw new IOException("Unsupported report schema " + report.schema() + " in " + json);
            }
            return report;
        }
    }
}
