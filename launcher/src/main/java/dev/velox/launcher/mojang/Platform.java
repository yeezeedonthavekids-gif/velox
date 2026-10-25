package dev.velox.launcher.mojang;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The OS/arch/features a version's rules are evaluated against.
 *
 * @param os       Mojang OS name: {@code windows}, {@code osx} or {@code linux}
 * @param arch     {@code x86_64}, {@code arm64} or {@code x86}
 * @param features enabled launcher features (e.g. {@code has_custom_resolution})
 */
public record Platform(String os, String arch, String osVersion, Set<String> features) {

    public static Platform current(Set<String> features) {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String os = n.contains("win") ? "windows" : n.contains("mac") ? "osx" : "linux";
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String arch = a.equals("aarch64") || a.equals("arm64") ? "arm64"
                : a.contains("64") ? "x86_64" : "x86";
        return new Platform(os, arch, System.getProperty("os.version", ""), Set.copyOf(features));
    }

    /** Key used by Mojang's Java runtime manifest for this platform. */
    public String runtimeKey() {
        return switch (os) {
            case "windows" -> arch.equals("arm64") ? "windows-arm64" : arch.equals("x86") ? "windows-x86" : "windows-x64";
            case "osx" -> arch.equals("arm64") ? "mac-os-arm64" : "mac-os";
            default -> arch.equals("x86") ? "linux-i386" : "linux";
        };
    }

    /**
     * Mojang rule semantics: with no rules a thing is allowed; with rules it starts disallowed
     * and each matching rule sets the outcome, so later rules win.
     */
    public boolean allows(java.util.List<VersionInfo.Rule> rules) {
        if (rules == null || rules.isEmpty()) return true;
        boolean allowed = false;
        for (VersionInfo.Rule r : rules) {
            if (matches(r)) allowed = "allow".equals(r.action);
        }
        return allowed;
    }

    private boolean matches(VersionInfo.Rule r) {
        if (r.os != null) {
            if (r.os.name != null && !r.os.name.equals(os)) return false;
            if (r.os.arch != null && !archMatches(r.os.arch)) return false;
            if (r.os.version != null && !Pattern.compile(r.os.version).matcher(osVersion).find()) return false;
        }
        if (r.features != null) {
            for (Map.Entry<String, Boolean> f : r.features.entrySet()) {
                if (features.contains(f.getKey()) != f.getValue()) return false;
            }
        }
        return true;
    }

    private boolean archMatches(String ruleArch) {
        // Version JSONs use "x86" to mean a 32-bit JVM and "arm64" for ARM.
        return switch (ruleArch) {
            case "x86" -> arch.equals("x86");
            case "x86_64", "amd64" -> arch.equals("x86_64");
            case "arm64", "aarch64" -> arch.equals("arm64");
            default -> ruleArch.equals(arch);
        };
    }
}
