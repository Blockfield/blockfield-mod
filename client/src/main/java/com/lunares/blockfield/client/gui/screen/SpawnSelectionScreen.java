package com.lunares.blockfield.client.gui.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lunares.blockfield.client.gui.BfIcon;
import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.UiMotion;
import com.lunares.blockfield.client.gui.tablet.Tablet;
import com.lunares.blockfield.network.packet.MenuActionC2SPacket;
import com.lunares.blockfield.network.packet.UiJson;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/** Spawn point choice; shown on the tablet's deployment tab ({@link Tablet#openDeployment}). */
public class SpawnSelectionScreen extends Screen {

    private static final float TARGET_WIDTH = 650.0f;
    private static final float TARGET_HEIGHT = 400.0f;
    private static final int LIST_X = 40;
    private static final int LIST_Y = 100;
    private static final int LIST_WIDTH = 180;
    private static final int ROW_HEIGHT = 30;
    private static final int ROW_GAP = 5;
    private static final int MARKER_HALF = 7;
    private static final int PAGE_BUTTON_H = 20;

    private String mapId;
    private JsonObject spawnsData;
    private JsonObject currentUiData;
    private final java.util.Map<String, UiMotion> rowMotion = new java.util.HashMap<>();

    private float hover(String id, boolean active) {
        UiMotion motion = rowMotion.computeIfAbsent(id, key -> new UiMotion());
        long now = System.nanoTime();
        motion.to(active ? 1f : 0f, now);
        return motion.value(now);
    }

    private String selectedSpawnId = "BASE";

    // The screen is rebuilt on every respawn; the last pick survives while the map stays the same.
    private static final SpawnSelectionMemory LAST_SELECTION = new SpawnSelectionMemory();

    private void select(String spawnId) {
        selectedSpawnId = spawnId;
        LAST_SELECTION.select(mapId, spawnId);
    }

    private record Box(int x, int y, int w, int h) {}

    private record Row(String name, Box rect) {}

    private record Marker(String name, int x, int y) {}

    private record Layout(
            float scale,
            int mouseX,
            int mouseY,
            int width,
            int height,
            int mapX,
            int mapY,
            int mapW,
            int mapH,
            Box mapArea,
            List<Row> rows,
            List<Marker> markers,
            Box deploy,
            Box changeClass,
            int page,
            int pages,
            Box prevPage,
            Box nextPage) {}

    private String focusedId;
    private int page;

    public SpawnSelectionScreen(String spawnsJson, String mapId) {
        super(Component.literal("Выбор точки высадки"));
        this.mapId = mapId;
        selectedSpawnId = LAST_SELECTION.forMap(mapId);
        try {
            this.spawnsData = UiJson.parse(spawnsJson);
        } catch (Exception e) {
            this.spawnsData = new JsonObject();
        }
    }

    JsonObject getLivePointData(String ptName) {
        if (this.currentUiData != null
                && this.currentUiData.has("pts")
                && this.currentUiData.get("pts").isJsonArray()) {
            JsonArray pts = UiJson.arr(this.currentUiData, "pts");
            for (int i = 0; i < pts.size(); i++) {
                JsonObject p = UiJson.asObject(pts.get(i));
                if (p == null) continue;
                if (p.has("n") && p.get("n").getAsString().equals(ptName)) {
                    return p;
                }
            }
        }
        return null;
    }

    /** Deathmatch: одна высадка, точку выбирает сервер; командных баз и точек нет. */
    private boolean isFfa() {
        return this.spawnsData != null
                && this.spawnsData.has("ffa")
                && this.spawnsData.get("ffa").getAsBoolean();
    }

    /** Режим с одним набором на всех (снайперы): выбирать нечего, кнопки смены класса нет. */
    private boolean fixedClass() {
        return this.spawnsData != null
                && this.spawnsData.has("fixedClass")
                && this.spawnsData.get("fixedClass").getAsBoolean();
    }

