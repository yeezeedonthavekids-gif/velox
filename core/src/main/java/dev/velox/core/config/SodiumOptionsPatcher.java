package dev.velox.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Applies the Sodium part of a preset to {@code config/sodium-options.json} before launch.
 *
 * <p>The key names match Sodium 0.9's {@code SodiumOptions} (Gson, lower-case-with-underscores).
 * Only the keys Velox owns are touched and every other key is preserved, so a newer Sodium
 * with extra options keeps them. Sodium fills absent keys with defaults, so writing a fresh
 * file with only these keys is safe too.
 */
public final class SodiumOptionsPatcher {
    public static final String FILE_NAME = "sodium-options.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SodiumOptionsPatcher() {}

    /** @return true if the file changed */
    public static boolean patch(Path configDir, GameSettings s) throws IOException {
        Path file = configDir.resolve(FILE_NAME);
        JsonObject root;
        String before = null;
        if (Files.exists(file)) {
            before = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(before);
            } catch (RuntimeException e) {
                // Sodium itself would fail to start on this file; leave it for the user to see.
                throw new IOException("sodium-options.json is not valid JSON; not modifying it", e);
            }
            root = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } else {
            root = new JsonObject();
        }
        JsonObject perf = child(root, "performance");
        if (s.sodiumChunkBuilderThreads != null) perf.addProperty("chunk_builder_threads", s.sodiumChunkBuilderThreads);
        if (s.sodiumChunkDefer != null) perf.addProperty("chunk_build_defer_mode", s.sodiumChunkDefer.name());
        if (s.sodiumAnimateOnlyVisibleTextures != null) perf.addProperty("animate_only_visible_textures", s.sodiumAnimateOnlyVisibleTextures);
        if (s.sodiumEntityCulling != null) perf.addProperty("use_entity_culling", s.sodiumEntityCulling);
        if (s.sodiumFogOcclusion != null) perf.addProperty("use_fog_occlusion", s.sodiumFogOcclusion);
        if (s.sodiumBlockFaceCulling != null) perf.addProperty("use_block_face_culling", s.sodiumBlockFaceCulling);

        String after = GSON.toJson(root);
        if (after.equals(before)) return false;
        Files.createDirectories(configDir);
        Files.writeString(file, after, StandardCharsets.UTF_8);
        return true;
    }

    private static JsonObject child(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        if (e != null && e.isJsonObject()) return e.getAsJsonObject();
        JsonObject o = new JsonObject();
        parent.add(key, o);
        return o;
    }
}
