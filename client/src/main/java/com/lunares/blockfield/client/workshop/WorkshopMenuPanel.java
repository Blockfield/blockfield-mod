package com.lunares.blockfield.client.workshop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.tablet.Tablet;
import com.lunares.blockfield.client.gui.tablet.TabletFrame;
import com.lunares.blockfield.client.gui.tablet.TabletMapTab;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Server-authored lists and markup use a bounded sheet with paging, never the tablet canvas. */
public final class WorkshopMenuPanel extends Screen {
    private final JsonArray entries;
    private final boolean maps;
    private WorkshopAiLayout layout;
    private boolean mapView;
    private boolean panning;
    private int page;
    private String confirm = "";

    private WorkshopMenuPanel(String title, JsonArray entries, boolean maps) {
        super(Component.literal(title));
        this.entries = entries;
        this.maps = maps;
    }

    static WorkshopMenuPanel entries(String title, JsonArray entries, boolean maps) {
        return new WorkshopMenuPanel(
                title, maps ? grouped(entries, specialCaption()) : entries, maps);
    }

    private static String specialCaption() {
        try {
            return Component.translatable("blockfield.workshop.maps.special").getString();
        } catch (RuntimeException empty) {
            return "Особые";
        }
    }

    /** Ordinary maps first, then the special ones (limbo, lobby, training) under a caption. */
    static JsonArray grouped(JsonArray entries, String caption) {
        var ordinary = new JsonArray();
        var special = new JsonArray();
        for (var element : entries) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.has("special") && entry.get("special").getAsBoolean()) special.add(entry);
            else ordinary.add(entry);
        }
        if (special.isEmpty() || ordinary.isEmpty()) {
            ordinary.addAll(special);
            return ordinary;
        }
        var marker = new JsonObject();
        marker.addProperty("label", caption);
        marker.addProperty("enabled", false);
        ordinary.add(marker);
        ordinary.addAll(special);
        return ordinary;
    }

    /** Group caption: a disabled marker without an action, never a clickable row. */
    static boolean isCaption(JsonObject entry) {
        return !entry.has("action");
    }

    /** Explicit server version for the icon tooltip; empty when the server sent none. */
    static String versionOf(JsonObject entry) {
        return entry.has("version") ? entry.get("version").getAsString() : "";
    }

    /**
     * Status line under the map name: the server hint minus the version the icon tooltip shows.
     * Older servers without the property keep their full hint.
     */
    static String statusOf(JsonObject entry) {
        String hint = WorkshopRadialScreen.str(entry, "hint");
        String version = versionOf(entry);
        if (!version.isEmpty()) {
            if (hint.equals(version)) return "";
            String suffix = " · " + version;
            if (hint.endsWith(suffix)) return hint.substring(0, hint.length() - suffix.length());
        }
        return hint;
    }

    static WorkshopMenuPanel menu(JsonObject menu) {
        JsonArray rows = new JsonArray();
        for (var section : menu.getAsJsonArray("sections")) {
            var obj = section.getAsJsonObject();
            if (obj.has("label")) rows.add(caption(obj.get("label").getAsString()));
            if (obj.has("lines"))
                for (var line : obj.getAsJsonArray("lines")) rows.add(caption(line.getAsString()));
            if (obj.has("buttons"))
                for (var button : obj.getAsJsonArray("buttons")) rows.add(button);
        }
        return entries(WorkshopRadialScreen.str(menu, "title"), rows, false);
    }

    private static JsonObject caption(String text) {
        var row = new JsonObject();
        row.addProperty("label", text);
        row.addProperty("enabled", false);
        return row;
    }

    @Override
    protected void init() {
        font = BfTheme.font();
        layout = WorkshopAiLayout.fit(width, height);
    }

    static final int LIST_TOP = 48;

    /** Row height: a 20 px button plus its status wrapped to one or two lines. */
    private int rowHeight(int index) {
        JsonObject entry = entries.get(index).getAsJsonObject();
        if (maps && isCaption(entry)) return 18;
        String hint = maps ? statusOf(entry) : WorkshopRadialScreen.hint(entry);
        var lines = hintLines(hint);
        // Map rows pack tightly once the version moves into the icon tooltip; other sheets keep
        // their established rhythm.
        if (!maps) return 20 + 10 * Math.clamp(lines.size(), 1, 2);
        return 20 + (lines.isEmpty() ? 0 : 10 * Math.clamp(lines.size(), 1, 2));
    }

    private java.util.List<String> hintLines(String hint) {
        return hint.isEmpty()
                ? java.util.List.of()
                : BfTheme.wrap(font, hint, layout.width() - 32, 2).lines();
    }

    /** First entry index of every page; rows vary in height, so pages are packed greedily. */
    private java.util.List<Integer> pageStarts() {
        int[] heights = new int[entries.size()];
        boolean[] keep = new boolean[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            heights[i] = rowHeight(i);
            // A group caption never strands at a page bottom: it breaks together with its row.
            keep[i] = maps && isCaption(entries.get(i).getAsJsonObject()) && i + 1 < entries.size();
        }
        return pageBreaks(heights, keep, layout.height() - 34 - LIST_TOP);
    }

    /**
     * Greedy page breaks over row heights; a flagged row starts a fresh page together with the row
     * after it instead of dangling alone at the bottom.
     */
    static java.util.List<Integer> pageBreaks(int[] heights, boolean[] keep, int room) {
        var starts = new java.util.ArrayList<Integer>();
        int used = room;
        for (int i = 0; i < heights.length; i++) {
            int h = heights[i] + (keep[i] && i + 1 < heights.length ? heights[i + 1] : 0);
            if (used + h > room) {
                starts.add(i);
                used = 0;
            }
            used += heights[i];
        }
        if (starts.isEmpty()) starts.add(0);
        return starts;
    }

    private int pages() {
        return pageStarts().size();
    }

    /** {index, top} of each row on the current page. */
    private java.util.List<int[]> pageRows() {
        var starts = pageStarts();
        int from = starts.get(Math.min(page, starts.size() - 1));
        int to = page + 1 < starts.size() ? starts.get(page + 1) : entries.size();
        var rows = new java.util.ArrayList<int[]>();
        int top = LIST_TOP;
        for (int i = from; i < to; i++) {
            rows.add(new int[] {i, top});
            top += rowHeight(i);
        }
        return rows;
    }

    private TabletFrame mapFrame() {
        return new TabletFrame(12, 48, layout.width() - 24, layout.height() - 82, 12, 24);
    }

    @Override
    public void tick() {
        Tablet.passThroughMovement(minecraft);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float dt) {}

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        double x = layout.localX(mx), y = layout.localY(my);
        g.pose().pushPose();
        g.pose().translate(layout.x(), layout.y(), 0);
        g.pose().scale(layout.scale(), layout.scale(), 1);
        BfTheme.panel(g, 0, 0, layout.width(), layout.height(), true);
        BfTheme.ticks(g, 0, 0, layout.width(), layout.height(), 6, BfTheme.AMBER);
        BfTheme.header(g, font, 12, 12, layout.width() - 24, title.getString(), "");
        // Version tooltip, drawn in screen space after the scaled sheet below.
        java.util.List<net.minecraft.network.chat.Component> tip = null;
        if (maps)
            BfTheme.button(
                    g,
                    font,
                    12,
                    24,
                    layout.width() - 24,
                    20,
                    Component.translatable(
                                    mapView
                                            ? "blockfield.workshop.radial.maps"
                                            : "blockfield.workshop.radial.map")
                            .getString(),
                    BfTheme.hit((int) x, (int) y, 12, 24, layout.width() - 24, 20),
                    TabletMapTab.available(),
                    BfTheme.Style.SECONDARY);
        if (mapView) {
            Tablet.withScissorShift(() -> TabletMapTab.render(g, mapFrame(), (int) x, (int) y));
            g.drawString(
                    font,
                    Component.translatable("blockfield.workshop.radial.map_hint"),
                    12,
                    layout.height() - 20,
                    BfTheme.MUTED,
                    false);
        } else {
            for (int[] row : pageRows()) {
                JsonObject entry = entries.get(row[0]).getAsJsonObject();
                int top = row[1];
                if (maps && isCaption(entry)) {
                    BfTheme.header(
                            g,
                            font,
                            12,
                            top + 4,
                            layout.width() - 24,
                            entry.get("label").getAsString(),
                            "");
                    continue;
                }
                boolean enabled = WorkshopRadialScreen.enabled(entry);
                String action = WorkshopRadialScreen.str(entry, "action");
                String label =
                        confirm.equals(action) && !action.isEmpty()
                                ? Component.translatable("blockfield.workshop.radial.confirm")
                                        .getString()
                                : WorkshopRadialScreen.label(entry);
                boolean selected = entry.has("selected") && entry.get("selected").getAsBoolean();
                String version = maps ? versionOf(entry) : "";
                int labelW = layout.width() - 24 - (version.isEmpty() ? 0 : 22);
                BfTheme.button(
                        g,
                        font,
                        12,
                        top,
                        labelW,
                        20,
                        label,
                        BfTheme.hit((int) x, (int) y, 12, top, labelW, 20),
                        enabled,
                        confirm.equals(action) && !action.isEmpty()
                                ? BfTheme.Style.DESTRUCTIVE
                                : selected ? BfTheme.Style.PRIMARY : BfTheme.Style.SECONDARY);
                if (!version.isEmpty()) {
                    // Small version button: the raw version leaves the list, its hover tooltip
                    // shows it (plus the status when the map carries one).
                    int ix = 12 + labelW + 6, iy = top + 2;
                    boolean iv = BfTheme.hit((int) x, (int) y, ix, iy, 16, 16);
                    g.fill(ix, iy, ix + 16, iy + 16, iv ? BfTheme.POPOVER : BfTheme.CARD);
                    BfTheme.outline(g, ix, iy, 16, 16, iv ? BfTheme.AMBER : BfTheme.BORDER_HOVER);
                    drawBranch(g, ix + 3, iy + 3, iv ? BfTheme.AMBER : BfTheme.MUTED);
                    if (iv) tip = versionTip(entry, version);
                }
                var hint = hintLines(maps ? statusOf(entry) : WorkshopRadialScreen.hint(entry));
                for (int i = 0; i < hint.size(); i++)
                    g.drawString(font, hint.get(i), 16, top + 21 + i * 10, BfTheme.MUTED, false);
            }
        }
        if (!mapView) g.drawString(font, "Esc", 12, layout.height() - 20, BfTheme.MUTED, false);
        if (!mapView && pages() > 1) {
            BfTheme.button(
                    g,
                    font,
                    layout.width() - 116,
                    layout.height() - 28,
                    48,
                    20,
                    "<",
                    BfTheme.hit(
                            (int) x, (int) y, layout.width() - 116, layout.height() - 28, 48, 20),
                    page > 0,
                    BfTheme.Style.SECONDARY);
            BfTheme.button(
                    g,
                    font,
                    layout.width() - 60,
                    layout.height() - 28,
                    48,
                    20,
                    ">",
                    BfTheme.hit(
                            (int) x, (int) y, layout.width() - 60, layout.height() - 28, 48, 20),
                    page < pages() - 1,
                    BfTheme.Style.SECONDARY);
            g.drawCenteredString(
                    font,
                    (page + 1) + " / " + pages(),
                    layout.width() / 2,
                    layout.height() - 22,
                    BfTheme.TEXT_SECONDARY);
        }
        g.pose().popPose();
        if (tip != null) BfTheme.tooltip(g, font, tip, mx, my, width, height);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (WorkshopControls.key("radial").matchesMouse(button)) {
            onClose();
            return true;
        }
        double x = layout.localX(mx), y = layout.localY(my);
        if (button == 1 && mapView) return TabletMapTab.teleport(x, y, mapFrame());
        if (button != 0) return false;
        if (maps
                && TabletMapTab.available()
                && BfTheme.hit((int) x, (int) y, 12, 24, layout.width() - 24, 20)) {
            mapView = !mapView;
            return true;
        }
        if (mapView) return false;
        if (pages() > 1
                && BfTheme.hit(
                        (int) x, (int) y, layout.width() - 116, layout.height() - 28, 48, 20)) {
            turn(-1);
            return true;
        }
        if (pages() > 1
                && BfTheme.hit(
                        (int) x, (int) y, layout.width() - 60, layout.height() - 28, 48, 20)) {
            turn(1);
            return true;
        }
        int index = -1;
        for (int[] row : pageRows())
            if (BfTheme.hit((int) x, (int) y, 12, row[1], layout.width() - 24, 20)) index = row[0];
        if (index < 0) return false;
        var entry = entries.get(index).getAsJsonObject();
        if (!WorkshopRadialScreen.enabled(entry)) return true;
        String action = WorkshopRadialScreen.str(entry, "action");
        if (action.isEmpty()) return true;
        if (entry.has("destructive")
                && entry.get("destructive").getAsBoolean()
                && !confirm.equals(action)) {
            confirm = action;
            return true;
        }
        boolean close = entry.has("closeOnClick") && entry.get("closeOnClick").getAsBoolean();
        if (close) onClose();
        WorkshopControls.execute(minecraft, action);
        return true;
    }

    /** Git-branch mark for the version button: stem with two commits, fits 10x10. */
    static void drawBranch(net.minecraft.client.gui.GuiGraphics g, int x, int y, int color) {
        g.fill(x + 1, y, x + 5, y + 4, color);
        g.fill(x + 2, y + 4, x + 4, y + 7, color);
        g.fill(x + 4, y + 5, x + 8, y + 7, color);
        g.fill(x + 6, y + 4, x + 10, y + 8, color);
    }

    private static java.util.List<net.minecraft.network.chat.Component> versionTip(
            JsonObject entry, String version) {
        var lines = new java.util.ArrayList<net.minecraft.network.chat.Component>();
        lines.add(Component.literal(version));
        String status = statusOf(entry);
        if (!status.isEmpty()) lines.add(Component.literal(status));
        return lines;
    }

    private void turn(int by) {
        page = Math.clamp(page + by, 0, pages() - 1);
        confirm = "";
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        turn(dy > 0 ? -1 : 1);
        return true;
    }

    @Override
    public boolean keyPressed(int code, int scan, int modifiers) {
        if (code == GLFW.GLFW_KEY_ESCAPE || WorkshopControls.matches("radial", code, scan)) {
            onClose();
            return true;
        }
        if (WorkshopControls.panelKey(minecraft, code, scan)) return true;
        return super.keyPressed(code, scan, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
