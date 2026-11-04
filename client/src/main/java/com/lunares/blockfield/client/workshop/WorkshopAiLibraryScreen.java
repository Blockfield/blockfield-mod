package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.tablet.Tablet;
import com.lunares.blockfield.network.packet.WorkshopAiLibraryS2CPacket;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Saved sketches as scrollable cards: take one into the hand ghost, or delete it. Plans stay
 * server-side; there is no rename (the library packet has no such action).
 */
public final class WorkshopAiLibraryScreen extends Screen {
    static final int TOP = 28;
    static final int CARD = 46;
    // 2 px, not the spec's 4: four cards then fit the 254 px sheet of 1080p at GUI 4 (C2).
    static final int GAP = 2;
    static final int STEP = CARD + GAP;
    static final int SIDE = 64;
    // Scroll position and selection survive reopening for the session.
    private static int first;
    private static UUID selectedId;

    private WorkshopAiLayout layout;
    private final List<WorkshopButton> place = new ArrayList<>();
    private final List<WorkshopButton> delete = new ArrayList<>();
    private WorkshopButton create;
    private WorkshopButton newSketch;
    private WorkshopButton close;
    private boolean dragging;
    private UUID[] armedIds = new UUID[0];

    public WorkshopAiLibraryScreen() {
        super(Component.translatable("blockfield.workshop.ai.library_title"));
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("blockfield.workshop.ai." + key, args);
    }

    /** Whole cards between the header and the bottom row: 3 at 240 px, 4 at 1080p (GUI 2–4). */
    static int visibleCards(int sheetHeight) {
        return Math.max(1, (sheetHeight - 36 - TOP + GAP) / STEP);
    }

    static int clampFirst(int first, int entries, int visible) {
        return Math.clamp(first, 0, Math.max(0, entries - visible));
    }

    /** First visible card after moving the selection to {@code index}. */
    static int reveal(int first, int index, int visible) {
        if (index < first) return index;
        if (index >= first + visible) return index - visible + 1;
        return first;
    }

    private int cardWidth() {
        return layout.inner() - 6;
    }

    private int visible() {
        return visibleCards(layout.height());
    }

    @Override
    protected void init() {
        layout = WorkshopAiLayout.fit(width, height);
        font = BfTheme.font();
        place.clear();
        delete.clear();
        int m = WorkshopAiLayout.MARGIN, sideX = m + cardWidth() - SIDE - 3;
        for (int i = 0; i < visible(); i++) {
            int row = i, y = TOP + i * STEP;
            place.add(
                    addRenderableWidget(
                            new WorkshopButton(sideX, y + 2, SIDE, BfTheme.Style.SECONDARY, null)));
            delete.add(
                    addRenderableWidget(
                            new WorkshopButton(
                                    sideX, y + 24, SIDE, BfTheme.Style.DESTRUCTIVE, null)));
            place.get(i)
                    .set(text("place"), BfTheme.Style.SECONDARY, true, () -> take(row))
                    .tooltip(text("place_hint"));
            delete.get(i).set(text("delete"), BfTheme.Style.DESTRUCTIVE, true, () -> remove(row));
        }
        int bottom = layout.height() - 32;
        create =
                addRenderableWidget(
                        new WorkshopButton(
                                m + (layout.inner() - 120) / 2,
                                emptyY() + 52,
                                120,
                                BfTheme.Style.PRIMARY,
                                this::newSketch));
        create.set(text("library_create"), BfTheme.Style.PRIMARY, true, this::newSketch);
        newSketch =
                addRenderableWidget(
                        new WorkshopButton(m, bottom, 120, BfTheme.Style.SECONDARY, null));
        newSketch.set(text("library_new"), BfTheme.Style.SECONDARY, true, this::newSketch);
        close =
                addRenderableWidget(
                        new WorkshopButton(
                                m + layout.inner() - 56, bottom, 56, BfTheme.Style.TERTIARY, null));
        close.set(text("close"), BfTheme.Style.TERTIARY, true, this::onClose);
        armedIds = new UUID[visible()];
        WorkshopAiClient.refreshLibrary();
        updateControls();
        setInitialFocus(entries().isEmpty() ? create : place.get(selectedRow()));
    }

