package dev.velox.mod.screen;

import dev.velox.core.config.GameSettings;
import dev.velox.core.config.Preset;
import dev.velox.core.config.PresetEngine;
import dev.velox.core.config.VeloxConfig;
import dev.velox.mod.VeloxMod;
import dev.velox.mod.bench.BenchmarkController;
import dev.velox.mod.hud.MinimalHud;
import dev.velox.mod.options.PresetApplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Velox settings, in four tabs. Built from vanilla buttons only: no custom rendering pipeline,
 * no per-frame allocation beyond what vanilla widgets do. With animations off (the default)
 * it appears instantly.
 */
public final class VeloxScreen extends Screen {
    private enum Tab { PRESETS, HUD, CROSSHAIR, BENCHMARK }

    private static final int[] COLORS = {0xFFFFFFFF, 0xFF4ADE80, 0xFF22D3EE, 0xFFF87171, 0xFFFACC15, 0xFFE879F9, 0xFF000000};
    private static final String[] COLOR_NAMES = {"White", "Green", "Cyan", "Red", "Yellow", "Magenta", "Black"};

    private final @Nullable Screen parent;
    private Tab tab = Tab.PRESETS;
    private final List<Runnable> relabel = new ArrayList<>();
    private long openedAt;

    public VeloxScreen(@Nullable Screen parent) {
        super(Component.literal("Velox"));
        this.parent = parent;
    }

