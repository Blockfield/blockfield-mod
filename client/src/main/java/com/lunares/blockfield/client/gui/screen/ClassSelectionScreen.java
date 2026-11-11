package com.lunares.blockfield.client.gui.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lunares.blockfield.client.gui.BfIcon;
import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.UiMotion;
import com.lunares.blockfield.client.gui.tablet.Tablet;
import com.lunares.blockfield.network.packet.MenuActionC2SPacket;
import com.lunares.blockfield.network.packet.UiJson;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Class choice; shown on the tablet's deployment tab ({@link Tablet#openDeployment}). */
public class ClassSelectionScreen extends Screen {

    private static final float TARGET_WIDTH = 650.0f;
    private static final float TARGET_HEIGHT = 400.0f;
    private static final int LIST_X = 40;
    private static final int LIST_Y = 60;
    private static final int ROW_HEIGHT = 36;
    private static final int ROW_GAP = 4;
    private static final int ROW_MIN_WIDTH = 240;
    private static final int VARIANTS_OFFSET_X = 160;
    private static final int VARIANT_HEIGHT = 18;
    private static final int VARIANT_GAP = 6;
    private static final int NAME_HIT_WIDTH = 150;
    // Лист не растёт без границы: дальше варианты переносятся на вторую строку.
    private static final int ROW_MAX_WIDTH = 500;
    private static final int ROW_RIGHT_PADDING = 8;
    private static final int VARIANTS_WIDTH = ROW_MAX_WIDTH - VARIANTS_OFFSET_X - ROW_RIGHT_PADDING;
    private static final int PAGE_BUTTON_W = 90;
    private static final int PAGE_BUTTON_H = 20;

    private JsonObject classesData;
    private JsonObject currentUiData;
    private final java.util.Map<String, UiMotion> rowMotion = new java.util.HashMap<>();

    private float hover(String id, boolean active) {
        UiMotion motion = rowMotion.computeIfAbsent(id, key -> new UiMotion());
        long now = System.nanoTime();
        motion.to(active ? 1f : 0f, now);
        return motion.value(now);
    }

    private record Box(int x, int y, int w, int h) {}

    private record Row(String classId, int firstVariant, Box rect, List<Box> variants) {}

    private record Layout(
            float scale,
            int mouseX,
            int mouseY,
            int width,
            int height,
            int rowWidth,
            List<Row> rows,
            int page,
            int pages,
            Box prevPage,
            Box nextPage,
            boolean showModel) {}

    /** Фокус клавиатуры: "classId:variant" или "page:prev"/"page:next". */
    private String focusedId;

    private int page;

    public ClassSelectionScreen(String classesJson) {
        super(Component.literal("Выбор класса"));
        try {
            this.classesData = UiJson.parse(classesJson);
        } catch (Exception e) {
            this.classesData = new JsonObject();
        }
    }

    static boolean hasPaidClasses(JsonObject classes) {
        if (classes == null) return false;
        return classes.entrySet().stream()
                .anyMatch(
                        entry -> {
                            if (!entry.getValue().isJsonObject()) return false;
                            JsonObject kit = UiJson.asObject(entry.getValue());
                            return UiJson.integer(kit, "deploymentCost", 0) > 0;
                        });
    }

    private double battleCredits() {
        if (classesData != null) {
            for (String id : classesData.keySet()) {
                if (!classesData.get(id).isJsonObject()) continue;
                JsonObject kit = UiJson.obj(classesData, id);
                double credits = UiJson.decimal(kit, "battleCredits", Double.NaN);
                if (Double.isFinite(credits)) return credits;
            }
        }
        return 0;
    }

    private boolean canAfford(JsonObject kit) {
        double cost = UiJson.decimal(kit, "deploymentCost", Double.NaN);
        return !Double.isFinite(cost) || battleCredits() >= cost;
    }

    private static JsonArray classPlayers(JsonObject uiData, String classId) {
        JsonArray mine = UiJson.arrOrNull(UiJson.obj(uiData, "classes"), classId);
        return mine == null ? new JsonArray() : mine;
    }

    int getLiveCount(String classId, int fallback) {
        if (this.currentUiData != null && UiJson.obj(this.currentUiData, "classes") != null)
            return classPlayers(this.currentUiData, classId).size();
        return fallback;
    }

