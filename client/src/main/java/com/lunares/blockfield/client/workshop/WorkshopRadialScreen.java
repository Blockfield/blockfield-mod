package com.lunares.blockfield.client.workshop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lunares.blockfield.client.gui.BfIcon;
import com.lunares.blockfield.client.gui.BfTheme;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Cursor overlay centred on the crosshair; world and inventory remain in place. */
public final class WorkshopRadialScreen extends Screen {
    private JsonArray entries = new JsonArray();
    private String group = "";
    private final boolean hold;
    private final long started = System.currentTimeMillis();
    private boolean released;
    private boolean nested;
    private int radius;
    private final List<int[]> spans = new ArrayList<>();

    /** Anti-aliased rim/separator pixels: {x, y, sector, alpha}. */
    private final List<int[]> edges = new ArrayList<>();

    private static boolean awaitingMenu;
    private static boolean requestingMaps;
    private static boolean requestingSubmit;

    private WorkshopRadialScreen(boolean hold) {
        super(Component.translatable("blockfield.workshop.radial.title"));
        this.hold = hold;
    }

    public static void open(Minecraft mc, boolean hold) {
        awaitingMenu = true;
        requestingMaps = false;
        requestingSubmit = false;
        mc.setScreen(new WorkshopRadialScreen(hold));
        WorkshopControls.send("menu");
    }

    public static void confirmSubmit(Minecraft mc) {
        open(mc, false);
        requestingSubmit = true;
    }

    public static void maps() {
        awaitingMenu = true;
        requestingMaps = true;
        WorkshopControls.send("menu");
    }

    public static void accept(JsonObject menu) {
        Minecraft mc = Minecraft.getInstance();
        if (menu.has("close") && menu.get("close").getAsBoolean()) {
            if (mc.screen instanceof WorkshopRadialScreen || mc.screen instanceof WorkshopMenuPanel)
                mc.setScreen(null);
            return;
        }
        if (menu.has("radial")) {
            if (requestingMaps) {
                requestingMaps = false;
                awaitingMenu = false;
                for (var e : menu.getAsJsonArray("radial")) {
                    JsonObject entry = e.getAsJsonObject();
                    if (entry.has("panel")) {
                        mc.setScreen(
                                WorkshopMenuPanel.entries(
                                        str(entry, "label"),
                                        entry.getAsJsonArray("children"),
                                        true));
                        return;
                    }
                }
            }
            if (mc.screen instanceof WorkshopRadialScreen radial) {
                radial.entries = menu.getAsJsonArray("radial");
                appendKeys(radial.entries);
                if (requestingSubmit) {
                    requestingSubmit = false;
                    for (var element : radial.entries) {
                        var entry = element.getAsJsonObject();
                        if (str(entry, "action").equals("workshop:mk:submit")) {
                            radial.entries = new JsonArray();
                            var confirm = entry.deepCopy();
                            confirm.remove("destructive");
                            confirm.addProperty(
                                    "label",
                                    Component.translatable("blockfield.workshop.radial.confirm")
                                            .getString());
                            radial.entries.add(confirm);
                            radial.group = str(entry, "label");
                            break;
                        }
                    }
                }
                radial.bake();
            } else if (!awaitingMenu) {
                var radial = new WorkshopRadialScreen(false);
                radial.entries = menu.getAsJsonArray("radial");
                appendKeys(radial.entries);
                mc.setScreen(radial);
            }
            awaitingMenu = false;
        } else if (!menu.has("update") || mc.screen instanceof WorkshopMenuPanel) {
            mc.setScreen(WorkshopMenuPanel.menu(menu));
        }
    }

    @Override
    protected void init() {
        font = BfTheme.font();
        radius = Math.min(112, Math.min(width / 2 - 8, radiusForHeight(height)));
        bake();
        if (hold)
            GLFW.glfwSetCursorPos(
                    minecraft.getWindow().getWindow(),
                    minecraft.getWindow().getScreenWidth() / 2.0,
                    minecraft.getWindow().getScreenHeight() / 2.0);
    }

