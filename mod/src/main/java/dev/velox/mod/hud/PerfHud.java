package dev.velox.mod.hud;

import dev.velox.core.bench.RollingFrameStats;
import dev.velox.core.config.VeloxConfig;
import dev.velox.mod.FrameClock;
import dev.velox.mod.VeloxMod;
import dev.velox.mod.bench.BenchmarkController;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.Locale;

/**
 * FPS, frame time, 1%/0.1% lows, memory, ping and an optional frame-time graph.
 *
 * <p>Text is rebuilt only when the underlying numbers refresh (4x per second), so steady-state
 * rendering formats no strings. The graph draws one fill per column, from the frame ring buffer.
 */
public final class PerfHud implements HudElement {
    public static final PerfHud INSTANCE = new PerfHud();

    private static final int GRAPH_W = 120;
    private static final int GRAPH_H = 30;
    private final String[] lines = new String[6];
    private int lineCount;
    private long lastBuild;

    private PerfHud() {}

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        VeloxConfig.Hud cfg = VeloxMod.config().hud;
        if (!cfg.enabled || mc.gui.hud.isHidden()) return;

        long now = System.nanoTime();
        if (now - lastBuild > 250_000_000L) {
            lastBuild = now;
            build(mc, cfg);
        }
        Font font = mc.font;
        int width = 0;
        for (int i = 0; i < lineCount; i++) width = Math.max(width, font.width(lines[i]));
        if (cfg.graph) width = Math.max(width, GRAPH_W);
        int lineH = font.lineHeight + 1;
        int height = lineCount * lineH + (cfg.graph ? GRAPH_H + 3 : 0);

        int pad = 3;
        boolean right = cfg.corner == VeloxConfig.Corner.TOP_RIGHT || cfg.corner == VeloxConfig.Corner.BOTTOM_RIGHT;
        boolean bottom = cfg.corner == VeloxConfig.Corner.BOTTOM_LEFT || cfg.corner == VeloxConfig.Corner.BOTTOM_RIGHT;
        int x = right ? graphics.guiWidth() - width - pad - 2 : pad + 2;
        int y = bottom ? graphics.guiHeight() - height - pad - 2 : pad + 2;

        if (cfg.background) graphics.fill(x - pad, y - pad, x + width + pad, y + height + pad - 1, 0x90000000);
        for (int i = 0; i < lineCount; i++) {
            graphics.text(font, lines[i], x, y + i * lineH, cfg.color, false);
        }
        if (cfg.graph) drawGraph(graphics, x, y + lineCount * lineH + 2);
    }

    private void build(Minecraft mc, VeloxConfig.Hud cfg) {
        RollingFrameStats s = FrameClock.HUD_STATS;
        int n = 0;
        BenchmarkController bench = BenchmarkController.INSTANCE;
        if (bench.statusLine() != null) lines[n++] = bench.statusLine();
        if (cfg.fps) lines[n++] = String.format(Locale.ROOT, "%.0f FPS", s.avgFps());
        if (cfg.frameTime) lines[n++] = String.format(Locale.ROOT, "%.2f ms  (max %.1f)", s.lastFrameMs(), s.maxFrameMs());
        if (cfg.lows) lines[n++] = String.format(Locale.ROOT, "1%% %.0f  0.1%% %.0f", s.low1PctFps(), s.low01PctFps());
        if (cfg.memory) {
            Runtime rt = Runtime.getRuntime();
            long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
            lines[n++] = String.format(Locale.ROOT, "Mem %d / %d MiB", used, rt.maxMemory() >> 20);
        }
        if (cfg.ping) {
            int ping = ping(mc);
            if (ping >= 0) lines[n++] = ping + " ms ping";
        }
        lineCount = n;
    }

    /** Latency the server reports for this player; -1 in singleplayer or when unknown. */
    private static int ping(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null || mc.getSingleplayerServer() != null) return -1;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    private static void drawGraph(GuiGraphicsExtractor g, int x, int y) {
        RollingFrameStats s = FrameClock.HUD_STATS;
        g.fill(x, y, x + GRAPH_W, y + GRAPH_H, 0x60000000);
        // Reference line at 16.7 ms (60 FPS); bars scale so 33 ms fills the graph.
        int ref = y + GRAPH_H - (int) (GRAPH_H * 16.7 / 33.3);
        g.fill(x, ref, x + GRAPH_W, ref + 1, 0x60FFFFFF);
        for (int i = 0; i < GRAPH_W; i++) {
            long ns = s.frameAgo(GRAPH_W - 1 - i);
            if (ns == 0) continue;
            double ms = ns / 1e6;
            int h = (int) Math.min(GRAPH_H, Math.max(1, ms / 33.3 * GRAPH_H));
            int color = ms < 8.4 ? 0xFF4ADE80 : ms < 16.7 ? 0xFFFACC15 : 0xFFF87171;
            g.fill(x + i, y + GRAPH_H - h, x + i + 1, y + GRAPH_H, color);
        }
    }
}
