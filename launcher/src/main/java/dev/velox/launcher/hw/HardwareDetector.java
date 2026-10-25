package dev.velox.launcher.hw;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.velox.core.hw.HardwareProfile;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Builds a {@link HardwareProfile} from the JVM and read-only OS queries. Every probe is
 * optional and time-limited, so a missing tool just leaves that field unknown.
 */
public final class HardwareDetector {
    private HardwareDetector() {}

    public static HardwareProfile detect() {
        int cores = Runtime.getRuntime().availableProcessors();
        long ramMb = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            ramMb = os.getTotalMemorySize() / (1024 * 1024);
        }
        Gpu gpu = detectGpu();
        return new HardwareProfile(cores, ramMb, gpu == null ? null : gpu.name, gpu == null ? -1 : gpu.vramMb, refreshRate());
    }

    record Gpu(String name, long vramMb) {}

    static Gpu detectGpu() {
        // nvidia-smi reports exact VRAM (WMI caps it at 4 GiB) and ships with the NVIDIA driver.
        String smi = run(List.of("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits"));
        if (smi != null && !smi.isBlank()) {
            Gpu g = parseNvidiaSmi(smi);
            if (g != null) return g;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<Gpu> gpus = new ArrayList<>();
        if (os.contains("win")) {
            String json = run(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-CimInstance Win32_VideoController | Select-Object Name,AdapterRAM | ConvertTo-Json -Compress"));
            if (json != null) gpus.addAll(parseWmi(json));
        } else if (os.contains("mac")) {
            String out = run(List.of("system_profiler", "SPDisplaysDataType"));
            if (out != null) {
                for (String line : out.split("\\R")) {
                    String t = line.trim();
                    if (t.startsWith("Chipset Model:")) gpus.add(new Gpu(t.substring(14).trim(), -1));
                }
            }
        } else {
            String out = run(List.of("lspci", "-mm"));
            if (out != null) gpus.addAll(parseLspci(out));
        }
        return pickPrimary(gpus);
    }

    static Gpu parseNvidiaSmi(String out) {
        String first = out.lines().findFirst().orElse("");
        String[] parts = first.split(",");
        if (parts.length < 1 || parts[0].isBlank()) return null;
        long vram = -1;
        if (parts.length > 1) {
            try {
                vram = Long.parseLong(parts[1].trim());
            } catch (NumberFormatException ignored) {
                // leave unknown
            }
        }
        return new Gpu(parts[0].trim(), vram);
    }

    static List<Gpu> parseWmi(String json) {
        List<Gpu> out = new ArrayList<>();
        try {
            JsonElement e = JsonParser.parseString(json);
            JsonArray arr = e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
            if (e.isJsonObject()) arr.add(e);
            for (JsonElement g : arr) {
                JsonObject o = g.getAsJsonObject();
                String name = o.has("Name") && !o.get("Name").isJsonNull() ? o.get("Name").getAsString() : null;
                long ram = o.has("AdapterRAM") && !o.get("AdapterRAM").isJsonNull() ? o.get("AdapterRAM").getAsLong() / (1024 * 1024) : -1;
                if (name != null) out.add(new Gpu(name, ram));
            }
        } catch (RuntimeException ignored) {
            // unexpected output: no GPU info
        }
        return out;
    }

    static List<Gpu> parseLspci(String out) {
        List<Gpu> gpus = new ArrayList<>();
        for (String line : out.split("\\R")) {
            // lspci -mm: slot "class" "vendor" "device" ...
            List<String> f = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(line);
            while (m.find()) f.add(m.group(1));
            if (f.size() >= 3 && (f.get(0).contains("VGA") || f.get(0).contains("3D") || f.get(0).contains("Display"))) {
                gpus.add(new Gpu(f.get(1) + " " + f.get(2), -1));
            }
        }
        return gpus;
    }

    /** Prefer a discrete NVIDIA/AMD adapter over integrated graphics and virtual display drivers. */
    static Gpu pickPrimary(List<Gpu> gpus) {
        Gpu best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Gpu g : gpus) {
            HardwareProfile hp = new HardwareProfile(1, 1, g.name, g.vramMb, -1);
            int score = switch (hp.gpuVendor()) {
                case NVIDIA -> 30;
                case AMD -> hp.integratedGpu() ? 10 : 30;
                case INTEL -> hp.integratedGpu() ? 5 : 25;
                case APPLE -> 20;
                case UNKNOWN -> 0; // e.g. "Microsoft Basic Display Adapter", remote-desktop drivers
            };
            if (score > bestScore) {
                best = g;
                bestScore = score;
            }
        }
        return best;
    }

    static int refreshRate() {
        if (GraphicsEnvironment.isHeadless()) return -1;
        try {
            int max = -1;
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                max = Math.max(max, d.getDisplayMode().getRefreshRate());
            }
            return max;
        } catch (RuntimeException | java.awt.AWTError e) {
            return -1;
        }
    }

    private static String run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null; // tool not installed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