    private int emptyY() {
        return TOP + (layout.height() - 36 - TOP) / 2 - 40;
    }

    private List<WorkshopAiLibraryS2CPacket.Entry> entries() {
        return WorkshopAiClient.library();
    }

    private int selectedIndex() {
        var entries = entries();
        for (int i = 0; i < entries.size(); i++)
            if (entries.get(i).id().equals(selectedId)) return i;
        return entries.isEmpty() ? -1 : 0;
    }

    private int selectedRow() {
        return Math.clamp(selectedIndex() - first, 0, visible() - 1);
    }

    private UUID rowId(int row) {
        int index = first + row;
        return index < entries().size() ? entries().get(index).id() : null;
    }

    private void take(int row) {
        UUID id = rowId(row);
        if (id == null) return;
        selectedId = id;
        if (WorkshopAiClient.takeSketch(id)) onClose();
    }

    private void remove(int row) {
        UUID id = rowId(row);
        if (id != null) WorkshopAiClient.deleteSketch(id);
    }

    private void newSketch() {
        minecraft.setScreen(WorkshopAiScreen.forLibrary());
    }

    private void select(int index) {
        var entries = entries();
        if (entries.isEmpty()) return;
        index = Math.clamp(index, 0, entries.size() - 1);
        selectedId = entries.get(index).id();
        first = reveal(first, index, visible());
    }

    private void updateControls() {
        var entries = entries();
        first = clampFirst(first, entries.size(), visible());
        if (selectedIndex() >= 0) selectedId = entries.get(selectedIndex()).id();
        boolean on = WorkshopAiClient.available();
        for (int i = 0; i < place.size(); i++) {
            UUID id = rowId(i);
            boolean shown = id != null;
            boolean chosen = shown && id.equals(selectedId);
            place.get(i).visible = delete.get(i).visible = shown;
            int row = i;
            place.get(i)
                    .set(
                            text("place"),
                            chosen ? BfTheme.Style.PRIMARY : BfTheme.Style.SECONDARY,
                            on,
                            () -> take(row));
            delete.get(i).set(text("delete"), BfTheme.Style.DESTRUCTIVE, on, () -> remove(row));
            // An armed «Удалить» belongs to the card it was pressed on, not to its row slot.
            if (id == null || !id.equals(armedIds[i])) delete.get(i).disarm();
            armedIds[i] = id;
        }
        create.visible = entries.isEmpty();
        create.active = on;
        newSketch.visible = !entries.isEmpty();
        newSketch.active = on;
    }

    @Override
    public void tick() {
        updateControls();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateControls();
        int lx = (int) layout.localX(mouseX), ly = (int) layout.localY(mouseY);
        int w = layout.width(), h = layout.height(), m = WorkshopAiLayout.MARGIN;
        graphics.pose().pushPose();
        graphics.pose().translate(layout.x(), layout.y(), 0);
        graphics.pose().scale(layout.scale(), layout.scale(), 1);
        BfTheme.panel(graphics, 0, 0, w, h, true);
        BfTheme.ticks(graphics, 0, 0, w, h, 6, BfTheme.AMBER);
        var entries = entries();
        BfTheme.header(
                graphics,
                font,
                m,
                12,
                layout.inner(),
                title.getString(),
                Integer.toString(entries.size()));
        Component nameTip = null;
        if (entries.isEmpty()) renderEmpty(graphics);
        else {
            for (int row = 0; row < visible() && first + row < entries.size(); row++) {
                var entry = entries.get(first + row);
                Component tip = card(graphics, entry, m, TOP + row * STEP, lx, ly);
                if (tip != null) nameTip = tip;
            }
            scrollbar(graphics, entries.size());
        }
        Tablet.withScissorShift(() -> super.render(graphics, lx, ly, partialTick));
        graphics.pose().popPose();
        List<Component> tip = new ArrayList<>();
        if (nameTip != null) tip.add(nameTip);
        else
            for (var button : place)
                if (button.visible && (button.isHovered() || button.isFocused()))
                    tip.add(button.tooltip());
        if (!tip.isEmpty()) {
            var lines = new ArrayList<Component>();
            for (String line :
                    BfTheme.wrap(font, tip.get(0).getString(), WorkshopStatusBlock.TOOLTIP_WIDTH, 8)
                            .lines()) lines.add(Component.literal(line));
            BfTheme.tooltip(graphics, font, lines, mouseX, mouseY, width, height);
        }
    }

