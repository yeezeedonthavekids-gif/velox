package dev.velox.launcher.java;

import dev.velox.launcher.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Checks JVM flags against the actual runtime before launch.
 *
 * <p>An unrecognized {@code -XX} flag makes the JVM refuse to start, and some valid combinations
 * only print warnings. Each optional flag is tried together with the required ones; if the JVM
 * exits non-zero or warns about it, the flag is dropped and the reason logged.
 */
public final class FlagProbe {
    private FlagProbe() {}

    public record Outcome(List<String> accepted, List<String> rejected) {}

    public static Outcome filter(Path java, List<String> required, List<String> optional) throws IOException {
        if (!run(java, required).ok) {
            throw new IOException("The Java runtime " + java + " rejects the base JVM arguments " + required);
        }
        List<String> accepted = new ArrayList<>(required);
        List<String> rejected = new ArrayList<>();
        for (String flag : optional) {
            List<String> trial = new ArrayList<>(accepted);
            trial.add(flag);
            Result r = run(java, trial);
            if (r.ok && !r.warned) {
                accepted.add(flag);
            } else {
                rejected.add(flag);
                Log.warn("Dropping JVM flag %s: %s", flag, r.firstLine);
            }
        }
        return new Outcome(accepted, rejected);
    }

    private record Result(boolean ok, boolean warned, String firstLine) {}

    private static Result run(Path java, List<String> flags) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(java.toString());
        // The probe only checks that flags are accepted; heap is shrunk so it can't fail on a busy machine.
        for (String f : flags) {
            if (f.startsWith("-Xms") || f.startsWith("-Xmx") || f.equals("-XX:+AlwaysPreTouch")) continue;
            cmd.add(f);
        }
        cmd.add("-Xmx64m");
        cmd.add("-version");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(false, false, "timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        // "Picked up JAVA_TOOL_OPTIONS" echoes on its own line; ignore it, but catch real JVM warnings.
        boolean warned = false;
        for (String line : out.split("\\R")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.startsWith("picked up")) continue;
            if (l.contains("warning")) warned = true;
        }
        String first = out.lines().filter(l -> !l.startsWith("Picked up")).findFirst().orElse("").trim();
        return new Result(p.exitValue() == 0, warned, first);
    }
}
