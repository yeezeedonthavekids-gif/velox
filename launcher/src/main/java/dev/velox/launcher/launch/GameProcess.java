package dev.velox.launcher.launch;

import dev.velox.launcher.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Stream;

/**
 * Runs the game and copies its output to {@code logs/velox-launch.log}. When the game exits with
 * an error, the end of the log and the newest crash report are surfaced instead of failing silently.
 */
public final class GameProcess {
    private GameProcess() {}

    public record Exit(int code, String tail, Path crashReport) {}

    public static Exit run(LaunchPlan plan, boolean echo) throws IOException {
        Path logDir = plan.workingDir().resolve("logs");
        Files.createDirectories(logDir);
        Path log = logDir.resolve("velox-launch.log");
        long started = System.currentTimeMillis();

        ProcessBuilder pb = new ProcessBuilder(plan.command()).directory(plan.workingDir().toFile()).redirectErrorStream(true);
        Log.info("Launching: %s", String.join(" ", plan.redacted()));
        Process p = pb.start();
        p.getOutputStream().close();
        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
             Writer w = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (plan.accessToken() != null && !plan.accessToken().isEmpty()) line = line.replace(plan.accessToken(), "<redacted>");
                w.write(line);
                w.write('\n');
                if (echo) System.out.println(line);
                tail.addLast(line);
                if (tail.size() > 40) tail.removeFirst();
            }
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while the game was running", e);
        }
        Path crash = newestCrashReport(plan.workingDir().resolve("crash-reports"), started);
        if (code != 0) {
            Log.error("Minecraft exited with code %d. Full log: %s", code, log);
            if (crash != null) Log.error("Crash report: %s", crash);
        }
        return new Exit(code, String.join("\n", tail), crash);
    }

    private static Path newestCrashReport(Path dir, long since) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.toString().endsWith(".txt"))
                    .filter(f -> f.toFile().lastModified() >= since)
                    .max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
                    .orElse(null);
        }
    }
}
