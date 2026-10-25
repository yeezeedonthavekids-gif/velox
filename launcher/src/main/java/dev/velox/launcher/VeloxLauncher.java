package dev.velox.launcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.velox.core.config.GameSettings;
import dev.velox.core.config.SodiumOptionsPatcher;
import dev.velox.core.config.VeloxConfig;
import dev.velox.core.hw.HardwareProfile;
import dev.velox.core.jvm.JvmArgs;
import dev.velox.launcher.auth.MicrosoftAuth;
import dev.velox.launcher.auth.SecretStore;
import dev.velox.launcher.java.FlagProbe;
import dev.velox.launcher.java.JavaRuntimes;
import dev.velox.launcher.launch.LaunchPlan;
import dev.velox.launcher.mods.ModCatalog;
import dev.velox.launcher.mods.ModInstaller;
import dev.velox.launcher.mods.ModResolver;
import dev.velox.launcher.mods.Modrinth;
import dev.velox.launcher.mojang.GameInstaller;
import dev.velox.launcher.mojang.Platform;
import dev.velox.launcher.net.DownloadQueue;
import dev.velox.launcher.net.Http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Install and launch, shared by the CLI and the GUI. */
public final class VeloxLauncher {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final String STATE_FILE = ".velox-install.json";

    private final Http http;
    private final Endpoints endpoints;
    private final LauncherPaths paths;
    private final Platform platform;

    public VeloxLauncher(Http http, Endpoints endpoints, LauncherPaths paths) {
        this(http, endpoints, paths, Platform.current(Set.of()));
    }

    public VeloxLauncher(Http http, Endpoints endpoints, LauncherPaths paths, Platform platform) {
        this.http = http;
        this.endpoints = endpoints;
        this.paths = paths;
        this.platform = platform;
    }

    public LauncherPaths paths() { return paths; }

    /** What was installed into an instance; launching needs only this plus local files. */
    public static final class InstallState {
        public String gameVersion;
        public String loaderVersion;
        public String profileId;
        public String javaComponent;
        public int javaMajor;
        public ModCatalog.Profile profile;
        public List<ModResolver.Planned> mods = new ArrayList<>();
        public List<ModResolver.Skipped> skipped = new ArrayList<>();
    }