    private String playerTeam() {
        return this.currentUiData != null && this.currentUiData.has("playerTeam")
                ? this.currentUiData.get("playerTeam").getAsString()
                : "";
    }

    private boolean canSpawnAt(String ptName) {
        if (ptName.equals("BASE")) return true;

        JsonObject liveData = getLivePointData(ptName);
        if (liveData != null) {
            String owner = liveData.has("o") ? liveData.get("o").getAsString() : "NEUTRAL";
            int progress = liveData.has("p") ? liveData.get("p").getAsInt() : 0;
            int threshold =
                    this.currentUiData != null && this.currentUiData.has("safeSpawnThreshold")
                            ? this.currentUiData.get("safeSpawnThreshold").getAsInt()
                            : 90;
            if (this.minecraft != null && this.minecraft.player != null) {
                String playerTeam = playerTeam();
                if ("RED".equals(owner) && "RedTeam".equals(playerTeam) && progress >= threshold)
                    return true;
                if ("BLUE".equals(owner) && "BlueTeam".equals(playerTeam) && progress >= threshold)
                    return true;
                return false;
            }
        }
        return false;
    }

    private List<Component> getSpawnBlockReason(String ptName) {
        List<Component> tt = new ArrayList<>();
        JsonObject liveData = getLivePointData(ptName);
        if (liveData == null) {
            tt.add(Component.literal("Нейтральная точка").withColor(BfTheme.MUTED));
            return tt;
        }

        String owner = liveData.has("o") ? liveData.get("o").getAsString() : "NEUTRAL";
        int progress = liveData.has("p") ? liveData.get("p").getAsInt() : 0;
        int threshold =
                this.currentUiData != null && this.currentUiData.has("safeSpawnThreshold")
                        ? this.currentUiData.get("safeSpawnThreshold").getAsInt()
                        : 90;
        String playerTeam = playerTeam();

        String expectedOwner = "NEUTRAL";
        if ("RedTeam".equals(playerTeam)) expectedOwner = "RED";
        else if ("BlueTeam".equals(playerTeam)) expectedOwner = "BLUE";

        if ("NEUTRAL".equals(owner)) {
            tt.add(Component.literal("Нейтральная точка").withColor(BfTheme.MUTED));
        } else if (!owner.equals(expectedOwner)) {
            tt.add(Component.literal("Захвачена врагом").withColor(BfTheme.DANGER));
        } else if (progress < threshold) {
            tt.add(Component.literal("На точке идёт бой").withColor(BfTheme.DANGER));
            tt.add(
                    Component.literal(
                                    "Прогресс защиты: "
                                            + progress
                                            + "% (требуется "
                                            + threshold
                                            + "%)")
                            .withColor(BfTheme.TEXT_SECONDARY));
        }
        return tt;
    }

    /** Сколько строк помещается над кнопками; pure helper для тестов. */
    static int rowsPerPage(int listTop, int listBottom) {
        int capacity = (listBottom - listTop + ROW_GAP) / (ROW_HEIGHT + ROW_GAP);
        return Math.max(1, capacity);
    }

    /** Фокус по Tab: доступные точки страницы, затем действия; locked пропускаются. */
    static String cycleFocus(List<String> order, String current, boolean forward) {
        if (order.isEmpty()) return null;
        int at = current == null ? -1 : order.indexOf(current);
        if (at < 0) return forward ? order.get(0) : order.get(order.size() - 1);
        int next = (at + (forward ? 1 : -1) + order.size()) % order.size();
        return order.get(next);
    }