    /** Draws one card; returns the full name when its cut name is hovered. */
    private Component card(
            GuiGraphics g, WorkshopAiLibraryS2CPacket.Entry entry, int x, int y, int mx, int my) {
        int cw = cardWidth();
        boolean hovered = BfTheme.hit(mx, my, x, y, cw, CARD);
        boolean chosen = entry.id().equals(selectedId);
        g.fill(x, y, x + cw, y + CARD, BfTheme.BG_ELEVATED);
        BfTheme.outline(
                g,
                x,
                y,
                cw,
                CARD,
                chosen ? BfTheme.AMBER : hovered ? BfTheme.BORDER_HOVER : BfTheme.BORDER);
        footprint(g, x + 6, y + 7, entry.sx(), entry.sy(), entry.sz(), false);
        int tx = x + 44, tw = cw - 44 - SIDE - 10, ty = y + 4;
        var name = BfTheme.wrap(font, entry.name(), tw, 2);
        for (String line : name.lines()) {
            g.drawString(font, line, tx, ty, BfTheme.TEXT, false);
            ty += 10;
        }
        // Name(≤2) + meta + date share four 10 px rows: a one-line name leaves the meta two.
        String meta =
                text(
                                "library_meta",
                                entry.sx(),
                                entry.sy(),
                                entry.sz(),
                                WorkshopAiFormat.count(entry.cells()))
                        .getString();
        var dims = BfTheme.wrap(font, meta, tw, metaBudget(name.lines().size()));
        for (String line : dims.lines()) {
            g.drawString(font, line, tx, ty, BfTheme.TEXT_SECONDARY, false);
            ty += 10;
        }
        g.drawString(
                font,
                WorkshopAiFormat.date(
                        entry.createdAt(),
                        System.currentTimeMillis(),
                        ZoneId.systemDefault(),
                        locale()),
                tx,
                ty,
                BfTheme.MUTED,
                false);
        boolean cut = name.overflow() || dims.overflow();
        int rows = textRows(name.lines().size(), dims.lines().size());
        return cut && BfTheme.hit(mx, my, tx, y + 4, tw, rows * 10)
                ? Component.literal(entry.name() + " " + meta)
                : null;
    }

    /** Meta line budget inside the fixed 46 px card: name(≤2) + meta + date share four rows. */
    static int metaBudget(int nameLines) {
        return nameLines <= 1 ? 2 : 1;
    }

    /** Text rows sharing the card: name(≤2) + meta + date; the tip hit area covers them all. */
    static int textRows(int nameLines, int metaLines) {
        return nameLines + metaLines + 1;
    }

    private Locale locale() {
        return Locale.forLanguageTag(
                minecraft.getLanguageManager().getSelected().replace('_', '-'));
    }

    /** Sketch «footprint»: width × depth to scale plus a height column; no block rendering. */
    static void footprint(GuiGraphics g, int x, int y, int sx, int sy, int sz, boolean faded) {
        int fill = faded ? BfTheme.alpha(BfTheme.AMBER_DIM, 0x60) : BfTheme.AMBER_DIM;
        int edge = faded ? BfTheme.alpha(BfTheme.AMBER, 0x60) : BfTheme.AMBER;
        g.fill(x, y, x + 32, y + 32, BfTheme.BG);
        BfTheme.outline(g, x, y, 32, 32, BfTheme.BORDER);
        int max = Math.max(sx, Math.max(sy, sz));
        int fw = Math.max(2, Math.round(22f * sx / max)),
                fd = Math.max(2, Math.round(22f * sz / max));
        int fx = x + 3 + (22 - fw) / 2, fy = y + 5 + (22 - fd) / 2;
        g.fill(fx, fy, fx + fw, fy + fd, fill);
        BfTheme.outline(g, fx, fy, fw, fd, edge);
        int ch = Math.max(2, Math.round(26f * sy / max));
        g.fill(x + 27, y + 29 - ch, x + 30, y + 29, edge);
    }

