package dev.velox.launcher.mods;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides exactly which mod builds to install for a Minecraft version.
 *
 * <ul>
 *   <li>Game version: either pinned, or the newest Mojang release that every <em>required</em>
 *       mod supports on Fabric, because a release often comes out before Sodium supports it.</li>
 *   <li>Build choice: the newest {@code release} build, then {@code beta}. Alpha builds are
 *       never picked automatically.</li>
 *   <li>Dependencies: required dependencies missing from the set are added automatically.
 *       If a project declares itself {@code incompatible} with another selected one, the
 *       lower-priority optional mod is dropped, with the reason recorded.</li>
 * </ul>
 * Optional mods without a compatible build are skipped and reported, not treated as fatal.
 */
public final class ModResolver {
    private final Modrinth modrinth;

    public ModResolver(Modrinth modrinth) {
        this.modrinth = modrinth;
    }

    public record Planned(String slug, String title, String projectId, String versionId, String versionNumber,
                          String versionType, String url, String filename, String sha512, long size,
                          String license, String reason) {}

    public record Skipped(String slug, String reason) {}

    public record Plan(String gameVersion, List<Planned> mods, List<Skipped> skipped) {}

    /** Newest release (from {@code releasesNewestFirst}) that every required mod supports on Fabric. */
    public String newestSupportedGameVersion(List<ModCatalog.Entry> selected, List<String> releasesNewestFirst)
            throws IOException {
        List<Modrinth.Project> required = new ArrayList<>();
        for (ModCatalog.Entry e : selected) if (e.required) required.add(modrinth.project(e.slug));
        for (String v : releasesNewestFirst) {
            boolean all = true;
            for (Modrinth.Project p : required) {
                if (!p.game_versions.contains(v) || !p.loaders.contains("fabric")) {
                    all = false;
                    break;
                }
            }
            if (all) return v;
        }
        throw new IOException("No Minecraft release is supported by all required mods");
    }

    public Plan resolve(String gameVersion, List<ModCatalog.Entry> selected, List<List<String>> conflicts)
            throws IOException {
        // Keyed by project id, in priority order.
        Map<String, Planned> chosen = new LinkedHashMap<>();
        Map<String, Integer> priority = new LinkedHashMap<>();
        Map<String, Boolean> requiredById = new LinkedHashMap<>();
        Map<String, List<Modrinth.Dependency>> deps = new LinkedHashMap<>();
        List<Skipped> skipped = new ArrayList<>();

        int rank = 0;
        for (ModCatalog.Entry e : selected) {
            Modrinth.Project p = modrinth.project(e.slug);
            Modrinth.Version v = pick(modrinth.fabricVersions(p.id, gameVersion));
            if (v == null) {
                if (e.required) {
                    throw new IOException(e.name + " (required) has no Fabric build for Minecraft " + gameVersion);
                }
                skipped.add(new Skipped(e.slug, "no Fabric build for Minecraft " + gameVersion));
                continue;
            }
            chosen.put(p.id, planned(p, v, e.role));
            priority.put(p.id, rank++);
            requiredById.put(p.id, e.required);
            deps.put(p.id, v.dependencies);
        }

        // Required dependencies, iterated until stable (dependencies can have dependencies).
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String id : new ArrayList<>(chosen.keySet())) {
                for (Modrinth.Dependency d : deps.getOrDefault(id, List.of())) {
                    if (!"required".equals(d.dependency_type) || d.project_id == null || chosen.containsKey(d.project_id)) continue;
                    Modrinth.Project dp = modrinth.project(d.project_id);
                    Modrinth.Version dv = pick(modrinth.fabricVersions(dp.id, gameVersion));
                    if (dv == null) {
                        Planned owner = chosen.remove(id);
                        if (requiredById.getOrDefault(id, false)) {
                            throw new IOException(owner.title() + " needs " + dp.title + ", which has no build for " + gameVersion);
                        }
                        skipped.add(new Skipped(owner.slug(), "needs " + dp.title + ", which has no build for " + gameVersion));
                        break;
                    }
                    chosen.put(dp.id, planned(dp, dv, "dependency of " + chosen.get(id).title()));
                    priority.put(dp.id, priority.get(id)); // inherit the dependent's priority
                    requiredById.put(dp.id, requiredById.getOrDefault(id, false));
                    deps.put(dp.id, dv.dependencies);
                    changed = true;
                }
            }
        }

        // Declared incompatibilities.
        for (String id : new ArrayList<>(chosen.keySet())) {
            if (!chosen.containsKey(id)) continue;
            for (Modrinth.Dependency d : deps.getOrDefault(id, List.of())) {
                if ("incompatible".equals(d.dependency_type) && d.project_id != null && chosen.containsKey(d.project_id)) {
                    dropLowerPriority(id, d.project_id, chosen, priority, requiredById, skipped, "declares itself incompatible");
                }
            }
        }

        // Catalog-level conflicts (by slug).
        for (List<String> pair : conflicts) {
            if (pair.size() != 2) continue;
            String a = idOfSlug(chosen, pair.get(0)), b = idOfSlug(chosen, pair.get(1));
            if (a != null && b != null) {
                dropLowerPriority(a, b, chosen, priority, requiredById, skipped, "overlaps in function (catalog conflict)");
            }
        }

        return new Plan(gameVersion, List.copyOf(chosen.values()), List.copyOf(skipped));
    }

    private static void dropLowerPriority(String a, String b, Map<String, Planned> chosen, Map<String, Integer> priority,
                                          Map<String, Boolean> required, List<Skipped> skipped, String why) throws IOException {
        boolean ra = required.getOrDefault(a, false), rb = required.getOrDefault(b, false);
        if (ra && rb) {
            throw new IOException(chosen.get(a).title() + " and " + chosen.get(b).title() + " are both required but " + why);
        }
        String drop;
        if (ra) drop = b;
        else if (rb) drop = a;
        else drop = priority.get(a) > priority.get(b) ? a : b;
        String keep = drop.equals(a) ? b : a;
        Planned dropped = chosen.remove(drop);
        skipped.add(new Skipped(dropped.slug(), "conflicts with " + chosen.get(keep).title() + ": " + why));
    }

    private static String idOfSlug(Map<String, Planned> chosen, String slug) {
        for (Planned p : chosen.values()) if (p.slug().equals(slug)) return p.projectId();
        return null;
    }

    static Modrinth.Version pick(List<Modrinth.Version> newestFirst) {
        for (String type : new String[]{"release", "beta"}) {
            for (Modrinth.Version v : newestFirst) {
                if (type.equals(v.version_type) && v.loaders.contains("fabric") && v.primaryFile() != null
                        && v.primaryFile().hashes.get("sha512") != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private static Planned planned(Modrinth.Project p, Modrinth.Version v, String reason) {
        Modrinth.File f = v.primaryFile();
        String license = p.license == null ? "unknown" : (p.license.id != null ? p.license.id : p.license.name);
        return new Planned(p.slug, p.title, p.id, v.id, v.version_number, v.version_type, f.url, f.filename,
                f.hashes.get("sha512"), f.size, license, reason);
    }
}
