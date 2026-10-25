package dev.velox.launcher;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Directory layout. Everything lives under one root so uninstalling is deleting one folder:
 * <pre>
 *   root/
 *     launcher.json          settings
 *     versions/&lt;id&gt;/       version JSON and client jar
 *     libraries/             Maven layout, shared by all instances
 *     assets/                indexes/ and objects/ (content-addressed)
 *     runtimes/&lt;component&gt;/ Java runtimes from Mojang
 *     instances/&lt;name&gt;/     game directories (mods/, config/, saves/ ...)
 * </pre>
 */
public record LauncherPaths(Path root) {

    public static LauncherPaths defaultRoot() {
        String override = System.getenv("VELOX_HOME");
        if (override != null && !override.isBlank()) return new LauncherPaths(Path.of(override));
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return new LauncherPaths((appData != null ? Path.of(appData) : home).resolve(".velox"));
        }
        if (os.contains("mac")) {
            return new LauncherPaths(home.resolve("Library/Application Support/velox"));
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return new LauncherPaths((xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".local/share")).resolve("velox"));
    }

    public Path settingsFile() { return root.resolve("launcher.json"); }
    public Path versions() { return root.resolve("versions"); }
    public Path versionDir(String id) { return versions().resolve(id); }
    public Path libraries() { return root.resolve("libraries"); }
    public Path assets() { return root.resolve("assets"); }
    public Path runtimes() { return root.resolve("runtimes"); }
    public Path instances() { return root.resolve("instances"); }
    public Path natives(String versionId) { return versionDir(versionId).resolve("natives"); }

    public Path instance(String name) {
        if (!name.matches("[A-Za-z0-9._-]{1,64}") || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Instance names may only use letters, digits, '.', '_' and '-': " + name);
        }
        return instances().resolve(name);
    }
}
