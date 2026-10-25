package dev.velox.mod;

import com.mojang.blaze3d.platform.InputConstants;
import dev.velox.core.config.Preset;
import dev.velox.core.config.VeloxConfig;
import dev.velox.mod.bench.BenchmarkController;
import dev.velox.mod.hud.Crosshair;
import dev.velox.mod.hud.MinimalHud;
import dev.velox.mod.hud.PerfHud;
import dev.velox.mod.options.PresetApplier;
import dev.velox.mod.screen.VeloxScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.sdl.SDLScancode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Client entrypoint. Velox adds measurement, presets and HUD features; it does not replace any
 * renderer. Rendering and logic optimizations come from Sodium, Lithium and the other mods the
 * launcher installs, which are faster than anything worth rewriting here.
 */
public final class VeloxMod implements ClientModInitializer {
    public static final String MOD_ID = "velox";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static VeloxConfig config = new VeloxConfig();
    private static Path configPath;
    private static boolean presetPending;

    private static KeyMapping openMenu;
    private static KeyMapping toggleHud;
    private static KeyMapping benchmark;
    private static KeyMapping cyclePreset;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static VeloxConfig config() {
        return config;
    }

    /** Saves the config; failures are logged, never thrown into the game loop. */
    public static void saveConfig() {
        try {
            config.save(configPath);
        } catch (IOException e) {
            LOG.warn("Could not save {}: {}", configPath, e.toString());
        }
    }

    @Override
    public void onInitializeClient() {
        configPath = VeloxConfig.path(FabricLoader.getInstance().getGameDir());
        try {
            config = VeloxConfig.load(configPath);
        } catch (IOException e) {
            LOG.warn("Could not read {}, using defaults: {}", configPath, e.toString());
        }
        presetPending = config.applyPresetOnStart;

        KeyMapping.Category category = KeyMapping.Category.register(id("velox"));
        openMenu = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.velox.open_menu", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_RSHIFT, category));
        toggleHud = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.velox.toggle_hud", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_F7, category));
        benchmark = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.velox.benchmark", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_F8, category));
        cyclePreset = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.velox.cycle_preset", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_UNKNOWN, category));

        HudElementRegistry.addLast(id("perf_hud"), PerfHud.INSTANCE);
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, Crosshair::wrap);
        MinimalHud.register();

        ClientTickEvents.END_CLIENT_TICK.register(VeloxMod::tick);
        LOG.info("Velox ready (preset {}, profile {})", config.preset.displayName, System.getProperty("velox.profile", "unknown"));
    }

    private static void tick(Minecraft mc) {
        if (presetPending && mc.level != null) {
            // Applied once the world exists, so renderer reloads hit a live renderer.
            presetPending = false;
            PresetApplier.apply(mc, config, true);
            config.applyPresetOnStart = false;
            saveConfig();
        }
        while (openMenu.consumeClick()) {
            mc.gui.setScreen(new VeloxScreen(null));
        }
        while (toggleHud.consumeClick()) {
            config.hud.enabled = !config.hud.enabled;
            saveConfig();
        }
        while (benchmark.consumeClick()) {
            BenchmarkController.INSTANCE.toggle(mc);
        }
        while (cyclePreset.consumeClick()) {
            Preset[] all = {Preset.MAX_FPS, Preset.COMPETITIVE, Preset.BALANCED};
            int i = 0;
            while (i < all.length && all[i] != config.preset) i++;
            config.preset = all[(i + 1) % all.length];
            PresetApplier.apply(mc, config, true);
            saveConfig();
            message(mc, "Preset: " + config.preset.displayName);
        }
        BenchmarkController.INSTANCE.tick(mc);
    }

    public static void message(Minecraft mc, String text) {
        if (mc.gui != null && mc.gui.hud != null) {
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Velox] " + text));
        }
    }
}
