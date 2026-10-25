package dev.velox.launcher.mojang;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands a version's argument templates ({@code ${auth_player_name}} etc.) for a platform. */
public final class ArgumentBuilder {
    private static final Pattern VAR = Pattern.compile("\\$\\{([a-zA-Z_]+)}");
    private static final Gson GSON = new Gson();

    private ArgumentBuilder() {}

    /**
     * @param warn receives the names of placeholders with no value; they expand to an empty
     *             string so a newly added Mojang variable cannot block launching.
     */
    public static List<String> expand(JsonArray template, Platform platform, Map<String, String> vars,
                                      Consumer<String> warn) {
        List<String> out = new ArrayList<>();
        if (template == null) return out;
        for (JsonElement e : template) {
            if (e.isJsonPrimitive()) {
                out.add(substitute(e.getAsString(), vars, warn));
            } else if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                List<VersionInfo.Rule> rules = o.has("rules")
                        ? GSON.fromJson(o.get("rules"), new TypeToken<List<VersionInfo.Rule>>() {}.getType())
                        : null;
                if (!platform.allows(rules)) continue;
                JsonElement value = o.get("value");
                if (value == null) continue;
                if (value.isJsonArray()) {
                    for (JsonElement v : value.getAsJsonArray()) out.add(substitute(v.getAsString(), vars, warn));
                } else {
                    out.add(substitute(value.getAsString(), vars, warn));
                }
            }
        }
        return out;
    }

    static String substitute(String s, Map<String, String> vars, Consumer<String> warn) {
        Matcher m = VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            if (v == null) {
                warn.accept(m.group(1));
                v = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