    private VeloxConfig cfg() {
        return VeloxMod.config();
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();
        relabel.clear();
        int tabW = 80, gap = 4;
        int tx = this.width / 2 - (4 * tabW + 3 * gap) / 2;
        for (Tab t : Tab.values()) {
            String name = t.name().charAt(0) + t.name().substring(1).toLowerCase(Locale.ROOT);
            Button b = this.addRenderableWidget(Button.builder(Component.literal(name), btn -> switchTab(t))
                    .pos(tx, 22).size(tabW, 20).build());
            b.active = t != tab;
            tx += tabW + gap;
        }
        Grid g = new Grid(this.width / 2 - 155, 52);
        switch (tab) {
            case PRESETS -> presets(g);
            case HUD -> hud(g);
            case CROSSHAIR -> crosshair(g);
            case BENCHMARK -> benchmark(g);
        }
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .pos(this.width / 2 - 75, this.height - 28).size(150, 20).build());
    }

    private void switchTab(Tab t) {
        tab = t;
        this.clearWidgets();
        this.init();
    }

    /** Two-column layout helper. */
    private final class Grid {
        final int x0;
        int y;
        int col;

        Grid(int x0, int y) {
            this.x0 = x0;
            this.y = y;
        }

        Button add(Supplier<String> label, Runnable onPress, String tooltip) {
            int x = x0 + col * 160;
            Button b = Button.builder(Component.literal(label.get()), btn -> {
                onPress.run();
                VeloxMod.saveConfig();
                relabel.forEach(Runnable::run);
            }).pos(x, y).size(150, 20).build();
            if (tooltip != null) b.setTooltip(Tooltip.create(Component.literal(tooltip)));
            relabel.add(() -> b.setMessage(Component.literal(label.get())));
            VeloxScreen.this.addRenderableWidget(b);
            if (++col == 2) newRow();
            return b;
        }

        void newRow() {
            if (col != 0) {
                col = 0;
                y += 24;
            }
        }
    }

    private static String onOff(String name, boolean v) {
        return name + ": " + (v ? "On" : "Off");
    }

    // --- tabs -----------------------------------------------------------------------------------

    private void presets(Grid g) {
        for (Preset p : Preset.values()) {
            g.add(() -> (cfg().preset == p ? "> " : "") + p.displayName, () -> {
                cfg().preset = p;
                PresetApplier.apply(this.minecraft, cfg(), true);
            }, switch (p) {
                case MAX_FPS -> "Highest frame rate. Shorter view distance, no clouds, opaque leaves, no smooth lighting.";
                case COMPETITIVE -> "Stable frame times and clear visibility. Full entity distance, no clouds or fade-in.";
                case BALANCED -> "Most of the vanilla look at a good frame rate.";
                case CUSTOM -> "Balanced plus your overrides below.";
            });
        }
        if (cfg().preset == Preset.CUSTOM) {
            GameSettings c = cfg().custom;
            Supplier<GameSettings> eff = () -> PresetEngine.resolve(Preset.CUSTOM, PresetApplier.hardware(), cfg().custom);
            g.add(() -> "Render distance: " + eff.get().renderDistance, () -> c.renderDistance = cycle(eff.get().renderDistance, 4, 32, 2), null);
            g.add(() -> "Simulation: " + eff.get().simulationDistance, () -> c.simulationDistance = cycle(eff.get().simulationDistance, 5, 16, 1), null);
            g.add(() -> "Particles: " + eff.get().particles, () -> c.particles = next(eff.get().particles, GameSettings.Particles.values()), null);
            g.add(() -> "Clouds: " + eff.get().clouds, () -> c.clouds = next(eff.get().clouds, GameSettings.Clouds.values()), null);
            g.add(() -> "Biome blend: " + eff.get().biomeBlendRadius, () -> c.biomeBlendRadius = cycle(eff.get().biomeBlendRadius, 0, 7, 1),
                    "0 is fastest; blending adds work to every chunk rebuild");
            g.add(() -> onOff("Smooth lighting", eff.get().ambientOcclusion), () -> c.ambientOcclusion = !eff.get().ambientOcclusion, null);
            g.add(() -> onOff("Entity shadows", eff.get().entityShadows), () -> c.entityShadows = !eff.get().entityShadows, null);
            g.add(() -> onOff("Fancy leaves", eff.get().cutoutLeaves), () -> c.cutoutLeaves = !eff.get().cutoutLeaves, "Off renders leaves opaque (less overdraw)");
            g.add(() -> "Mipmaps: " + eff.get().mipmapLevels, () -> c.mipmapLevels = cycle(eff.get().mipmapLevels, 0, 4, 1), null);
            g.add(() -> "Entity distance: " + Math.round(eff.get().entityDistanceScaling * 100) + "%",
                    () -> c.entityDistanceScaling = eff.get().entityDistanceScaling >= 2.0 ? 0.5 : eff.get().entityDistanceScaling + 0.25, null);
            g.add(() -> "Apply custom settings", () -> PresetApplier.apply(this.minecraft, cfg(), true), null);
        }
        g.newRow();
        g.add(() -> onOff("Menu animations", cfg().ui.animations), () -> cfg().ui.animations = !cfg().ui.animations,
                "Fade-in for this screen. Off by default.");
    }

    private void hud(Grid g) {
        VeloxConfig.Hud h = cfg().hud;
        g.add(() -> onOff("Performance HUD", h.enabled), () -> h.enabled = !h.enabled, "Toggle in game with F7");
        g.add(() -> "Corner: " + h.corner.name().toLowerCase(Locale.ROOT).replace('_', ' '), () -> h.corner = next(h.corner, VeloxConfig.Corner.values()), null);
        g.add(() -> onOff("FPS", h.fps), () -> h.fps = !h.fps, null);
        g.add(() -> onOff("Frame time", h.frameTime), () -> h.frameTime = !h.frameTime, null);
        g.add(() -> onOff("1% / 0.1% lows", h.lows), () -> h.lows = !h.lows, "Average FPS of the slowest 1% and 0.1% of recent frames");
        g.add(() -> onOff("Memory", h.memory), () -> h.memory = !h.memory, null);
        g.add(() -> onOff("Ping", h.ping), () -> h.ping = !h.ping, "Multiplayer only");
        g.add(() -> onOff("Frame-time graph", h.graph), () -> h.graph = !h.graph, "Green < 8.3 ms, yellow < 16.7 ms, red above");
        g.add(() -> onOff("Background", h.background), () -> h.background = !h.background, null);
        g.add(() -> onOff("Minimal HUD", h.minimalHud), () -> h.minimalHud = !h.minimalHud,
                "Hides the clutter elements below; never health, hunger, armor, hotbar or chat");
        g.newRow();
        for (String el : MinimalHud.HIDEABLE) {
            String name = el.replace('_', ' ');
            g.add(() -> (h.hiddenElements.contains(el) ? "Hide: " : "Show: ") + name, () -> {
                if (!h.hiddenElements.remove(el)) h.hiddenElements.add(el);
            }, "Applies when Minimal HUD is on");
        }
    }

    private void crosshair(Grid g) {
        VeloxConfig.Crosshair c = cfg().crosshair;
        g.add(() -> onOff("Custom crosshair", c.custom), () -> c.custom = !c.custom, "Off = vanilla crosshair, unchanged");
        g.add(() -> "Style: " + c.style.name().toLowerCase(Locale.ROOT).replace('_', ' '), () -> c.style = next(c.style, VeloxConfig.Crosshair.Style.values()), null);
        g.add(() -> "Length: " + c.length, () -> c.length = cycle(c.length, 1, 16, 1), null);
        g.add(() -> "Thickness: " + c.thickness, () -> c.thickness = cycle(c.thickness, 1, 4, 1), null);
        g.add(() -> "Gap: " + c.gap, () -> c.gap = cycle(c.gap, 0, 10, 1), null);
        g.add(() -> "Color: " + colorName(c.color), () -> c.color = COLORS[(indexOf(c.color) + 1) % COLORS.length], null);
        g.add(() -> onOff("Outline", c.outline), () -> c.outline = !c.outline, null);
        g.add(() -> onOff("Center dot", c.centerDot), () -> c.centerDot = !c.centerDot, null);
    }

    private void benchmark(Grid g) {
        VeloxConfig.Benchmark b = cfg().benchmark;
        g.add(() -> "Scenario: " + b.scenario, () -> b.scenario = switch (b.scenario) {
            case "static" -> "flight";
            case "flight" -> "free";
            default -> "static";
        }, "static: stand still · flight: auto-fly forward (singleplayer) · free: your own route");
        g.add(() -> "Duration: " + b.durationSeconds + "s", () -> b.durationSeconds = b.durationSeconds >= 180 ? 30 : b.durationSeconds + 30, null);
        g.add(() -> "Warm-up: " + b.warmupSeconds + "s", () -> b.warmupSeconds = b.warmupSeconds >= 30 ? 0 : b.warmupSeconds + 5,
                "Excluded from results: lets chunks load and the JIT warm up");
        g.add(() -> BenchmarkController.INSTANCE.running() ? "Stop benchmark" : "Start benchmark", () -> {
            this.onClose();
            BenchmarkController.INSTANCE.toggle(this.minecraft);
        }, "Also bound to F8. Results go to velox-benchmarks/ in the game folder");
    }

    // --- helpers --------------------------------------------------------------------------------

    private static int cycle(int v, int min, int max, int step) {
        return v + step > max ? min : v + step;
    }

    private static <E> E next(E current, E[] all) {
        for (int i = 0; i < all.length; i++) if (all[i] == current) return all[(i + 1) % all.length];
        return all[0];
    }

    private static int indexOf(int color) {
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == color) return i;
        return -1;
    }

    private static String colorName(int color) {
        int i = indexOf(color);
        return i >= 0 ? COLOR_NAMES[i] : String.format(Locale.ROOT, "#%08X", color);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        this.extractBackground(graphics, mouseX, mouseY, delta);
        if (cfg().ui.animations) {
            // Fade the content in over 150 ms by covering it with a fading overlay.
            float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 150f);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            int alpha = (int) ((1f - t) * 200);
            if (alpha > 0) graphics.fill(0, 0, this.width, this.height, alpha << 24);
        } else {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
        }
        graphics.centeredText(this.font, "Velox", this.width / 2, 8, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        VeloxMod.saveConfig();
        this.minecraft.gui.setScreen(this.parent);
    }
}
