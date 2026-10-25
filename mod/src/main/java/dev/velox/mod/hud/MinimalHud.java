package dev.velox.mod.hud;

import dev.velox.core.config.VeloxConfig;
import dev.velox.mod.VeloxMod;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Minimal HUD mode: hides the vanilla HUD elements the player chose, like a partial F1.
 *
 * <p>Only elements that are clutter rather than gameplay information can be hidden. Health,
 * hunger, armor, hotbar, chat and boss bars are not offered, so the mode never hides something
 * the player needs.
 */
public final class MinimalHud {
    /** Element paths (Fabric's {@code VanillaHudElements}) that minimal mode may hide. */
    public static final List<String> HIDEABLE = List.of(
            "mob_effects", "held_item_tooltip", "experience_level", "overlay_message",
            "title_and_subtitle", "subtitles", "scoreboard");

    private MinimalHud() {}

    public static void register() {
        for (String path : HIDEABLE) {
            Identifier id = Identifier.withDefaultNamespace(path);
            HudElementRegistry.replaceElement(id, vanilla -> (graphics, delta) -> {
                VeloxConfig.Hud cfg = VeloxMod.config().hud;
                if (cfg.minimalHud && cfg.hiddenElements.contains(path)) return;
                vanilla.extractRenderState(graphics, delta);
            });
        }
    }
}