    private void bake() {
        spans.clear();
        edges.clear();
        if (entries.isEmpty()) return;
        int count = entries.size();
        // Centre-sampled class per pixel; boundary pixels (whose 4-neighbourhood disagrees) get
        // a subpixel coverage pass, so the rim, the hole and the separators anti-alias instead
        // of stair-stepping at any GUI scale. Interior runs stay single fills.
        int size = 2 * radius + 3;
        int[] cls = new int[size * size];
        for (int y = -radius - 1; y <= radius + 1; y++)
            for (int x = -radius - 1; x <= radius + 1; x++)
                cls[(y + radius + 1) * size + x + radius + 1] =
                        WorkshopRadial.drawSector(x + 0.5, y + 0.5, 23, radius, count);
        for (int y = -radius - 1; y <= radius + 1; y++) {
            int previous = -1, start = -radius - 1;
            for (int x = -radius - 1; x <= radius + 2; x++) {
                int cell = x > radius + 1 ? -1 : cls[(y + radius + 1) * size + x + radius + 1];
                int current = cell >= 0 && interior(cls, size, x, y, radius, cell) ? cell : -1;
                if (current == previous) continue;
                if (previous >= 0) spans.add(new int[] {start, y, x, previous});
                previous = current;
                start = x;
            }
        }
        for (int y = -radius - 1; y <= radius + 1; y++)
            for (int x = -radius - 1; x <= radius + 1; x++) {
                int cell = cls[(y + radius + 1) * size + x + radius + 1];
                if (cell >= 0 && interior(cls, size, x, y, radius, cell)) continue;
                // Every distinct neighbouring sector owns a share of this pixel.
                boolean[] seen = new boolean[count];
                for (int[] d : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx < -radius - 1 || nx > radius + 1 || ny < -radius - 1 || ny > radius + 1)
                        continue;
                    int s = cls[(ny + radius + 1) * size + nx + radius + 1];
                    if (s >= 0 && !seen[s]) {
                        seen[s] = true;
                        double cover = WorkshopRadial.coverage(x, y, 23, radius, count, s);
                        if (cover > 0)
                            edges.add(new int[] {x, y, s, (int) Math.round(0xDA * cover)});
                    }
                }
            }
    }

    /** Fully surrounded by its own sector: safe to merge into an opaque span run. */
    private static boolean interior(int[] cls, int size, int x, int y, int radius, int cell) {
        int[][] neighbours = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : neighbours) {
            int nx = x + d[0], ny = y + d[1];
            if (nx < -radius - 1 || nx > radius + 1 || ny < -radius - 1 || ny > radius + 1)
                return false;
            if (cls[(ny + radius + 1) * size + nx + radius + 1] != cell) return false;
        }
        return true;
    }

    private int hovered(double mx, double my) {
        return WorkshopRadial.sector(
                mx - width / 2.0, my - height / 2.0, 23, radius, entries.size());
    }

    @Override
    public void tick() {
        com.lunares.blockfield.client.gui.tablet.Tablet.passThroughMovement(minecraft);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float dt) {}

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        int hover = hovered(mx, my), cx = width / 2, cy = height / 2;
        for (var span : spans) {
            JsonObject entry = entries.get(span[3]).getAsJsonObject();
            int color = span[3] == hover && enabled(entry) ? BfTheme.POPOVER : BfTheme.BG;
            g.fill(
                    cx + span[0],
                    cy + span[1],
                    cx + span[2],
                    cy + span[1] + 1,
                    BfTheme.alpha(color, 0xDA));
        }
        // Feathered rim, hole and separator pixels ride the same hover highlight.
        for (var edge : edges) {
            JsonObject entry = entries.get(edge[2]).getAsJsonObject();
            int color = edge[2] == hover && enabled(entry) ? BfTheme.POPOVER : BfTheme.BG;
            g.fill(
                    cx + edge[0],
                    cy + edge[1],
                    cx + edge[0] + 1,
                    cy + edge[1] + 1,
                    BfTheme.alpha(color, edge[3]));
        }
        int count = entries.size();
        // Wrap every caption once: drawing, the bottom-pair nudge and the group slot below
        // the hole all share the real ink widths. One line while every caption fits its chord
        // above the readable minimum; otherwise long captions wrap to two lines and the fit
        // runs on the wrapped block.
        var captions = new java.util.ArrayList<java.util.List<String>>(count);
        var ink = new int[count];
        String[] texts = new String[count];
        int[] single = new int[count];
        for (int i = 0; i < count; i++) {
            texts[i] = label(entries.get(i).getAsJsonObject());
            single[i] = font.width(texts[i]);
        }
        boolean oneLine = WorkshopRadial.fitsSingleLine(count, radius, single);
        for (int i = 0; i < count; i++) {
            java.util.List<String> lines;
            if (oneLine) lines = java.util.List.of(texts[i]);
            else {
                int width =
                        captionWidth(
                                texts[i],
                                WorkshopRadial.labelWidth(i, count, radius, font.lineHeight),
                                font::width);
                lines = BfTheme.wrap(font, texts[i], width, 2).lines();
            }
            captions.add(lines);
            int widest = 0;
            for (String line : lines) widest = Math.max(widest, font.width(line));
            ink[i] = widest;
        }
        // A row-sharing bottom pair («Люди»/«Разметка», «Повернуть»/«Отменить») would read
        // as one phrase: nudge the pair apart so their ink keeps an 8 px gap. The shift
        // moves the whole label assembly (icon and caption) within its sector.
        int[] nudge = WorkshopRadial.captionShifts(radius, count, ink, font.lineHeight);
        // One size for the whole wheel: long labels no longer shrink alone.
        float common = WorkshopRadial.commonScale(count, radius, ink);
        for (int i = 0; i < count; i++) {
            var entry = entries.get(i).getAsJsonObject();
            int[] at = WorkshopRadial.labelOffset(i, count, radius);
            int x = cx + at[0] + nudge[i], y = cy + at[1];
            int color = !enabled(entry) ? BfTheme.MUTED : hover == i ? BfTheme.AMBER : BfTheme.TEXT;
            // Narrow sectors (8+) shrink the whole label assembly to fit the chord.
            float scale = common;
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(scale, scale, 1);
            g.pose().translate(-x, -y, 0);
            icon(entry).draw(g, x - 6, y - 13, 12, color);
            // Sector captions are one or two short words (S4): the key lives in the centre
            // hint, so even the narrowest chord wraps to two lines instead of cutting words.
            var caption = captions.get(i);
            for (int k = 0; k < caption.size(); k++)
                g.drawCenteredString(font, caption.get(k), x, y + 3 + k * 10, color);
            g.pose().popPose();
        }
        // The nested group name sits just below the hole (never over it or the crosshair),
        // in a slot whose width clears the neighbouring captions and icons.
        if (!group.isEmpty()) {
            int slot = groupSlot(count, captions);
            var lines = BfTheme.wrap(font, group, slot, 2).lines();
            for (int i = 0; i < lines.size(); i++)
                g.drawCenteredString(
                        font,
                        lines.get(i),
                        cx,
                        cy + WorkshopRadial.GROUP_TOP + i * 10,
                        BfTheme.AMBER);
        }
        String hint =
                hover >= 0
                        ? hoverText(entries.get(hover).getAsJsonObject())
                        : entries.isEmpty()
                                ? Component.translatable("blockfield.workshop.radial.loading")
                                        .getString()
                                : centerHint();
        if (hintBelow(cy, radius, height)) {
            var hintLines = BfTheme.wrap(font, hint, centreWidth(), 2);
            List<String> shown = hintLines.lines();
            if (hintLines.overflow() && hover >= 0)
                // Details lose to the label: the full caption stays reachable, never «…».
                shown = BfTheme.wrap(font, hoverFallback(hover), centreWidth(), 2).lines();
            int hintTop = hintY(cy, radius);
            for (int k = 0; k < shown.size(); k++)
                g.drawCenteredString(
                        font, shown.get(k), cx, hintTop + k * 10, BfTheme.TEXT_SECONDARY);
        } else {
            // Small height: the wheel keeps its size (hotbar reserve only), so the hint moves
            // to the wheel's right, where the full height is free, instead of shrinking the
            // wheel. Three short lines stay complete; details still lose to the bare label.
            int x0 = cx + radius + 8, slot = Math.max(40, width - 8 - x0);
            var hintLines = BfTheme.wrap(font, hint, slot, 3);
            List<String> shown = hintLines.lines();
            if (hintLines.overflow() && hover >= 0)
                shown = BfTheme.wrap(font, hoverFallback(hover), slot, 3).lines();
            int startY = cy - shown.size() * 10 / 2;
            for (int k = 0; k < shown.size(); k++)
                g.drawCenteredString(
                        font, shown.get(k), x0 + slot / 2, startY + k * 10, BfTheme.TEXT_SECONDARY);
        }
        if (hold
                && !released
                && !WorkshopControls.physicallyDown(minecraft, WorkshopControls.key("radial"))) {
            released = true;
            switch (WorkshopRadial.release(System.currentTimeMillis() - started, hover)) {
                case CANCEL -> onClose();
                case ACTIVATE -> activate(hover);
            }
        }
    }

    private void activate(int index) {
        if (index < 0 || index >= entries.size()) return;
        JsonObject entry = entries.get(index).getAsJsonObject();
        if (!enabled(entry)) return;
        if (str(entry, "action").equals(WorkshopKeyHelp.KEYS_ACTION)) {
            onClose();
            WorkshopKeyHelp.open(minecraft);
            return;
        }
        if (entry.has("children")) {
            JsonArray children = entry.getAsJsonArray("children");
            if (WorkshopRadial.nested(nested, children.size(), entry.has("panel"))) {
                entries = children;
                group = str(entry, "label");
                nested = true;
                bake();
            } else
                // «panel» marks the map list: same sheet (map toggle, RMB hint) as «Открыть карты».
                minecraft.setScreen(
                        WorkshopMenuPanel.entries(
                                str(entry, "label"), children, entry.has("panel")));
            return;
        }
        String action = str(entry, "action");
        if (entry.has("destructive") && entry.get("destructive").getAsBoolean()) {
            var confirm = entry.deepCopy();
            confirm.remove("destructive");
            confirm.addProperty(
                    "label",
                    Component.translatable("blockfield.workshop.radial.confirm").getString());
            entries = new JsonArray();
            entries.add(confirm);
            group = str(entry, "label");
            nested = true;
            bake();
            return;
        }
        onClose();
        WorkshopControls.execute(minecraft, action);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (released && WorkshopControls.key("radial").matchesMouse(button)) {
            onClose();
            return true;
        }
        if (button == 0) {
            // Hold-to-release: while the radial key is still down the release gesture selects,
            // so an early click must not fire the sector under the cursor.
            if (hold && !released) return true;
            int at = hovered(x, y);
            if (at < 0) onClose();
            else activate(at);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int code, int scan, int modifiers) {
        // Hold-to-release: while the radial key is still down every keyPressed for it is an OS
        // auto-repeat, never a fresh toggle — only Esc cancels mid-hold.
        if (code == GLFW.GLFW_KEY_ESCAPE
                || (!hold || released) && WorkshopControls.matches("radial", code, scan)) {
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

    static String str(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : "";
    }

    /**
     * Client-only «Клавиши» entry reopening the key card. It is display-only and executes locally,
     * so the server-authored ACL filtering is untouched. The ring geometry supports one extra
     * sector ({@link WorkshopRadial#MAX_SECTORS}).
     */
    static void appendKeys(JsonArray entries) {
        for (var element : entries)
            if (str(element.getAsJsonObject(), "action").equals(WorkshopKeyHelp.KEYS_ACTION))
                return;
        if (entries.size() >= WorkshopRadial.MAX_SECTORS) return;
        var keys = new JsonObject();
        keys.addProperty("action", WorkshopKeyHelp.KEYS_ACTION);
        entries.add(keys);
    }

    static boolean enabled(JsonObject obj) {
        if (obj.has("enabled") && !obj.get("enabled").getAsBoolean()) return false;
        return switch (str(obj, "action")) {
            case "workshop:cancel" ->
                    WorkshopAiClient.uploading()
                            || WorkshopToolsClient.busy(WorkshopAiClient.state().stage())
                            || WorkshopAiClient.state().stage().equals("preview");
            case "workshop:extend" -> WorkshopAiClient.canFill();
            case "workshop:chain" -> WorkshopSketchPlacement.canExtend();
            case "workshop:ai_undo" ->
                    WorkshopAiClient.state().canUndo()
                            && !WorkshopToolsClient.busy(WorkshopAiClient.state().stage());
            default -> true;
        };
    }

    static String hint(JsonObject obj) {
        if (str(obj, "action").equals(WorkshopKeyHelp.KEYS_ACTION))
            return Component.translatable("blockfield.workshop.radial.keys_hint").getString();
        if ((!obj.has("enabled") || obj.get("enabled").getAsBoolean()) && !enabled(obj))
            return Component.translatable(
                            switch (str(obj, "action")) {
                                case "workshop:cancel" -> "blockfield.workshop.radial.no_task";
                                case "workshop:ai_undo" -> "blockfield.workshop.radial.no_undo";
                                case "workshop:extend" ->
                                        WorkshopAiClient.fillSupported()
                                                ? "blockfield.workshop.radial.fill_unavailable"
                                                : "blockfield.workshop.radial.fill_unsupported";
                                case "workshop:ai" -> "blockfield.workshop.radial.ai_hub";
                                default -> "blockfield.workshop.radial.extend_unavailable";
                            })
                    .getString();
        return str(obj, "hint");
    }

    static String label(JsonObject obj) {
        // Sector captions stay bare (S4): one or two words, the key moves to the centre hint.
        if (str(obj, "action").equals(WorkshopKeyHelp.KEYS_ACTION))
            return Component.translatable("blockfield.workshop.radial.keys_label").getString();
        if (str(obj, "action").equals("workshop:selection"))
            return Component.translatable(
                            WorkshopToolsClient.selectionMode()
                                    ? "blockfield.workshop.radial.selection_off"
                                    : "blockfield.workshop.radial.selection_on")
                    .getString();
        return str(obj, "label");
    }

    /**
     * The font splitter breaks a word wider than the line («Размет/ка» at 854×480), so the line is
     * never narrower than the widest word. Such a word may pass the chord a little; the only
     * row-sharing pairs of real menus («Люди»/«Разметка», «Повернуть»/«Отменить») still clear each
     * other because one of them is short.
     */
    static int captionWidth(String caption, int width, ToIntFunction<String> widthOf) {
        for (String word : caption.split(" ")) width = Math.max(width, widthOf.applyAsInt(word));
        return width;
    }

    /**
     * Leaves room below the wheel for the hotbar only: the hint sits below the wheel while two
     * lines clear the hotbar, else it moves to the wheel's right — so small heights keep a large
     * wheel whose labels still fit their sectors.
     */
    static int radiusForHeight(int height) {
        return height / 2 - 24;
    }

    /** Hint top: always below the wheel; the radius leaves room for two lines. */
    static int hintY(int cy, int radius) {
        return cy + radius + 7;
    }

    /**
     * Below the wheel while two hint lines clear the hotbar; otherwise the hint moves to the
     * wheel's right, where the full height is free, instead of shrinking the wheel.
     */
    static boolean hintBelow(int cy, int radius, int height) {
        return hintY(cy, radius) + 2 * 10 <= height - 24;
    }

    /** Centre width for the hover hint: two wrapped lines, never «…» (S4). */
    int centreWidth() {
        return (int) (radius * 1.4);
    }

    /** Group-name slot below the hole, from the real caption ink of this wheel. */
    private int groupSlot(int count, java.util.List<java.util.List<String>> captions) {
        int[] halves = new int[count], rows = new int[count];
        for (int i = 0; i < count; i++) {
            int widest = 0;
            for (String line : captions.get(i)) widest = Math.max(widest, font.width(line));
            halves[i] = widest / 2 + 1;
            rows[i] = captions.get(i).size();
        }
        int widest = 0;
        for (String word : group.split(" ")) widest = Math.max(widest, font.width(word));
        return WorkshopRadial.groupWidth(radius, count, halves, rows, widest);
    }

    /**
     * Full sector caption with its key and server hint, for the centre line on hover. Pure join
     * below stays headless-testable.
     */
    static String hoverText(JsonObject obj) {
        return hoverText(label(obj), WorkshopKeyHelp.liveKeyName(str(obj, "action")), hint(obj));
    }

    static String hoverText(String base, String keyName, String details) {
        String head = WorkshopKeyHelp.withKey(base, keyName);
        if (details.isEmpty() || details.equals(head) || details.equals(base)) return head;
        return head + " — " + details;
    }

    /** Bare label with its key: what the centre hint falls back to when details overflow. */
    private String hoverFallback(int hover) {
        return WorkshopKeyHelp.withKey(
                label(entries.get(hover).getAsJsonObject()),
                WorkshopKeyHelp.liveKeyName(str(entries.get(hover).getAsJsonObject(), "action")));
    }

    /** One-line centre hint with the real radial binding: hold, release on an item, Esc cancels. */
    static String centerHint() {
        return Component.translatable(
                        "blockfield.workshop.radial.center_hint",
                        WorkshopControls.key("radial").getTranslatedKeyMessage())
                .getString();
    }

    private static BfIcon icon(JsonObject obj) {
        if (!enabled(obj)) return BfIcon.LOCK;
        if (obj.has("icon")) {
            try {
                return BfIcon.valueOf(str(obj, "icon"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return BfIcon.forAction(str(obj, "action"));
    }
}
