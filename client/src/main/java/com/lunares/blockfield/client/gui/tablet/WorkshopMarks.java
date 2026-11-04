package com.lunares.blockfield.client.gui.tablet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lunares.blockfield.client.gui.BfTheme;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Workshop map markup from the server's UI packet ({@code map.workshopMarks}, see blockfield-mod
 * MarkupEdits.marks): capture points with their zones, bases, base spawn pads, team lobbies and
 * deathmatch spawns, drawn on the tablet map and the minimap. Only present in the workshop profile.
 */
public final class WorkshopMarks {
    /** box -- охватывающий прямоугольник зоны точки, boxes -- её коробки (зона сложной формы). */
    public record Mark(
            String kind, String name, String team, double x, double z, int[] box, int[][] boxes) {}

    private WorkshopMarks() {}

    public static boolean active(JsonObject map) {
        return map != null && map.has("workshop") && map.get("workshop").getAsBoolean();
    }

    public static List<Mark> of(JsonObject map) {
        List<Mark> marks = new ArrayList<>();
        if (map == null || !map.has("workshopMarks") || !map.get("workshopMarks").isJsonArray())
            return marks;
        for (JsonElement e : map.getAsJsonArray("workshopMarks")) {
            if (!e.isJsonObject()) continue;
            JsonObject m = e.getAsJsonObject();
            int[] box = com.lunares.blockfield.client.render.PointMarkerRenderer.box4(m.get("box"));
            int[][] boxes =
                    com.lunares.blockfield.client.render.PointMarkerRenderer.boxes(m.get("boxes"));
            if (boxes == null && box != null) boxes = new int[][] {box};
            marks.add(
                    new Mark(
                            m.get("k").getAsString(),
                            m.has("n") ? m.get("n").getAsString() : null,
                            m.has("t") ? m.get("t").getAsString() : null,
                            m.get("x").getAsDouble(),
                            m.get("z").getAsDouble(),
                            box,
                            boxes));
        }
        return marks;
    }

    public static int color(Mark mark) {
        if (mark.kind().equals("point")) return BfTheme.AMBER;
        if (mark.kind().equals("dm")) return BfTheme.TEXT;
        if (mark.kind().equals("lobby")) return 0xFFA855F7;
        if (mark.kind().equals("training")) return 0xFF22C55E;
        if (mark.kind().equals("target")) return BfTheme.AMBER;
        return TabletMapTab.teamColor(mark.team());
    }

    /**
     * One mark centred at (x, y); {@code big} adds the label (tablet map), the minimap draws the
     * glyph only.
     */
    public static void draw(GuiGraphics g, Mark mark, int x, int y, int alpha, boolean big) {
        int color = BfTheme.alpha(color(mark), alpha);
        int shade = BfTheme.alpha(BfTheme.BG, alpha * 0xD0 / 255);
        switch (mark.kind()) {
            case "point" -> {
                g.fill(x - 4, y - 4, x + 4, y + 4, shade);
                g.fill(x - 3, y - 3, x + 3, y + 3, color);
            }
            case "base" -> TabletMapTab.drawBase(g, x, y, color);
            case "lobby" -> {
                g.fill(x - 3, y - 3, x + 3, y + 3, shade);
                BfTheme.outline(g, x - 3, y - 3, 6, 6, color);
            }
            case "dm" -> {
                g.fill(x - 2, y, x + 3, y + 1, color);
                g.fill(x, y - 2, x + 1, y + 3, color);
            }
            default -> g.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
        if (big && mark.name() != null) {
            var font = BfTheme.font();
            int width = font.width(mark.name());
            g.fill(x - width / 2 - 2, y + 6, x + width / 2 + 2, y + 16, shade);
            g.drawString(font, mark.name(), x - width / 2, y + 7, color, false);
        }
    }

    /** Name of another member for a marker label, from the tab list; null when unknown. */
    public static String playerName(UUID id) {
        var connection = Minecraft.getInstance().getConnection();
        var info = connection == null || id == null ? null : connection.getPlayerInfo(id);
        return info == null ? null : info.getProfile().getName();
    }

    /**
     * Grid step in blocks: the smallest of 16/64/256 whose lines are at least {@code minPx} apart
     * on screen.
     */
    public static int gridStep(double pixelsPerBlock, int minPx) {
        for (int step : new int[] {16, 64, 256}) if (step * pixelsPerBlock >= minPx) return step;
        return 0;
    }
}
