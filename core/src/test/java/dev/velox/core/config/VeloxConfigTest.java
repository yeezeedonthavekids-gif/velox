package dev.velox.core.config;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VeloxConfigTest {
    @TempDir Path dir;

    @Test
    void roundTrip() throws Exception {
        Path f = VeloxConfig.path(dir);
        VeloxConfig c = new VeloxConfig();
        c.preset = Preset.COMPETITIVE;
        c.crosshair.custom = true;
        c.crosshair.color = 0xFF00FF00;
        c.custom.renderDistance = 14;
        c.save(f);
        VeloxConfig r = VeloxConfig.load(f);
        assertEquals(Preset.COMPETITIVE, r.preset);
        assertTrue(r.crosshair.custom);
        assertEquals(0xFF00FF00, r.crosshair.color);
        assertEquals(14, r.custom.renderDistance);
        assertFalse(Files.exists(f.resolveSibling("velox.json.tmp")));
    }

    @Test
    void missingFileGivesDefaults() throws Exception {
        assertEquals(Preset.BALANCED, VeloxConfig.load(dir.resolve("nope.json")).preset);
    }

    @Test
    void corruptFileIsMovedAsideNotLost() throws Exception {
        Path f = dir.resolve("velox.json");
        Files.writeString(f, "{ this is not json");
        VeloxConfig c = VeloxConfig.load(f);
        assertEquals(Preset.BALANCED, c.preset);
        assertTrue(Files.exists(dir.resolve("velox.json.broken")));
    }

    @Test
    void outOfRangeValuesAreSanitized() throws Exception {
        Path f = dir.resolve("velox.json");
        Files.writeString(f, "{\"preset\":null,\"crosshair\":{\"length\":500,\"gap\":-3},\"benchmark\":{\"durationSeconds\":1}}");
        VeloxConfig c = VeloxConfig.load(f);
        assertEquals(Preset.BALANCED, c.preset);
        assertEquals(32, c.crosshair.length);
        assertEquals(0, c.crosshair.gap);
        assertEquals(5, c.benchmark.durationSeconds);
        assertNotNull(c.hud);
    }

    @Test
    void sodiumPatchPreservesUnknownKeys() throws Exception {
        Files.writeString(dir.resolve("sodium-options.json"), """
                {"quality":{"pixel_filtering_mode":"NEAREST"},
                 "performance":{"chunk_builder_threads":3,"quad_splitting_mode":"SAFE","future_key":7},
                 "notifications":{"has_seen_donation_prompt":true}}""");
        GameSettings s = PresetEngine.resolve(Preset.MAX_FPS, null, null);
        assertTrue(SodiumOptionsPatcher.patch(dir, s));
        var root = JsonParser.parseString(Files.readString(dir.resolve("sodium-options.json"))).getAsJsonObject();
        var perf = root.getAsJsonObject("performance");
        assertEquals(0, perf.get("chunk_builder_threads").getAsInt());
        assertEquals("ALWAYS", perf.get("chunk_build_defer_mode").getAsString());
        assertEquals(7, perf.get("future_key").getAsInt());
        assertEquals("SAFE", perf.get("quad_splitting_mode").getAsString());
        assertTrue(root.getAsJsonObject("notifications").get("has_seen_donation_prompt").getAsBoolean());
        assertFalse(SodiumOptionsPatcher.patch(dir, s), "second patch is a no-op");
    }

    @Test
    void sodiumPatchRefusesInvalidJson() throws Exception {
        Files.writeString(dir.resolve("sodium-options.json"), "{broken");
        assertThrows(java.io.IOException.class, () -> SodiumOptionsPatcher.patch(dir, new GameSettings()));
        assertEquals("{broken", Files.readString(dir.resolve("sodium-options.json")));
    }
}
