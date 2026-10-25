package dev.velox.launcher.fabric;

import com.google.gson.JsonElement;
import dev.velox.launcher.net.Http;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Fabric's metadata service: which loader builds exist and the launcher profile for each. */
public final class FabricMeta {
    private final Http http;
    private final String base;

    public FabricMeta(Http http, String base) {
        this.http = http;
        this.base = base;
    }

    /** Newest stable loader for a game version, or the newest of any kind when none is marked stable. */
    public String latestLoader(String gameVersion) throws IOException {
        JsonElement list = http.getJson(base + "/v2/versions/loader/" + enc(gameVersion));
        String firstAny = null;
        for (JsonElement e : list.getAsJsonArray()) {
            var loader = e.getAsJsonObject().getAsJsonObject("loader");
            String version = loader.get("version").getAsString();
            if (firstAny == null) firstAny = version;
            if (loader.has("stable") && loader.get("stable").getAsBoolean()) return version;
        }
        if (firstAny == null) {
            throw new IOException("Fabric has no loader for Minecraft " + gameVersion + " yet");
        }
        return firstAny;
    }

    /** The profile JSON ({@code inheritsFrom} the vanilla version) as raw text, to store verbatim. */
    public String profileJson(String gameVersion, String loaderVersion) throws IOException {
        return http.getString(base + "/v2/versions/loader/" + enc(gameVersion) + "/" + enc(loaderVersion) + "/profile/json",
                java.util.Map.of());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
