package dev.velox.launcher.launch;

import dev.velox.launcher.Log;
import dev.velox.launcher.mojang.ArgumentBuilder;
import dev.velox.launcher.mojang.GameInstaller;
import dev.velox.launcher.mojang.Platform;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** The exact command line for one game launch. */
public record LaunchPlan(Path java, List<String> command, Path workingDir, String accessToken) {

    public record Identity(String username, String uuid, String xuid, String accessToken, String clientId) {}

    public static LaunchPlan build(Path java, GameInstaller.Installed game, Identity id, Platform platform,
                                   Path gameDir, Path libraryDir, List<String> memoryAndGcArgs, List<String> extraJvm,
                                   List<String> extraGame, Path nativesDir) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("auth_player_name", id.username());
        vars.put("version_name", game.version().id);
        vars.put("game_directory", gameDir.toString());
        vars.put("assets_root", game.assetsRoot().toString());
        vars.put("assets_index_name", game.version().assets != null ? game.version().assets : game.version().assetIndex.id);
        vars.put("auth_uuid", id.uuid());
        vars.put("auth_access_token", id.accessToken());
        vars.put("auth_xuid", id.xuid() == null ? "0" : id.xuid());
        vars.put("clientid", id.clientId() == null ? "" : id.clientId());
        vars.put("user_type", "msa");
        vars.put("version_type", game.version().type == null ? "release" : game.version().type);
        vars.put("natives_directory", nativesDir.toString());
        vars.put("launcher_name", "velox");
        vars.put("launcher_version", "0.1.0");
        vars.put("library_directory", libraryDir.toString());
        vars.put("classpath_separator", File.pathSeparator);
        List<String> cp = new ArrayList<>();
        for (Path p : game.classpath()) cp.add(p.toString());
        vars.put("classpath", String.join(File.pathSeparator, cp));

        TreeSet<String> missing = new TreeSet<>();
        List<String> cmd = new ArrayList<>();
        cmd.add(java.toString());
        cmd.addAll(memoryAndGcArgs);
        List<String> versionJvm = ArgumentBuilder.expand(game.version().arguments.jvm, platform, vars, missing::add);
        if (versionJvm.isEmpty()) {
            versionJvm = List.of("-Djava.library.path=" + nativesDir, "-cp", vars.get("classpath"));
        }
        cmd.addAll(versionJvm);
        if (game.loggingConfig() != null && game.version().logging.client.argument != null) {
            cmd.add(game.version().logging.client.argument.replace("${path}", game.loggingConfig().toString()));
        }
        cmd.addAll(extraJvm);
        cmd.add(game.version().mainClass);
        cmd.addAll(ArgumentBuilder.expand(game.version().arguments.game, platform, vars, missing::add));
        cmd.addAll(extraGame);
        if (!missing.isEmpty()) Log.warn("No value for launch placeholders %s; passing them empty", missing);
        return new LaunchPlan(java, List.copyOf(cmd), gameDir, id.accessToken());
    }

    /** The command with the access token replaced, safe to print or log. */
    public List<String> redacted() {
        List<String> r = new ArrayList<>(command.size());
        for (String s : command) r.add(accessToken != null && !accessToken.isEmpty() ? s.replace(accessToken, "<redacted>") : s);
        return r;
    }
}