    public static InstallState readState(Path gameDir) throws IOException {
        Path f = gameDir.resolve(STATE_FILE);
        if (!Files.exists(f)) return null;
        return GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), InstallState.class);
    }

    public InstallState install(Settings s, HardwareProfile hw, Path bundledMod, DownloadQueue.Progress progress)
            throws IOException {
        Path gameDir = s.gameDir(paths);
        Files.createDirectories(gameDir);
        GameInstaller game = new GameInstaller(http, endpoints, paths, platform);
        ModCatalog catalog = ModCatalog.bundled();
        List<ModCatalog.Entry> selected = catalog.select(s.profile, s.enableMods, s.disableMods);
        Modrinth modrinth = new Modrinth(http, endpoints.modrinthApi());
        ModResolver resolver = new ModResolver(modrinth);

        String gameVersion = s.gameVersion;
        if (gameVersion == null || gameVersion.equals("auto")) {
            // Resolve against the optimized set even for a baseline install, so both profiles run
            // the same Minecraft version and benchmarks stay comparable.
            List<ModCatalog.Entry> required = catalog.select(ModCatalog.Profile.OPTIMIZED, Set.of(), Set.of());
            List<String> releases = new ArrayList<>();
            for (GameInstaller.ManifestEntry m : game.releases()) releases.add(m.id());
            gameVersion = resolver.newestSupportedGameVersion(required, releases);
            Log.info("Newest Minecraft release supported by all required mods: %s", gameVersion);
        }

        ModResolver.Plan plan = resolver.resolve(gameVersion, selected, catalog.conflicts);
        GameInstaller.Installed installed = game.install(gameVersion, s.loaderVersion, false, progress);

        List<ModInstaller.LocalMod> local = new ArrayList<>();
        if (bundledMod != null) local.add(new ModInstaller.LocalMod("velox", bundledMod));
        else Log.warn("Velox mod jar not found: the HUD, presets and benchmark recorder will be unavailable in game");
        new ModInstaller(http).install(plan, local, gameDir.resolve("mods"));
        for (ModResolver.Skipped sk : plan.skipped()) Log.warn("Skipped %s: %s", sk.slug(), sk.reason());

        applyPreset(s, hw, gameDir, plan);

        InstallState st = new InstallState();
        st.gameVersion = installed.gameVersion();
        st.loaderVersion = installed.loaderVersion();
        st.profileId = installed.profileId();
        st.javaComponent = installed.version().javaVersion == null ? null : installed.version().javaVersion.component;
        st.javaMajor = installed.version().javaVersion == null ? 21 : installed.version().javaVersion.majorVersion;
        st.profile = s.profile;
        st.mods = plan.mods();
        st.skipped = plan.skipped();
        Files.writeString(gameDir.resolve(STATE_FILE), GSON.toJson(st), StandardCharsets.UTF_8);
        return st;
    }

    /** Writes the preset choice for the mod and the Sodium part of it into Sodium's config. */
    public static void applyPreset(Settings s, HardwareProfile hw, Path gameDir, ModResolver.Plan plan) throws IOException {
        Path cfgFile = VeloxConfig.path(gameDir);
        VeloxConfig cfg = VeloxConfig.load(cfgFile);
        cfg.preset = s.preset;
        cfg.applyPresetOnStart = true;
        cfg.save(cfgFile);
        boolean hasSodium = plan == null || plan.mods().stream().anyMatch(m -> m.slug().equals("sodium"));
        if (hasSodium) {
            GameSettings gs = cfg.effectiveSettings(hw);
            try {
                if (SodiumOptionsPatcher.patch(gameDir.resolve("config"), gs)) Log.info("Applied %s preset to Sodium options", s.preset.displayName);
            } catch (IOException e) {
                Log.warn("%s", e.getMessage());
            }
        }
    }

    public record JavaChoice(Path executable, int major) {}

    public JavaChoice java(Settings s, InstallState st, DownloadQueue.Progress progress) throws IOException {
        int need = st.javaMajor;
        if (s.javaPath != null && !s.javaPath.isBlank()) {
            Path j = Path.of(s.javaPath);
            int major = JavaRuntimes.majorVersion(j);
            if (major < 0) throw new IOException("Configured Java " + j + " does not run");
            if (major < need) throw new IOException("Minecraft " + st.gameVersion + " needs Java " + need + ", but " + j + " is Java " + major);
            return new JavaChoice(j, major);
        }
        if (st.javaComponent == null) throw new IOException("Version metadata names no Java runtime; set a Java path in settings");
        JavaRuntimes rt = new JavaRuntimes(http, endpoints.javaRuntimes(), paths.runtimes(), platform);
        Path j;
        try {
            j = rt.ensureMojangRuntime(st.javaComponent, progress);
        } catch (IOException e) {
            Path existing = rt.javaExecutable(paths.runtimes().resolve(st.javaComponent));
            if (!Files.isExecutable(existing)) throw e;
            Log.warn("Could not refresh the Java runtime (%s); using the installed copy", e.getMessage());
            j = existing;
        }
        int major = JavaRuntimes.majorVersion(j);
        return new JavaChoice(j, major > 0 ? major : need);
    }

    public static JvmArgs.Plan jvmPlan(Settings s, HardwareProfile hw, int javaMajor) {
        int rd = dev.velox.core.config.PresetEngine.resolve(s.preset, hw, null).renderDistance;
        int heap = s.heapMb > 0 ? s.heapMb : JvmArgs.recommendedHeapMb(hw.totalRamMb(), rd);
        JvmArgs.Gc gc = switch (s.gc.toLowerCase(java.util.Locale.ROOT)) {
            case "zgc" -> JvmArgs.Gc.ZGC;
            case "g1" -> JvmArgs.Gc.G1;
            default -> JvmArgs.recommendedGc(hw, heap, javaMajor, s.preset == dev.velox.core.config.Preset.MAX_FPS);
        };
        return JvmArgs.plan(hw, heap, gc, javaMajor, s.preTouchHeap);
    }

    /** Builds the launch command. Probing flags runs the target java a few times (~100 ms each). */
    public LaunchPlan plan(Settings s, HardwareProfile hw, InstallState st, JavaChoice java,
                           LaunchPlan.Identity id, boolean probeFlags) throws IOException {
        Path gameDir = s.gameDir(paths);
        GameInstaller.Installed game = new GameInstaller(http, endpoints, paths, platform)
                .loadInstalled(st.gameVersion, st.loaderVersion, st.profileId);
        JvmArgs.Plan jp = jvmPlan(s, hw, java.major());
        List<String> mem = jp.args();
        if (probeFlags) {
            List<String> required = new ArrayList<>();
            List<String> optional = new ArrayList<>();
            for (String a : jp.args()) (a.startsWith("-Xm") ? required : optional).add(a);
            mem = FlagProbe.filter(java.executable(), required, optional).accepted();
        }
        Log.info("JVM: %d MiB heap, %s; %s", jp.heapMb(), jp.gc(), String.join("; ", jp.notes()));
        Path natives = paths.natives(st.profileId);
        Files.createDirectories(natives);
        List<String> extraJvm = new ArrayList<>(veloxProperties(hw, st));
        extraJvm.addAll(s.extraJvmArgs);
        return LaunchPlan.build(java.executable(), game, id, platform, gameDir, paths.libraries(), mem,
                extraJvm, s.extraGameArgs, natives);
    }

    /** What the mod cannot detect from inside the game: hardware as the launcher saw it, and the profile. */
    static List<String> veloxProperties(HardwareProfile hw, InstallState st) {
        List<String> p = new ArrayList<>();
        p.add("-Dvelox.profile=" + st.profile);
        p.add("-Dvelox.cores=" + hw.logicalCores());
        p.add("-Dvelox.ramMb=" + hw.totalRamMb());
        if (hw.gpuName() != null) p.add("-Dvelox.gpu=" + hw.gpuName());
        if (hw.vramMb() > 0) p.add("-Dvelox.vramMb=" + hw.vramMb());
        p.add("-Dvelox.hardware=" + hw.describe());
        return p;
    }

    /** Signs in from the stored refresh token, or runs the device-code flow. */
    public MicrosoftAuth.Session signIn(Settings s, Consumer<MicrosoftAuth.DeviceCode> prompt, boolean forceNew) throws IOException {
        MicrosoftAuth auth = new MicrosoftAuth(http, endpoints, s.msaClientId);
        SecretStore store = s.rememberAccount ? SecretStore.forCurrentOs(paths.root()) : SecretStore.none();
        if (!forceNew) {
            try {
                var saved = store.get();
                if (saved.isPresent()) {
                    MicrosoftAuth.Session session = auth.refresh(saved.get());
                    if (session.refreshToken() != null) store.put(session.refreshToken());
                    return session;
                }
            } catch (IOException e) {
                Log.warn("Saved sign-in could not be used: %s", e.getMessage());
            }
        }
        MicrosoftAuth.Session session = auth.loginWithDeviceCode(prompt);
        if (session.refreshToken() != null) {
            try {
                store.put(session.refreshToken());
                Log.info("Sign-in saved with %s", store.name());
            } catch (IOException e) {
                Log.warn("Could not save sign-in (%s); you will need to sign in next time", e.getMessage());
            }
        }
        return session;
    }

    public void signOut() throws IOException {
        SecretStore.forCurrentOs(paths.root()).clear();
    }

    /** The Velox mod jar: bundled inside the launcher, or next to it, or null. */
    public static Path findBundledMod(Path tempDir) throws IOException {
        try (InputStream in = VeloxLauncher.class.getResourceAsStream("/bundled/velox-mod.jar")) {
            if (in != null) {
                Path out = tempDir.resolve("velox-mod.jar");
                Files.createDirectories(tempDir);
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                return out;
            }
        }
        Path beside = Path.of("velox-mod.jar");
        return Files.isRegularFile(beside) ? beside : null;
    }
}
