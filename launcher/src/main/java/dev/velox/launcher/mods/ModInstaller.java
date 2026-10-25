package dev.velox.launcher.mods;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.velox.launcher.Log;
import dev.velox.launcher.net.Http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs a {@link ModResolver.Plan} into an instance's {@code mods/} folder.
 *
 * <p>Velox only ever deletes files it installed itself, as recorded in
 * {@code mods/.velox-lock.json}, and only while they still match the recorded hash. Jars the
 * user dropped in are never touched. If one provides the same Fabric mod id as a managed mod,
 * the user's jar wins and the managed copy is not installed, which avoids duplicate-mod crashes.
 */
public final class ModInstaller {
    public static final String LOCK_FILE = ".velox-lock.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Http http;

    public ModInstaller(Http http) {
        this.http = http;
    }

    public static final class Lock {
        public String gameVersion;
        public List<LockedFile> files = new ArrayList<>();
        /** Managed mods not installed because a user jar provides the same mod id; avoids re-downloading them. */
        public List<LockedFile> shadowed = new ArrayList<>();
    }

    public static final class LockedFile {
        public String slug;
        public String versionNumber;
        public String filename;
        public String sha512;
        public String modId;
    }

    public record Result(List<LockedFile> installed, List<String> userMods, List<String> notes) {}

    /** A local jar to install as a managed mod (the bundled Velox mod). */
    public record LocalMod(String slug, Path jar) {}

    public Result install(ModResolver.Plan plan, List<LocalMod> local, Path modsDir) throws IOException {
        Files.createDirectories(modsDir);
        Lock old = readLock(modsDir);
        Map<String, LockedFile> oldByName = new HashMap<>();
        for (LockedFile f : old.files) oldByName.put(f.filename, f);

        // Mod ids provided by jars the user placed here themselves.
        Map<String, String> userModIds = new HashMap<>();
        List<String> userJars = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path jar : ds) {
                String name = jar.getFileName().toString();
                if (oldByName.containsKey(name)) continue;
                userJars.add(name);
                String id = fabricModId(jar);
                if (id != null) userModIds.put(id, name);
            }
        }

        List<String> notes = new ArrayList<>();
        Lock lock = new Lock();
        lock.gameVersion = plan.gameVersion();
        Map<String, String> knownModIds = new HashMap<>();
        for (LockedFile f : old.shadowed) if (f.modId != null) knownModIds.put(f.slug + "@" + f.sha512, f.modId);
        for (ModResolver.Planned p : plan.mods()) {
            checkFileName(p.filename());
            String knownId = knownModIds.get(p.slug() + "@" + p.sha512());
            if (knownId != null && userModIds.containsKey(knownId)) {
                lock.shadowed.add(locked(p.slug(), p.versionNumber(), p.filename(), p.sha512(), knownId));
                notes.add(p.title() + ": skipped, already provided by user jar " + userModIds.get(knownId));
                continue;
            }
            Path target = modsDir.resolve(p.filename());
            if (userJars.contains(p.filename())) {
                notes.add(p.title() + ": a user-provided " + p.filename() + " is already present; leaving it");
                continue;
            }
            Http.Expect expect = Http.Expect.sha512(p.sha512(), p.size());
            if (!Http.matches(target, expect)) http.download(p.url(), target, expect);
            String modId = fabricModId(target);
            if (modId != null && userModIds.containsKey(modId)) {
                Files.delete(target);
                lock.shadowed.add(locked(p.slug(), p.versionNumber(), p.filename(), p.sha512(), modId));
                notes.add(p.title() + ": skipped, already provided by user jar " + userModIds.get(modId));
                continue;
            }
            lock.files.add(locked(p.slug(), p.versionNumber(), p.filename(), p.sha512(), modId));
        }
        for (LocalMod m : local) {
            String filename = m.slug() + ".jar";
            Path target = modsDir.resolve(filename);
            if (userJars.contains(filename)) {
                // A file the lock does not know about with our reserved name: don't clobber it.
                notes.add(filename + " exists but was not installed by Velox; leaving it");
                continue;
            }
            Files.copy(m.jar(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            lock.files.add(locked(m.slug(), "local", filename, Http.hashFile(target, "SHA-512"), fabricModId(target)));
        }

        // Remove previously managed files that are no longer planned.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (LockedFile f : lock.files) keep.add(f.filename);
        for (LockedFile f : old.files) {
            if (keep.contains(f.filename)) continue;
            checkFileName(f.filename);
            Path p = modsDir.resolve(f.filename);
            if (!Files.exists(p)) continue;
            if (f.sha512 != null && Http.hashFile(p, "SHA-512").equalsIgnoreCase(f.sha512)) {
                Files.delete(p);
                notes.add("removed " + f.filename + " (no longer selected)");
            } else {
                notes.add("kept " + f.filename + ": modified since Velox installed it");
            }
        }

        writeLock(modsDir, lock);
        for (String n : notes) Log.info("  %s", n);
        return new Result(lock.files, userJars, notes);
    }

    private static LockedFile locked(String slug, String version, String filename, String sha512, String modId) {
        LockedFile f = new LockedFile();
        f.slug = slug;
        f.versionNumber = version;
        f.filename = filename;
        f.sha512 = sha512;
        f.modId = modId;
        return f;
    }

    /** File names come from a remote API; refuse anything that could escape the mods folder. */
    static void checkFileName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")
                || name.startsWith(".") || !name.endsWith(".jar")) {
            throw new IOException("Refusing unsafe mod file name: " + name);
        }
    }

    public static Lock readLock(Path modsDir) throws IOException {
        Path f = modsDir.resolve(LOCK_FILE);
        if (!Files.exists(f)) return new Lock();
        Lock l = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Lock.class);
        if (l == null) return new Lock();
        if (l.files == null) l.files = new ArrayList<>();
        if (l.shadowed == null) l.shadowed = new ArrayList<>();
        return l;
    }

    private static void writeLock(Path modsDir, Lock lock) throws IOException {
        Files.writeString(modsDir.resolve(LOCK_FILE), GSON.toJson(lock), StandardCharsets.UTF_8);
    }

    /** The {@code id} from a jar's {@code fabric.mod.json}, or null if absent or unreadable. */
    public static String fabricModId(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry("fabric.mod.json");
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                return o.has("id") ? o.get("id").getAsString() : null;
            }
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }
}
