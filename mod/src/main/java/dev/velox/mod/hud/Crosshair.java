package dev.velox.mod.hud;

import dev.velox.core.config.VeloxConfig;
import dev.velox.mod.VeloxMod;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Optional custom crosshair. It wraps the vanilla element rather than removing it: with the
 * custom crosshair off, vanilla draws exactly as before, attack indicator included. It is purely
 * cosmetic and shows nothing vanilla does not.
 */
public final class Crosshair {
    private Crosshair() {}

    public static HudElement wrap(HudElement vanilla) {
        return (graphics, delta) -> {
            VeloxConfig.Crosshair c = VeloxMod.config().crosshair;
            Minecraft mc = Minecraft.getInstance();
            if (!c.custom) {
                vanilla.extractRenderState(graphics, delta);
                return;
            }
            if (mc.gui.hud.isHidden() || !mc.options.getCameraType().isFirstPerson()) return;
            draw(graphics, c);
        };
    }

    static void draw(GuiGraphicsExtractor g, VeloxConfig.Crosshair c) {
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;
        int t = c.thickness;
        int lo = t / 2;          // pixels before the center line
        int hi = t - lo;         // pixels after it
        switch (c.style) {
            case CROSS -> {
                bar(g, c, cx - lo, cy - c.gap - c.length, cx + hi, cy - c.gap);        // top
                bar(g, c, cx - lo, cy + c.gap + 1, cx + hi, cy + c.gap + 1 + c.length); // bottom
                bar(g, c, cx - c.gap - c.length, cy - lo, cx - c.gap, cy + hi);        // left
                bar(g, c, cx + c.gap + 1, cy - lo, cx + c.gap + 1 + c.length, cy + hi); // right
            }
            case T_SHAPE -> {
                bar(g, c, cx - lo, cy + c.gap + 1, cx + hi, cy + c.gap + 1 + c.length);
                bar(g, c, cx - c.gap - c.length, cy - lo, cx - c.gap, cy + hi);
                bar(g, c, cx + c.gap + 1, cy - lo, cx + c.gap + 1 + c.length, cy + hi);
            }
            case CIRCLE -> {
                int r = c.gap + c.length / 2 + 1;
                // Midpoint circle: one small fill per plotted point (at most ~8r fills).
                int x = r, y = 0, err = 1 - r;
                while (x >= y) {
                    plot8(g, c, cx, cy, x, y);
                    y++;
                    if (err < 0) err += 2 * y + 1;
                    else { x--; err += 2 * (y - x) + 1; }
                }
            }
            case DOT -> { }
        }
        if (c.centerDot || c.style == VeloxConfig.Crosshair.Style.DOT) {
            bar(g, c, cx - lo, cy - lo, cx + hi, cy + hi);
        }
    }

    private static void plot8(GuiGraphicsExtractor g, VeloxConfig.Crosshair c, int cx, int cy, int x, int y) {
        int[][] pts = {{x, y}, {y, x}, {-y, x}, {-x, y}, {-x, -y}, {-y, -x}, {y, -x}, {x, -y}};
        for (int[] p : pts) bar(g, c, cx + p[0], cy + p[1], cx + p[0] + 1, cy + p[1] + 1);
    }

    private static void bar(GuiGraphicsExtractor g, VeloxConfig.Crosshair c, int x1, int y1, int x2, int y2) {
        if (c.outline) g.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, c.outlineColor);
        g.fill(x1, y1, x2, y2, c.color);
    }
}