    private Layout layout(double mouseX, double mouseY) {
        float scale =
                Math.min(1.0f, Math.min(this.width / TARGET_WIDTH, this.height / TARGET_HEIGHT));
        int virtualWidth = (int) (this.width / scale);
        int virtualHeight = (int) (this.height / scale);

        float minX = -500;
        float maxX = 500;
        float minZ = -500;
        float maxZ = 500;
        JsonObject b = this.spawnsData == null ? null : UiJson.obj(this.spawnsData, "bounds");
        if (b != null) {
            minX = (float) UiJson.decimal(b, "minX", minX);
            maxX = (float) UiJson.decimal(b, "maxX", maxX);
            minZ = (float) UiJson.decimal(b, "minZ", minZ);
            maxZ = (float) UiJson.decimal(b, "maxZ", maxZ);
        }

        float worldW = maxX - minX;
        float worldH = maxZ - minZ;
        if (worldW <= 0) worldW = 1;
        if (worldH <= 0) worldH = 1;
        float worldAspect = worldW / worldH;

        Box mapArea =
                new Box(
                        LIST_X + LIST_WIDTH + 20,
                        62,
                        virtualWidth - LIST_X - LIST_WIDTH - 48,
                        virtualHeight - 124);
        int maxMapW = mapArea.w() - 12;
        int maxMapH = mapArea.h() - 12;

        int mapW = maxMapW;
        int mapH = (int) (mapW / worldAspect);
        if (mapH > maxMapH) {
            mapH = maxMapH;
            mapW = (int) (mapH * worldAspect);
        }
        int mapX = mapArea.x() + 6 + (maxMapW - mapW) / 2;
        int mapY = mapArea.y() + 6 + (maxMapH - mapH) / 2;

        Box deploy = new Box(LIST_X, virtualHeight - 50, LIST_WIDTH, 30);
        Box changeClass = new Box(LIST_X, deploy.y() - 30, LIST_WIDTH, 20);
        // Reserve the status line above the actions before fitting the list and its pager.
        int listBottom = deploy.y() - 44 - 8;
        List<String> names = new ArrayList<>();
        names.add("BASE");
        java.util.Map<String, int[]> coords = new java.util.HashMap<>();
        int baseMx = mapX + 40;
        int baseMy = mapY + mapH - 40;
        coords.put("BASE", new int[] {baseMx, baseMy});

        if (this.spawnsData != null
                && this.spawnsData.has("pts")
                && this.spawnsData.get("pts").isJsonArray()) {
            JsonArray pts = UiJson.arr(this.spawnsData, "pts");
            for (int i = 0; i < pts.size(); i++) {
                JsonObject pt = UiJson.asObject(pts.get(i));
                if (pt == null) continue;
                String pName = pt.has("n") ? pt.get("n").getAsString() : "Точка " + i;
                float px = pt.has("x") ? pt.get("x").getAsFloat() : 0;
                float pz = pt.has("z") ? pt.get("z").getAsFloat() : 0;
                int mx = mapX + (int) ((px - minX) / (maxX - minX) * mapW);
                int my = mapY + (int) ((pz - minZ) / (maxZ - minZ) * mapH);

                if (pName.equals("BASE")) {
                    coords.put("BASE", new int[] {mx, my});
                    continue;
                }
                if (!names.contains(pName)) names.add(pName);
                coords.put(pName, new int[] {mx, my});
            }
        }

        int perPage = rowsPerPage(LIST_Y, listBottom);
        int pages = Math.max(1, (int) Math.ceil((double) names.size() / perPage));
        if (pages > 1) {
            // Кнопки страниц отнимают строку: пересчитываем с их учётом.
            perPage = rowsPerPage(LIST_Y, listBottom - PAGE_BUTTON_H - 6);
            pages = Math.max(1, (int) Math.ceil((double) names.size() / perPage));
        }
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;

        List<Row> rows = new ArrayList<>();
        List<Marker> markers = new ArrayList<>();
        int from = page * perPage;
        int to = Math.min(names.size(), from + perPage);
        int rowY = LIST_Y;
        for (int i = from; i < to; i++) {
            String pName = names.get(i);
            rows.add(new Row(pName, new Box(LIST_X, rowY, LIST_WIDTH, ROW_HEIGHT)));
            rowY += ROW_HEIGHT + ROW_GAP;
        }
        for (String pName : names) {
            int[] c = coords.get(pName);
            if (c != null) markers.add(new Marker(pName, c[0], c[1]));
        }
        if (isFfa()) markers.removeIf(m -> m.name().equals("BASE"));
        else if (!markers.isEmpty() && !markers.get(0).name().equals("BASE")) {
            for (int i = 0; i < markers.size(); i++) {
                if (markers.get(i).name().equals("BASE")) {
                    Marker base = markers.remove(i);
                    markers.add(0, base);
                    break;
                }
            }
        }

        Box prev = null;
        Box next = null;
        if (pages > 1) {
            int by = listBottom - PAGE_BUTTON_H;
            prev = new Box(LIST_X, by, 60, PAGE_BUTTON_H);
            next = new Box(LIST_X + LIST_WIDTH - 60, by, 60, PAGE_BUTTON_H);
        }

        return new Layout(
                scale,
                (int) (mouseX / scale),
                (int) (mouseY / scale),
                virtualWidth,
                virtualHeight,
                mapX,
                mapY,
                mapW,
                mapH,
                mapArea,
                rows,
                markers,
                deploy,
                changeClass,
                page,
                pages,
                prev,
                next);
    }