    private void renderEmpty(GuiGraphics g) {
        int m = WorkshopAiLayout.MARGIN, inner = layout.inner(), cx = m + inner / 2, y = emptyY();
        footprint(g, cx - 16, y - 36, 5, 3, 5, true);
        g.drawCenteredString(font, text("library_empty_title"), cx, y, BfTheme.TEXT);
        var body =
                BfTheme.wrap(font, text("library_empty_body").getString(), inner - 24, 3).lines();
        for (int i = 0; i < body.size(); i++)
            g.drawCenteredString(font, body.get(i), cx, y + 14 + i * 10, BfTheme.TEXT_SECONDARY);
    }

    private int trackHeight() {
        return visible() * STEP - GAP;
    }

    private void scrollbar(GuiGraphics g, int entries) {
        if (entries <= visible()) return;
        int x = WorkshopAiLayout.MARGIN + layout.inner() - 3, track = trackHeight();
        int thumb = Math.max(8, track * visible() / entries);
        int top = TOP + (track - thumb) * first / (entries - visible());
        g.fill(x, TOP, x + 3, TOP + track, BfTheme.BORDER);
        g.fill(x, top, x + 3, top + thumb, dragging ? BfTheme.AMBER : BfTheme.AMBER_DIM);
    }

    private void dragTo(double y) {
        int entries = entries().size(), track = trackHeight();
        double share = Math.clamp((y - TOP) / track, 0, 1);
        first = clampFirst((int) Math.round(share * (entries - visible())), entries, visible());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (WorkshopControls.key("library").matchesMouse(button)
                || WorkshopControls.key("radial").matchesMouse(button)) {
            onClose();
            return true;
        }
        if (WorkshopControls.key("ai").matchesMouse(button)) {
            minecraft.setScreen(new WorkshopAiScreen());
            return true;
        }
        double x = layout.localX(mouseX), y = layout.localY(mouseY);
        if (super.mouseClicked(x, y, button)) return true;
        if (button != 0 || entries().isEmpty()) return false;
        int sx = WorkshopAiLayout.MARGIN + layout.inner() - 3;
        if (entries().size() > visible()
                && x >= sx - 2
                && x < sx + 5
                && y >= TOP
                && y < TOP + trackHeight()) {
            dragging = true;
            dragTo(y);
            return true;
        }
        int row = (int) Math.floor((y - TOP) / STEP);
        if (x >= WorkshopAiLayout.MARGIN
                && x < WorkshopAiLayout.MARGIN + cardWidth()
                && row >= 0
                && row < visible()
                && (y - TOP) - row * STEP < CARD
                && first + row < entries().size()) {
            select(first + row);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(layout.localX(mouseX), layout.localY(mouseY), button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging) {
            dragTo(layout.localY(mouseY));
            return true;
        }
        return super.mouseDragged(
                layout.localX(mouseX),
                layout.localY(mouseY),
                button,
                dx / layout.scale(),
                dy / layout.scale());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        first = clampFirst(first - (int) Math.signum(scrollY), entries().size(), visible());
        return true;
    }

    @Override
    public boolean keyPressed(int code, int scan, int modifiers) {
        if (WorkshopControls.matches("library", code, scan)
                || WorkshopControls.matches("radial", code, scan)) {
            onClose();
            return true;
        }
        if (WorkshopControls.panelKey(minecraft, code, scan)) return true;
        int index = selectedIndex();
        if (index >= 0) {
            switch (code) {
                case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                    select(index + (code == GLFW.GLFW_KEY_UP ? -1 : 1));
                    setFocused(place.get(selectedRow()));
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    if (getFocused() == place.get(selectedRow())
                            || !(getFocused() instanceof WorkshopButton)) {
                        take(selectedRow());
                        return true;
                    }
                }
                case GLFW.GLFW_KEY_DELETE -> {
                    first = reveal(first, index, visible());
                    delete.get(selectedRow()).onPress();
                    return true;
                }
                default -> {}
            }
        }
        return super.keyPressed(code, scan, modifiers);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