    List<String> getLivePlayers(String classId) {
        List<String> names = new ArrayList<>();
        JsonArray pArr = classPlayers(this.currentUiData, classId);
        for (int i = 0; i < pArr.size(); i++) {
            String name = UiJson.str(pArr.get(i), null);
            names.add(name == null ? "Игрок" : name);
        }
        return names;
    }

    static List<String> variantNames(JsonObject classObj) {
        List<String> names = new ArrayList<>();
        if (classObj == null) return names;
        JsonArray variants = UiJson.arr(classObj, "variants");
        for (int i = 0; i < variants.size(); i++) {
            JsonObject varObj = UiJson.asObject(variants.get(i));
            if (varObj == null) continue;
            names.add(UiJson.str(varObj, "name", "Вариант " + i));
        }
        return names;
    }

    /**
     * Перенос вариантов в строки внутри класса: жадно слева направо, не шире maxLine. Возвращает
     * ширины по строкам; pure helper для тестов без шрифта.
     */
    static List<List<Integer>> wrapVariants(List<Integer> widths, int maxLine) {
        List<List<Integer>> lines = new ArrayList<>();
        List<Integer> line = new ArrayList<>();
        int used = 0;
        for (int w : widths) {
            int need = line.isEmpty() ? w : used + VARIANT_GAP + w;
            if (!line.isEmpty() && need > maxLine) {
                lines.add(line);
                line = new ArrayList<>();
                used = 0;
            }
            line.add(w);
            used = line.size() == 1 ? w : used + VARIANT_GAP + w;
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    static int variantLinesPerRow(int listBottom) {
        return Math.max(1, (listBottom - LIST_Y - 16) / (VARIANT_HEIGHT + 2));
    }

    /** Разбиение классов по страницам фактическими высотами; pure helper для тестов. */
    static List<List<String>> splitPages(
            List<String> ids, java.util.Map<String, Integer> rowH, int listBottom) {
        List<List<String>> pageIds = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int cap = Math.max(ROW_HEIGHT, listBottom - LIST_Y + ROW_GAP);
        int curH = 0;
        for (String classId : ids) {
            int h = rowH.getOrDefault(classId, ROW_HEIGHT) + ROW_GAP;
            if (!cur.isEmpty() && curH + h > cap) {
                pageIds.add(cur);
                cur = new ArrayList<>();
                curH = 0;
            }
            cur.add(classId);
            curH += h;
        }
        if (!cur.isEmpty()) pageIds.add(cur);
        return pageIds;
    }

    private Layout layout(double mouseX, double mouseY) {
        float scale =
                Math.min(1.0f, Math.min(this.width / TARGET_WIDTH, this.height / TARGET_HEIGHT));
        int rowWidth = ROW_MIN_WIDTH;
        int virtualH = (int) (this.height / scale);

        List<String> ids = new ArrayList<>();
        if (this.classesData != null) ids.addAll(this.classesData.keySet());
        java.util.Map<String, List<List<Integer>>> wrapped = new java.util.HashMap<>();
        java.util.Map<String, Integer> rowH = new java.util.HashMap<>();
        java.util.Map<String, String> rowClasses = new java.util.HashMap<>();
        java.util.Map<String, Integer> firstVariants = new java.util.HashMap<>();
        List<String> rowIds = new ArrayList<>();
        int maxLines = variantLinesPerRow(virtualH - 56 - PAGE_BUTTON_H - 8);
        for (String classId : ids) {
            List<Integer> widths = new ArrayList<>();
            for (String varName : variantNames(UiJson.obj(this.classesData, classId))) {
                widths.add(Math.min(this.font.width(varName) + 16, VARIANTS_WIDTH));
            }
            if (widths.isEmpty()) widths.add(80);
            List<List<Integer>> lines = wrapVariants(widths, VARIANTS_WIDTH);
            // A single class can span pages; keep original variant indices in each chunk.
            int firstVariant = 0;
            for (int from = 0; from < lines.size(); from += maxLines) {
                String rowId = classId + "#" + from;
                List<List<Integer>> chunk =
                        lines.subList(from, Math.min(lines.size(), from + maxLines));
                rowIds.add(rowId);
                rowClasses.put(rowId, classId);
                firstVariants.put(rowId, firstVariant);
                wrapped.put(rowId, chunk);
                rowH.put(rowId, Math.max(ROW_HEIGHT, chunk.size() * (VARIANT_HEIGHT + 2) + 16));
                firstVariant += chunk.stream().mapToInt(List::size).sum();
            }
            int widest = 0;
            for (List<Integer> line : lines) {
                int w = -VARIANT_GAP;
                for (int v : line) w += v + VARIANT_GAP;
                widest = Math.max(widest, w);
            }
            rowWidth =
                    Math.max(
                            rowWidth,
                            Math.min(
                                    ROW_MAX_WIDTH, VARIANTS_OFFSET_X + widest + ROW_RIGHT_PADDING));
        }

        // Низ занят действиями матча: список заканчивается выше, иначе нужны страницы.
        List<List<String>> pageIds = splitPages(rowIds, rowH, virtualH - 56);
        if (pageIds.size() > 1) {
            // Кнопки страниц отнимают место: пересчитываем разбиение с их учётом.
            pageIds = splitPages(rowIds, rowH, virtualH - 56 - PAGE_BUTTON_H - 8);
        }
        int pages = Math.max(1, pageIds.size());
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        rowIds = pageIds.isEmpty() ? List.of() : pageIds.get(page);

        List<Row> rows = new ArrayList<>();

        int y = LIST_Y;
        for (String rowId : rowIds) {
            String classId = rowClasses.get(rowId);
            List<Box> variants = new ArrayList<>();
            List<String> names = variantNames(UiJson.obj(this.classesData, classId));
            List<List<Integer>> lines = wrapped.get(rowId);
            int vi = firstVariants.get(rowId);
            int vy = y + 8;
            for (List<Integer> line : lines) {
                int vx = LIST_X + VARIANTS_OFFSET_X;
                for (int vw : line) {
                    if (vi < names.size()) {
                        variants.add(new Box(vx, vy, vw, VARIANT_HEIGHT));
                        vi++;
                    }
                    vx += vw + VARIANT_GAP;
                }
                vy += VARIANT_HEIGHT + 2;
            }
            rows.add(
                    new Row(
                            classId,
                            firstVariants.get(rowId),
                            new Box(LIST_X, y, rowWidth, rowH.get(rowId)),
                            variants));
            y += rowH.get(rowId) + ROW_GAP;
        }

        Box prev = null;
        Box next = null;
        if (pages > 1) {
            int by = virtualH - 56 - PAGE_BUTTON_H;
            prev = new Box(LIST_X, by, PAGE_BUTTON_W, PAGE_BUTTON_H);
            next = new Box(LIST_X + rowWidth - PAGE_BUTTON_W, by, PAGE_BUTTON_W, PAGE_BUTTON_H);
        }
        // Модель справа скрываем только при реальной нехватке места, координаты не трогаем.
        boolean showModel = LIST_X + rowWidth + 16 < (int) (this.width / scale) - 146;

        return new Layout(
                scale,
                (int) (mouseX / scale),
                (int) (mouseY / scale),
                (int) (this.width / scale),
                virtualH,
                rowWidth,
                rows,
                page,
                pages,
                prev,
                next,
                showModel);
    }

    private boolean selectable(
            String classId, JsonObject classObj, boolean varSelected, boolean isCurrent) {
        if (classObj == null) return false;
        boolean blocked = UiJson.bool(classObj, "blocked", false);
        int limit = UiJson.integer(classObj, "limit", 999);
        int staticCount = UiJson.integer(classObj, "currentCount", 0);
        boolean isFull = getLiveCount(classId, staticCount) >= limit;
        return !blocked && canAfford(classObj) && (varSelected || !isFull || isCurrent);
    }

    /** Порядок Tab: доступные варианты страницы, затем кнопки страниц. */
    private List<String> focusOrder(Layout l) {
        List<String> order = new ArrayList<>();
        for (Row row : l.rows()) {
            JsonObject classObj =
                    this.classesData == null ? null : UiJson.obj(this.classesData, row.classId());
            if (classObj == null) continue;
            boolean isCurrent = UiJson.bool(classObj, "isCurrent", false);
            int selectedVariant = UiJson.integer(classObj, "selectedVariant", -1);
            for (int i = 0; i < row.variants().size(); i++) {
                boolean varSelected = isCurrent && selectedVariant == row.firstVariant() + i;
                if (selectable(row.classId(), classObj, varSelected, isCurrent)) {
                    order.add(row.classId() + ":" + (row.firstVariant() + i));
                }
            }
        }
        if (l.pages() > 1) {
            if (l.page() > 0) order.add("page:prev");
            if (l.page() + 1 < l.pages()) order.add("page:next");
        }
        order.addAll(MatchActions.focusOrder(currentUiData));
        return order;
    }

    private boolean activate(String id) {
        if (id == null) return false;
        if (MatchActions.activate(this.minecraft, currentUiData, id)) return true;
        if (id.equals("page:prev") && page > 0) {
            page--;
            focusedId = null;
            return true;
        }
        if (id.equals("page:next")) {
            page++;
            focusedId = null;
            return true;
        }
        int sep = id.lastIndexOf(':');
        if (sep < 0) return false;
        String classId = id.substring(0, sep);
        int variant;
        try {
            variant = Integer.parseInt(id.substring(sep + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        JsonObject classObj = UiJson.obj(this.classesData, classId);
        if (classObj == null) return false;
        boolean isCurrent = UiJson.bool(classObj, "isCurrent", false);
        int selectedVariant = UiJson.integer(classObj, "selectedVariant", -1);
        if (!selectable(classId, classObj, isCurrent && selectedVariant == variant, isCurrent))
            return false;
        if (this.minecraft != null && this.minecraft.player != null) {
            ClientPlayNetworking.send(
                    new MenuActionC2SPacket(classAction(classId, variant, classObj)));
            Tablet.closeDeploymentSoon();
        }
        return true;
    }

    @Override
    protected void init() {
        this.font = BfTheme.font();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, BfTheme.SCRIM);

        Layout l = layout(mouseX, mouseY);
        float scale = l.scale();

        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(scale, scale, 1.0f);

        BfTheme.header(
                guiGraphics,
                this.font,
                LIST_X,
                25,
                l.rowWidth(),
                "ВЫБОР КЛАССА",
                hasPaidClasses(classesData) ? "БАЛАНС: " + (long) battleCredits() + " БК" : "");
        String countdown = DeploymentCountdown.text(currentUiData);
        guiGraphics.drawString(
                this.font,
                countdown.isEmpty()
                        ? (hasPaidClasses(classesData)
                                ? "БК за бой и точки • оплата каждой высадки • сброс в конце матча"
                                : "Выбери класс и комплект снаряжения")
                        : countdown,
                LIST_X,
                40,
                countdown.isEmpty() ? BfTheme.MUTED : BfTheme.AMBER,
                false);

        int frameCx = l.width() - 90;
        int frameBottom = l.height() / 2 + 60;
        int frameX = frameCx - 56;
        int frameY = frameBottom - 172;
        if (l.showModel()) {
            BfTheme.panel(guiGraphics, frameX, frameY, 112, 196, true);
            BfTheme.ticks(guiGraphics, frameX, frameY, 112, 196, 6, BfTheme.AMBER);
        }

        // Модель игрока рисуется в экранных координатах: renderEntityInInventoryFollowsMouse в
        // 1.21.1
        // ставит ножницы (enableScissor) без учёта PoseStack, поэтому внутри scale() её обрезало
        // бы.
        if (l.showModel() && this.minecraft != null && this.minecraft.player != null) {
            int entScale = Math.max(1, (int) (80 * scale));
            int modelCx = (int) (this.width - 90 * scale);
            int modelBy = (int) (this.height / 2f + 60 * scale);
            guiGraphics.pose().popPose();
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    guiGraphics,
                    modelCx - entScale / 2,
                    modelBy - 2 * entScale,
                    modelCx + entScale / 2,
                    modelBy,
                    entScale,
                    0.0625F,
                    mouseX,
                    mouseY,
                    this.minecraft.player);
            guiGraphics.pose().pushPose();
            guiGraphics.pose().scale(scale, scale, 1.0f);
        }

        List<Component> tooltipToRender = null;

        for (Row row : l.rows()) {
            JsonObject classObj =
                    this.classesData == null ? null : UiJson.obj(this.classesData, row.classId());
            if (classObj == null) continue;
            String name = UiJson.str(classObj, "name", row.classId());
            boolean isCurrent = UiJson.bool(classObj, "isCurrent", false);
            int selectedVariant = UiJson.integer(classObj, "selectedVariant", -1);
            boolean blocked = UiJson.bool(classObj, "blocked", false);

            int staticCount = UiJson.integer(classObj, "currentCount", 0);
            int limit = UiJson.integer(classObj, "limit", 999);
            int currentCount = getLiveCount(row.classId(), staticCount);
            boolean isFull = currentCount >= limit;

            Box r = row.rect();
            boolean rowHovered = BfTheme.hit(l.mouseX(), l.mouseY(), r.x(), r.y(), r.w(), r.h());

            BfTheme.panel(guiGraphics, r.x(), r.y(), r.w(), r.h(), false);
            float hover = hover(row.classId(), rowHovered && !blocked);
            guiGraphics.fill(
                    r.x() + 1,
                    r.y() + 1,
                    r.x() + r.w() - 1,
                    r.y() + r.h() - 1,
                    BfTheme.mix(BfTheme.CARD, BfTheme.POPOVER, hover));
            BfTheme.outline(
                    guiGraphics,
                    r.x(),
                    r.y(),
                    r.w(),
                    r.h(),
                    BfTheme.mix(BfTheme.BORDER, BfTheme.AMBER, hover));
            BfIcon.forClass(row.classId())
                    .draw(
                            guiGraphics,
                            r.x() + 9,
                            r.y() + 10,
                            16,
                            blocked
                                    ? BfTheme.MUTED
                                    : isCurrent ? BfTheme.AMBER : BfTheme.TEXT_SECONDARY);

            int accent =
                    isCurrent
                            ? BfTheme.AMBER
                            : ((blocked || isFull) ? BfTheme.DANGER : BfTheme.BORDER);
            guiGraphics.fill(r.x(), r.y(), r.x() + 2, r.y() + r.h(), accent);

            int textY = r.y() + 6;
            String shownName =
                    BfTheme.ellipsize(
                            this.font,
                            name,
                            VARIANTS_OFFSET_X
                                    - 44
                                    - (limit >= 999
                                            ? 0
                                            : this.font.width(currentCount + "/" + limit) + 8));
            guiGraphics.drawString(
                    this.font,
                    shownName,
                    r.x() + 34,
                    textY,
                    blocked ? BfTheme.MUTED_DARK : BfTheme.TEXT,
                    false);
            int price = UiJson.integer(classObj, "deploymentCost", 0);
            guiGraphics.drawString(
                    this.font,
                    price == 0 ? "Бесплатно" : price + " БК за высадку",
                    r.x() + 34,
                    r.y() + 22,
                    canAfford(classObj) ? BfTheme.MUTED : BfTheme.DANGER,
                    false);
            String limitStr = limit >= 999 ? "" : currentCount + "/" + limit;
            guiGraphics.drawString(
                    this.font,
                    limitStr,
                    r.x() + 34 + this.font.width(shownName) + 8,
                    textY,
                    BfTheme.MUTED,
                    false);

            if (BfTheme.hit(l.mouseX(), l.mouseY(), r.x(), r.y(), NAME_HIT_WIDTH, r.h())) {
                String statusTxt = blocked ? "[ЗАКРЫТ]" : (isCurrent ? "[ВЫБРАН]" : "");
                List<Component> classTooltip = new ArrayList<>();
                classTooltip.add(
                        Component.literal(name + " " + statusTxt).withColor(BfTheme.AMBER));
                if (!canAfford(classObj))
                    classTooltip.add(
                            Component.literal("Недостаточно БК. Заработайте их бесплатным классом.")
                                    .withColor(BfTheme.DANGER));
                if (blocked) {
                    String reason = UiJson.str(classObj, "blocked_reason", null);
                    if (reason != null) {
                        classTooltip.add(
                                Component.literal("Причина: " + reason).withColor(BfTheme.DANGER));
                    }
                }
                String desc = UiJson.str(classObj, "desc", null);
                JsonArray descArr = UiJson.arrOrNull(classObj, "desc");
                if (descArr != null) {
                    for (int i = 0; i < descArr.size(); i++) {
                        String line = UiJson.str(descArr.get(i), null);
                        if (line != null) {
                            classTooltip.add(
                                    Component.literal(line).withColor(BfTheme.TEXT_SECONDARY));
                        }
                    }
                } else if (desc != null) {
                    classTooltip.add(Component.literal(desc).withColor(BfTheme.TEXT_SECONDARY));
                }
                classTooltip.add(
                        Component.literal(
                                        "Игроки: "
                                                + currentCount
                                                + "/"
                                                + (limit >= 999 ? "∞" : limit))
                                .withColor(BfTheme.MUTED));
                List<String> livePlayers = getLivePlayers(row.classId());
                for (String pName : livePlayers) {
                    classTooltip.add(Component.literal("- " + pName).withColor(BfTheme.TEXT));
                }
                if (livePlayers.isEmpty()) {
                    classTooltip.add(Component.literal("(Пусто)").withColor(BfTheme.MUTED_DARK));
                }
                tooltipToRender = classTooltip;
            }

            JsonArray variants = UiJson.arr(classObj, "variants");
            List<String> names = variantNames(classObj);
            for (int i = 0;
                    i < Math.min(row.variants().size(), variants.size() - row.firstVariant());
                    i++) {
                JsonObject varObj = UiJson.asObject(variants.get(row.firstVariant() + i));
                if (varObj == null) continue;
                Box v = row.variants().get(i);
                boolean varSelected = (isCurrent && selectedVariant == row.firstVariant() + i);
                boolean canSelect = selectable(row.classId(), classObj, varSelected, isCurrent);
                boolean varHovered =
                        canSelect
                                && BfTheme.hit(l.mouseX(), l.mouseY(), v.x(), v.y(), v.w(), v.h());
                boolean focused =
                        (row.classId() + ":" + (row.firstVariant() + i)).equals(focusedId);

                BfTheme.button(
                        guiGraphics,
                        this.font,
                        v.x(),
                        v.y(),
                        v.w(),
                        v.h(),
                        BfTheme.ellipsize(this.font, names.get(row.firstVariant() + i), v.w() - 8),
                        varHovered || focused,
                        canSelect,
                        varSelected);
                if (focused) {
                    BfTheme.focusRing(guiGraphics, v.x(), v.y(), v.w(), v.h());
                }

                if (varHovered || focused) {
                    List<Component> varTooltip = new ArrayList<>();
                    varTooltip.add(
                            Component.literal(name + " · " + names.get(row.firstVariant() + i))
                                    .withColor(BfTheme.AMBER));
                    JsonArray varDescArr = UiJson.arrOrNull(varObj, "desc");
                    if (varDescArr != null) {
                        for (int j = 0; j < varDescArr.size(); j++) {
                            String varLine = UiJson.str(varDescArr.get(j), null);
                            if (varLine != null) {
                                varTooltip.add(
                                        Component.literal(varLine)
                                                .withColor(BfTheme.TEXT_SECONDARY));
                            }
                        }
                    } else {
                        String varDesc = UiJson.str(varObj, "desc", null);
                        if (varDesc != null) {
                            varTooltip.add(
                                    Component.literal(varDesc).withColor(BfTheme.TEXT_SECONDARY));
                        }
                    }
                    varTooltip.add(Component.literal(""));
                    varTooltip.add(
                            Component.literal("Нажмите, чтобы выбрать").withColor(BfTheme.OK));
                    tooltipToRender = varTooltip;
                }
            }
        }

        if (l.pages() > 1) {
            String pageLabel = (l.page() + 1) + " / " + l.pages();
            int labelW = this.font.width(pageLabel);
            int labelX = l.prevPage().x() + (l.rowWidth() - labelW) / 2;
            guiGraphics.drawString(
                    this.font, pageLabel, labelX, l.prevPage().y() + 6, BfTheme.MUTED, false);
            BfTheme.button(
                    guiGraphics,
                    this.font,
                    l.prevPage().x(),
                    l.prevPage().y(),
                    l.prevPage().w(),
                    l.prevPage().h(),
                    "← НАЗАД",
                    BfTheme.hit(
                                    l.mouseX(),
                                    l.mouseY(),
                                    l.prevPage().x(),
                                    l.prevPage().y(),
                                    l.prevPage().w(),
                                    l.prevPage().h())
                            || "page:prev".equals(focusedId),
                    l.page() > 0,
                    false);
            BfTheme.button(
                    guiGraphics,
                    this.font,
                    l.nextPage().x(),
                    l.nextPage().y(),
                    l.nextPage().w(),
                    l.nextPage().h(),
                    "ВПЕРЁД →",
                    BfTheme.hit(
                                    l.mouseX(),
                                    l.mouseY(),
                                    l.nextPage().x(),
                                    l.nextPage().y(),
                                    l.nextPage().w(),
                                    l.nextPage().h())
                            || "page:next".equals(focusedId),
                    l.page() + 1 < l.pages(),
                    false);
        }

        MatchActions.render(
                guiGraphics,
                this.font,
                l.width(),
                l.height(),
                l.mouseX(),
                l.mouseY(),
                currentUiData,
                focusedId);
        guiGraphics.pose().popPose();

        if (tooltipToRender != null) {
            BfTheme.tooltip(
                    guiGraphics,
                    this.font,
                    tooltipToRender,
                    mouseX,
                    mouseY,
                    this.width,
                    this.height);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        Layout l = layout(mouseX, mouseY);
        if (MatchActions.click(
                this.minecraft, l.width(), l.height(), l.mouseX(), l.mouseY(), currentUiData))
            return true;

        if (l.pages() > 1) {
            if (l.page() > 0
                    && BfTheme.hit(
                            l.mouseX(),
                            l.mouseY(),
                            l.prevPage().x(),
                            l.prevPage().y(),
                            l.prevPage().w(),
                            l.prevPage().h())) {
                page--;
                focusedId = null;
                return true;
            }
            if (l.page() + 1 < l.pages()
                    && BfTheme.hit(
                            l.mouseX(),
                            l.mouseY(),
                            l.nextPage().x(),
                            l.nextPage().y(),
                            l.nextPage().w(),
                            l.nextPage().h())) {
                page++;
                focusedId = null;
                return true;
            }
        }

        for (Row row : l.rows()) {
            JsonObject classObj =
                    this.classesData == null ? null : UiJson.obj(this.classesData, row.classId());
            if (classObj == null) continue;
            boolean isCurrent = UiJson.bool(classObj, "isCurrent", false);

            for (int i = 0; i < row.variants().size(); i++) {
                Box v = row.variants().get(i);
                boolean varSelected =
                        isCurrent
                                && UiJson.integer(classObj, "selectedVariant", -1)
                                        == row.firstVariant() + i;
                boolean canSelect = selectable(row.classId(), classObj, varSelected, isCurrent);

                if (canSelect && BfTheme.hit(l.mouseX(), l.mouseY(), v.x(), v.y(), v.w(), v.h())) {
                    if (this.minecraft != null && this.minecraft.player != null) {
                        ClientPlayNetworking.send(
                                new MenuActionC2SPacket(
                                        classAction(
                                                row.classId(), row.firstVariant() + i, classObj)));
                        Tablet.closeDeploymentSoon();
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static String classAction(String classId, int variant, JsonObject definition) {
        String token = UiJson.str(definition, "selectionToken", null);
        return "class:" + classId + ":" + variant + (token == null ? "" : "." + token);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Layout l = layout(mouseX, mouseY);
        if (l.pages() <= 1 || scrollY == 0) return false;
        page = Math.max(0, Math.min(l.pages() - 1, page + (scrollY < 0 ? 1 : -1)));
        focusedId = null;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        Layout l = layout(0, -1000);
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            boolean forward = (modifiers & GLFW.GLFW_MOD_SHIFT) == 0;
            focusedId = MenuLayout.cycleFocus(focusOrder(l), focusedId, forward);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (focusedId == null) focusedId = MenuLayout.cycleFocus(focusOrder(l), null, true);
            return activate(focusedId);
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP && l.pages() > 1 && page > 0) {
            page--;
            focusedId = null;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN && l.pages() > 1 && page + 1 < l.pages()) {
            page++;
            focusedId = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public void updateModel(String updatedUiJson) {
        try {
            this.currentUiData = UiJson.parse(updatedUiJson);
            if (currentUiData.has("battleCredits") && classesData != null) {
                for (String id : classesData.keySet()) {
                    JsonObject kit = UiJson.obj(classesData, id);
                    if (kit != null) kit.add("battleCredits", currentUiData.get("battleCredits"));
                }
            }
        } catch (Exception e) {
        }
    }

    public void updateClassesData(String classesJson) {
        try {
            this.classesData = UiJson.parse(classesJson);
        } catch (Exception e) {
        }
    }
}