    /** Порядок Tab: доступные точки страницы, кнопки страниц, затем действия. */
    private List<String> focusOrder(Layout l) {
        List<String> order = new ArrayList<>();
        for (Row row : l.rows()) {
            boolean canSpawn = row.name().equals("BASE") || canSpawnAt(row.name());
            if (canSpawn) order.add("spawn:" + row.name());
        }
        if (l.pages() > 1) {
            if (l.page() > 0) order.add("page:prev");
            if (l.page() + 1 < l.pages()) order.add("page:next");
        }
        if (canSpawnAt(selectedSpawnId) && (!isDead() || deathTimer() <= 0))
            order.add("action:deploy");
        if (!fixedClass()) order.add("action:class");
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
        if (id.startsWith("spawn:")) {
            String pt = id.substring("spawn:".length());
            if (pt.equals("BASE") || canSpawnAt(pt)) {
                select(pt);
                return true;
            }
            return false;
        }
        if (id.equals("action:deploy")) {
            if (canSpawnAt(selectedSpawnId) && (!isDead() || deathTimer() <= 0)) {
                if (this.minecraft != null && this.minecraft.player != null) {
                    ClientPlayNetworking.send(new MenuActionC2SPacket("spawn:" + selectedSpawnId));
                    Tablet.closeDeployment();
                }
            }
            return true;
        }
        if (id.equals("action:class") && !fixedClass()) {
            if (this.minecraft != null && this.minecraft.player != null) {
                ClientPlayNetworking.send(new MenuActionC2SPacket("menu_class"));
            }
            return true;
        }
        return false;
    }

    private boolean markerHit(Layout l, Marker m) {
        return BfTheme.hit(
                l.mouseX(),
                l.mouseY(),
                m.x() - MARKER_HALF,
                m.y() - MARKER_HALF,
                MARKER_HALF * 2,
                MARKER_HALF * 2);
    }

    private int teamColor(String owner) {
        if ("RED".equals(owner)) return BfTheme.RED_TEAM;
        if ("BLUE".equals(owner)) return BfTheme.BLUE_TEAM;
        return BfTheme.NEUTRAL;
    }

    private boolean isDead() {
        return this.currentUiData != null
                && this.currentUiData.has("dead")
                && this.currentUiData.get("dead").getAsBoolean();
    }

    private int deathTimer() {
        return this.currentUiData != null && this.currentUiData.has("deathTimer")
                ? this.currentUiData.get("deathTimer").getAsInt()
                : 0;
    }

    @Override
    protected void init() {
        this.font = BfTheme.font();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, BfTheme.SCRIM);
        // A remembered or picked point can be lost meanwhile; BASE is always available.
        if (currentUiData != null && !canSpawnAt(selectedSpawnId)) selectedSpawnId = "BASE";

        Layout l = layout(mouseX, mouseY);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(l.scale(), l.scale(), 1.0f);

