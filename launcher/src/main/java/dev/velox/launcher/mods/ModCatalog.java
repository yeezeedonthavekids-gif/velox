package dev.velox.launcher.mods;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The curated mod list ({@code velox/mods.json}). */
public final class ModCatalog {
    public List<Entry> mods = new ArrayList<>();
    /** Pairs of slugs never installed together; the later entry in {@link #mods} is dropped. */
    public List<List<String>> conflicts = new ArrayList<>();

    public static final class Entry {
        public String slug;
        public String name;
        public String role;
        public boolean required;
        public boolean defaultEnabled;
        /** Part of the benchmark baseline profile (only Fabric API is). */
        public boolean baseline;
        public String expectedLicense;
    }

    public static ModCatalog bundled() throws IOException {
        try (InputStream in = ModCatalog.class.getResourceAsStream("/velox/mods.json")) {
            if (in == null) throw new IOException("Bundled mod catalog missing from the launcher jar");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return new Gson().fromJson(r, ModCatalog.class);
            }
        }
    }

    public enum Profile {
        /** The curated optimization set. */
        OPTIMIZED,
        /** Fabric API + Velox only: the same measuring tools with no optimization mods, for A/B benchmarks. */
        BASELINE
    }

    /** Entries to install for a profile, honoring the user's explicit enables/disables. */
    public List<Entry> select(Profile profile, Set<String> enable, Set<String> disable) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : mods) {
            boolean on = profile == Profile.BASELINE ? e.baseline : e.defaultEnabled;
            if (profile == Profile.OPTIMIZED) {
                if (enable.contains(e.slug)) on = true;
                if (disable.contains(e.slug)) {
                    if (e.required) throw new IllegalArgumentException(e.name + " is required and cannot be disabled");
                    on = false;
                }
            }
            if (on) out.add(e);
        }
        return out;
    }

    public Entry find(String slug) {
        for (Entry e : mods) if (e.slug.equals(slug)) return e;
        return null;
    }
}