        BfTheme.panel(guiGraphics, 22, 22, l.width() - 44, l.height() - 34, false);
        guiGraphics.fill(23, 23, l.width() - 23, 54, BfTheme.BG_ELEVATED);
        BfIcon.MAP.draw(guiGraphics, LIST_X, 31, 16, BfTheme.AMBER);
        guiGraphics.drawString(this.font, "ВЫСАДКА", LIST_X + 26, 35, BfTheme.AMBER, false);
        guiGraphics.fill(23, 54, LIST_X + LIST_WIDTH, 56, BfTheme.AMBER);
        var zone = com.lunares.blockfield.client.gui.tablet.TabletMapTab.zone();
        String mapName = zone != null ? UiJson.str(zone, "name", this.mapId) : this.mapId;
        mapName = BfTheme.ellipsize(this.font, mapName, l.width() - 320);
        guiGraphics.drawString(
                this.font,
                mapName,
                l.width() - 40 - this.font.width(mapName),
                35,
                BfTheme.MUTED,
                false);
        guiGraphics.fill(
                LIST_X + LIST_WIDTH + 10,
                62,
                LIST_X + LIST_WIDTH + 11,
                l.height() - 62,
                BfTheme.BORDER_SOFT);
        guiGraphics.fill(23, l.height() - 58, l.width() - 23, l.height() - 57, BfTheme.BORDER_SOFT);

        int pulse =
                BfTheme.mix(
                        BfTheme.AMBER_DIM,
                        BfTheme.AMBER,
                        0.65f + 0.35f * (float) Math.sin(System.nanoTime() / 400_000_000d));
        String playerTeam = playerTeam();

        ResourceLocation mapTexture =
                com.lunares.blockfield.client.gui.tablet.TabletMapTab.snapshot(
                        this.minecraft, this.mapId);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        if (mapTexture != null) {
            guiGraphics.blit(
                    mapTexture, l.mapX(), l.mapY(), 0, 0, l.mapW(), l.mapH(), l.mapW(), l.mapH());
            guiGraphics.flush();
            BfTheme.outline(guiGraphics, l.mapX(), l.mapY(), l.mapW(), l.mapH(), BfTheme.BORDER);
            BfTheme.ticks(guiGraphics, l.mapX(), l.mapY(), l.mapW(), l.mapH(), 5, BfTheme.AMBER);
        } else {
            com.lunares.blockfield.client.gui.tablet.MapLoading.render(
                    guiGraphics,
                    zone,
                    l.mapArea().x(),
                    l.mapArea().y(),
                    l.mapArea().w(),
                    l.mapArea().h());
        }

        BfTheme.header(guiGraphics, this.font, LIST_X, 66, LIST_WIDTH, "ТОЧКИ ВЫСАДКИ", "");
        if (currentUiData != null
                && currentUiData.has("battleCredits")
                && currentUiData.has("deploymentCost")) {
            long balance = (long) currentUiData.get("battleCredits").getAsDouble();
            int price = currentUiData.get("deploymentCost").getAsInt();
            guiGraphics.drawString(
                    this.font,
                    price + " БК · баланс " + balance + " БК",
                    LIST_X,
                    82,
                    balance >= price ? BfTheme.MUTED : BfTheme.DANGER,
                    false);
        }

        List<Component> tooltipToRender = null;
        String hoveredPtId = null;

        for (Row row : l.rows()) {
            Box r = row.rect();
            boolean isBase = row.name().equals("BASE");
            boolean selected = selectedSpawnId.equals(row.name());
            boolean canSpawn = isBase || canSpawnAt(row.name());
            boolean hovered = BfTheme.hit(l.mouseX(), l.mouseY(), r.x(), r.y(), r.w(), r.h());
            if (hovered) hoveredPtId = row.name();
            float hover = hover(row.name(), hovered && canSpawn);
            boolean focused = ("spawn:" + row.name()).equals(focusedId);

            if (!canSpawn && !selected) {
                guiGraphics.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), BfTheme.BG_ELEVATED);
                BfTheme.outline(guiGraphics, r.x(), r.y(), r.w(), r.h(), BfTheme.BORDER_SOFT);
            } else {
                BfTheme.panel(guiGraphics, r.x(), r.y(), r.w(), r.h(), false);
                guiGraphics.fill(
                        r.x() + 1,
                        r.y() + 1,
                        r.x() + r.w() - 1,
                        r.y() + r.h() - 1,
                        BfTheme.mix(BfTheme.CARD, BfTheme.POPOVER, hover));
                if (selected) {
                    BfTheme.outline(guiGraphics, r.x(), r.y(), r.w(), r.h(), pulse);
                    guiGraphics.fill(r.x(), r.y(), r.x() + 2, r.y() + r.h(), BfTheme.AMBER);
                } else if (hovered || focused) {
                    BfTheme.outline(guiGraphics, r.x(), r.y(), r.w(), r.h(), BfTheme.BORDER_HOVER);
                }
            }
            if (focused) {
                BfTheme.outline(
                        guiGraphics, r.x() - 1, r.y() - 1, r.w() + 2, r.h() + 2, BfTheme.TEXT);
            }

            int textColor =
                    selected
                            ? BfTheme.TEXT
                            : (!canSpawn
                                    ? BfTheme.MUTED
                                    : (hovered ? BfTheme.TEXT : BfTheme.TEXT_SECONDARY));
            if (isBase) {
                int dot =
                        isFfa()
                                ? BfTheme.OK
                                : "RedTeam".equals(playerTeam)
                                        ? BfTheme.RED_TEAM
                                        : ("BlueTeam".equals(playerTeam)
                                                ? BfTheme.BLUE_TEAM
                                                : BfTheme.OK);
                BfTheme.statusDot(guiGraphics, r.x() + 9, r.y() + r.h() / 2 - 1, dot);
                String baseLabel = isFfa() ? "СЛУЧАЙНАЯ ТОЧКА" : "ГЛАВНАЯ БАЗА";
                guiGraphics.drawString(
                        this.font,
                        BfTheme.ellipsize(this.font, baseLabel, r.w() - 24),
                        r.x() + 18,
                        r.y() + 11,
                        textColor,
                        false);
                if (isFfa() && hovered) {
                    tooltipToRender =
                            List.of(
                                    Component.literal(
                                                    "Сервер выберет безопасную точку подальше от противников")
                                            .withColor(BfTheme.TEXT_SECONDARY));
                }
            } else {
                String label =
                        "Точка "
                                + com.lunares.blockfield.client.render.PointMarkerRenderer
                                        .displayName(row.name());
                guiGraphics.drawString(
                        this.font,
                        BfTheme.ellipsize(this.font, label, r.w() - 20),
                        r.x() + 13,
                        r.y() + 11,
                        textColor,
                        false);
                if (hovered || focused) {
                    tooltipToRender = new ArrayList<>();
                    tooltipToRender.add(Component.literal(label).withColor(BfTheme.TEXT));
                    if (!canSpawn) tooltipToRender.addAll(getSpawnBlockReason(row.name()));
                }
            }
        }

        for (Marker m : l.markers()) {
            if (mapTexture == null) continue;
            boolean isBase = m.name().equals("BASE");
            boolean hovered = markerHit(l, m);
            if (hovered) hoveredPtId = m.name();

            JsonObject liveData = isBase ? null : getLivePointData(m.name());
            int color;
            if (isBase) {
                color =
                        "RedTeam".equals(playerTeam)
                                ? BfTheme.RED_TEAM
                                : ("BlueTeam".equals(playerTeam) ? BfTheme.BLUE_TEAM : BfTheme.OK);
            } else {
                color =
                        liveData != null && liveData.has("o")
                                ? teamColor(liveData.get("o").getAsString())
                                : BfTheme.NEUTRAL;
            }
            if (selectedSpawnId.equals(m.name())) {
                color = pulse;
            }

            if (isBase) {
                // Флажок базы: древко + полотнище командного цвета — читается лучше буквы.
                int cx = m.x(), cy = m.y();
                guiGraphics.fill(cx - 1, cy - 8, cx, cy + 6, BfTheme.TEXT);
                guiGraphics.fill(cx, cy - 8, cx + 8, cy - 2, color);
                guiGraphics.fill(cx, cy - 2, cx + 5, cy + 1, color);
            } else {
                guiGraphics.fill(m.x() - 7, m.y() - 7, m.x() + 7, m.y() + 7, BfTheme.BG);
                guiGraphics.fill(m.x() - 6, m.y() - 6, m.x() + 6, m.y() + 6, color);
                guiGraphics.drawCenteredString(
                        this.font, m.name().substring(0, 1), m.x(), m.y() - 4, BfTheme.TEXT);
            }

            if (hovered || m.name().equals(hoveredPtId)) {
                BfTheme.outline(guiGraphics, m.x() - 8, m.y() - 8, 16, 16, BfTheme.TEXT);
            }

            if (hovered) {
                List<Component> tt = new ArrayList<>();
                if (isBase) {
                    tt.add(Component.literal("Главная база").withColor(BfTheme.AMBER));
                } else {
                    tt.add(
                            Component.literal(
                                            "Точка "
                                                    + com.lunares.blockfield.client.render
                                                            .PointMarkerRenderer.displayName(
                                                            m.name()))
                                    .withColor(BfTheme.AMBER));
                    if (liveData != null && liveData.has("o")) {
                        String owner = liveData.get("o").getAsString();
                        int progress = liveData.has("p") ? liveData.get("p").getAsInt() : 0;
                        if ("NEUTRAL".equals(owner)) {
                            tt.add(
                                    Component.literal("Нейтральная точка (" + progress + "%)")
                                            .withColor(BfTheme.MUTED));
                        } else {
                            String expectedOwner =
                                    "RedTeam".equals(playerTeam)
                                            ? "RED"
                                            : ("BlueTeam".equals(playerTeam) ? "BLUE" : "NEUTRAL");
                            if (owner.equals(expectedOwner)) {
                                tt.add(
                                        Component.literal("Удерживается нами: " + progress + "%")
                                                .withColor(BfTheme.OK));
                            } else {
                                tt.add(
                                        Component.literal("Удерживается врагом: " + progress + "%")
                                                .withColor(BfTheme.DANGER));
                            }
                        }
                    }
                    if (!canSpawnAt(m.name())) {
                        tt.addAll(getSpawnBlockReason(m.name()));
                        tt.add(Component.literal("Высадка недоступна").withColor(BfTheme.DANGER));
                    }
                }
                tooltipToRender = tt;
            }
        }

        boolean isDead = isDead();
        int deathTimer = deathTimer();
        boolean canDeploy = canSpawnAt(selectedSpawnId) && (!isDead || deathTimer <= 0);

        if (l.pages() > 1) {
            String pageLabel = (l.page() + 1) + " / " + l.pages();
            int labelW = this.font.width(pageLabel);
            int labelX = l.prevPage().x() + (LIST_WIDTH - labelW) / 2;
            guiGraphics.drawString(
                    this.font, pageLabel, labelX, l.prevPage().y() + 6, BfTheme.MUTED, false);
            BfTheme.button(
                    guiGraphics,
                    this.font,
                    l.prevPage().x(),
                    l.prevPage().y(),
                    l.prevPage().w(),
                    l.prevPage().h(),
                    "←",
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
                    "→",
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

        Box deploy = l.deploy();
        guiGraphics.drawCenteredString(
                this.font,
                DeploymentCountdown.text(currentUiData),
                deploy.x() + deploy.w() / 2,
                deploy.y() - 44,
                BfTheme.AMBER);
        String deployText =
                (isDead && deathTimer > 0) ? "ВОЗРОЖДЕНИЕ ЧЕРЕЗ " + deathTimer + "с" : "ВЫСАДИТЬСЯ";
        BfTheme.button(
                guiGraphics,
                this.font,
                deploy.x(),
                deploy.y(),
                deploy.w(),
                deploy.h(),
                deployText,
                BfTheme.hit(l.mouseX(), l.mouseY(), deploy.x(), deploy.y(), deploy.w(), deploy.h())
                        || "action:deploy".equals(focusedId),
                canDeploy,
                true);
        if ("action:deploy".equals(focusedId)) {
            BfTheme.outline(
                    guiGraphics,
                    deploy.x() - 1,
                    deploy.y() - 1,
                    deploy.w() + 2,
                    deploy.h() + 2,
                    BfTheme.TEXT);
        }

        Box changeClass = l.changeClass();
        if (!fixedClass()) {
            BfTheme.button(
                    guiGraphics,
                    this.font,
                    changeClass.x(),
                    changeClass.y(),
                    changeClass.w(),
                    changeClass.h(),
                    "СМЕНИТЬ КЛАСС",
                    BfTheme.hit(
                                    l.mouseX(),
                                    l.mouseY(),
                                    changeClass.x(),
                                    changeClass.y(),
                                    changeClass.w(),
                                    changeClass.h())
                            || "action:class".equals(focusedId),
                    true,
                    false);
            if ("action:class".equals(focusedId)) {
                BfTheme.outline(
                        guiGraphics,
                        changeClass.x() - 1,
                        changeClass.y() - 1,
                        changeClass.w() + 2,
                        changeClass.h() + 2,
                        BfTheme.TEXT);
            }
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
            Box r = row.rect();
            if (BfTheme.hit(l.mouseX(), l.mouseY(), r.x(), r.y(), r.w(), r.h())) {
                if (canSpawnAt(row.name())) {
                    select(row.name());
                }
                return true;
            }
        }

        for (Marker m : l.markers()) {
            if (com.lunares.blockfield.client.gui.tablet.TabletMapTab.snapshot(
                            this.minecraft, this.mapId)
                    == null) break;
            if (markerHit(l, m)) {
                if (canSpawnAt(m.name())) {
                    select(m.name());
                }
                return true;
            }
        }

        Box deploy = l.deploy();
        if (BfTheme.hit(l.mouseX(), l.mouseY(), deploy.x(), deploy.y(), deploy.w(), deploy.h())) {
            if (canSpawnAt(selectedSpawnId) && (!isDead() || deathTimer() <= 0)) {
                if (this.minecraft != null && this.minecraft.player != null) {
                    ClientPlayNetworking.send(new MenuActionC2SPacket("spawn:" + selectedSpawnId));
                    Tablet.closeDeployment();
                }
            }
            return true;
        }

        Box changeClass = l.changeClass();
        if (!fixedClass()
                && BfTheme.hit(
                        l.mouseX(),
                        l.mouseY(),
                        changeClass.x(),
                        changeClass.y(),
                        changeClass.w(),
                        changeClass.h())) {
            if (this.minecraft != null && this.minecraft.player != null) {
                // The class choice replaces this screen on the tablet's deployment tab.
                ClientPlayNetworking.send(new MenuActionC2SPacket("menu_class"));
            }
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
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
            focusedId = cycleFocus(focusOrder(l), focusedId, forward);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (focusedId == null) focusedId = cycleFocus(focusOrder(l), null, true);
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
        } catch (Exception e) {
        }
    }

    /** Повторная выдача с сервера сохраняет выделение только на той же карте. */
    public void updateSpawnsData(String spawnsJson, String mapId) {
        try {
            this.spawnsData = UiJson.parse(spawnsJson);
        } catch (Exception e) {
            return;
        }
        if (!java.util.Objects.equals(this.mapId, mapId)) {
            selectedSpawnId = LAST_SELECTION.forMap(mapId);
            currentUiData = null;
            focusedId = null;
            page = 0;
        }
        this.mapId = mapId;
    }
}
