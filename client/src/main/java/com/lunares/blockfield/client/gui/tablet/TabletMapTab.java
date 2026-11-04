package com.lunares.blockfield.client.gui.tablet;

import com.google.gson.JsonObject;
import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.UiMotion;
import com.lunares.blockfield.client.render.HudRenderer;
import com.lunares.blockfield.client.render.PointMarkerRenderer;
import com.lunares.blockfield.network.packet.MapPingC2SPacket;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Вкладка «Карта»: зона целиком, готовым снимком с сервера и серверными позициями союзников. Старые
 * серверы используют снимок из ресурсов (`textures/gui/map_<id>.png`). Границы зоны приходят с
 * сервера — за зону планшет не показывает ничего. Позиции противников приходят только после
 * серверного обнаружения.
 */
public final class TabletMapTab {

    private TabletMapTab() {}

    private static final ResourceLocation SELF_ARROW =
            ResourceLocation.fromNamespaceAndPath("blockfield", "textures/gui/player_arrow.png");

    private static final MapPlayerMotion playerMotion = new MapPlayerMotion();

    public static void updatePlayers(JsonObject map) {
        playerMotion.update(map, System.nanoTime() / 1_000_000);
    }

    public static void clearPlayers() {
        playerMotion.clear();
    }

    /** Вне боя (хаб, полигон) сервер границ не присылает и вкладка скрыта. */
    public static boolean available() {
        return zone() != null;
    }

    /** Границы боевой зоны из UI-пакета матча; null — игрок не в бою. */
    public static JsonObject zone() {
        JsonObject map = HudRenderer.matchMap();
        if (map == null) return null;
        for (String key : new String[] {"minX", "maxX", "minZ", "maxZ"}) {
            if (!map.has(key)) return null;
        }
        return map;
    }

    private static final float MAX_ZOOM_LOG = 3f;

    /** Приближение храним логарифмом: шаг колеса тогда одинаков на любом масштабе. */
    private static final UiMotion zoomLog = new UiMotion();

    private static float zoomTarget;
    private static float anchorX, anchorY, anchorU = 0.5f, anchorV = 0.5f;

    /** Каждое открытие планшета начинается с карты целиком. */
    public static void resetZoom() {
        zoomTarget = 0f;
        zoomLog.snap(0f);
    }

    /** Вид карты внутри экрана планшета; null -- боевой карты сейчас нет. */
    private static MapView view(TabletFrame f) {
        JsonObject map = zone();
        if (map == null) return null;
        JsonObject base = map.has("base") ? map.getAsJsonObject("base") : null;
        return MapView.of(
                map.get("minX").getAsDouble(),
                map.get("minZ").getAsDouble(),
                map.get("maxX").getAsDouble(),
                map.get("maxZ").getAsDouble(),
                base != null && base.has("z") ? base.get("z").getAsDouble() : Double.NaN,
                f.contentX() + 3,
                f.contentY() + 3,
                f.contentW() - 6,
                f.contentH() - 6,
                (float) Math.pow(2, zoomLog.value(System.nanoTime())),
                anchorX,
                anchorY,
                anchorU,
                anchorV);
    }

    /**
     * Колесо приближает к точке под курсором; сам ход масштаба доигрывает {@link UiMotion} по
     * времени кадра.
     */
    public static boolean zoom(double mouseX, double mouseY, double scrollY, TabletFrame f) {
        MapView view = view(f);
        if (view == null || !f.inContent(mouseX, mouseY) || scrollY == 0) return false;
        anchorX = (float) mouseX;
        anchorY = (float) mouseY;
        anchorU = Mth.clamp((anchorX - view.x()) / view.w(), 0f, 1f);
        anchorV = Mth.clamp((anchorY - view.y()) / view.h(), 0f, 1f);
        zoomTarget = Mth.clamp(zoomTarget + (float) Math.signum(scrollY) * 0.5f, 0f, MAX_ZOOM_LOG);
        zoomLog.to(zoomTarget, System.nanoTime());
        return true;
    }

    public static boolean beginPan(double mouseX, double mouseY, TabletFrame f) {
        MapView view = view(f);
        if (view == null || !f.inContent(mouseX, mouseY) || !view.onMap(mouseX, mouseY))
            return false;
        zoomTarget = zoomLog.value(System.nanoTime());
        zoomLog.snap(zoomTarget);
        return true;
    }

    public static boolean pan(double dx, double dy, TabletFrame f) {
        MapView view = view(f);
        if (view == null) return false;
        MapView moved =
                view.pan(
                        (float) dx,
                        (float) dy,
                        f.contentX() + 3,
                        f.contentY() + 3,
                        f.contentW() - 6,
                        f.contentH() - 6);
        anchorX = moved.x();
        anchorY = moved.y();
        anchorU = 0;
        anchorV = 0;
        return true;
    }

    /**
     * Средняя кнопка по карте ставит метку команде: клиент шлёт только точку, высоту и допуск (в
     * зоне ли она, не слишком ли часто) проверяет сервер.
     */
    public static boolean ping(double screenX, double screenY, TabletFrame f) {
        MapView view = view(f);
        if (view == null || !f.inContent(screenX, screenY) || !view.onMap(screenX, screenY))
            return false;
        JsonObject map = zone();
        if (snapshot(Minecraft.getInstance(), map.has("id") ? map.get("id").getAsString() : "")
                == null) return false;
        if (!ClientPlayNetworking.canSend(MapPingC2SPacket.TYPE)) return false;
        double[] world = view.world(screenX, screenY);
        ClientPlayNetworking.send(new MapPingC2SPacket(world[0], world[1]));
        return true;
    }

    /** Close zoom fades in block textures for the part of the image inside the frame. */
    private static void detail(
            GuiGraphics g, Minecraft mc, JsonObject map, MapView view, TabletFrame f) {
        var image = MapSnapshots.terrain(mc, map);
        if (image == null) return;
        int w = image.header().width(), h = image.header().height();
        float alpha = MapDetail.alpha(view.w() / w * (float) mc.getWindow().getGuiScale());
        if (alpha <= 0) return;
        float u0 = (f.contentX() - view.x()) / view.w() * w;
        float u1 = (f.contentX() + f.contentW() - view.x()) / view.w() * w;
        float v0 = (f.contentY() - view.y()) / view.h() * h;
        float v1 = (f.contentY() + f.contentH() - view.y()) / view.h() * h;
        // A flipped map is rotated 180°: screen corners land on the opposite image corners.
        if (view.flipped()) {
            float u = u0, v = v0;
            u0 = w - u1;
            u1 = w - u;
            v0 = h - v1;
            v1 = h - v;
        }
        MapDetail.render(
                g,
                image,
                (int) Math.floor(u0),
                (int) Math.ceil(u1),
                (int) Math.floor(v0),
                (int) Math.ceil(v1),
                alpha);
    }

    public static void render(GuiGraphics g, TabletFrame f, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        g.fill(
                f.contentX(),
                f.contentY(),
                f.contentX() + f.contentW(),
                f.contentY() + f.contentH(),
                BfTheme.BG);
        JsonObject map = zone();
        if (map == null) {
            g.drawCenteredString(
                    mc.font,
                    "НЕТ ДАННЫХ КАРТЫ",
                    f.contentX() + f.contentW() / 2,
                    f.contentY() + f.contentH() / 2 - 4,
                    BfTheme.MUTED);
            return;
        }

        MapView view = view(f);
        int x = Math.round(view.x());
        int y = Math.round(view.y());
        int w = Math.round(view.w());
        int h = Math.round(view.h());
        // Приближенная карта шире рамки: всё, что за ней, срезаем.
        g.enableScissor(
                f.contentX(),
                f.contentY(),
                f.contentX() + f.contentW(),
                f.contentY() + f.contentH());

        String id = map.has("id") ? map.get("id").getAsString() : "";
        ResourceLocation texture = snapshot(mc, id);
        if (texture != null) {
            // Дробные сдвиг и масштаб через pose: с целыми координатами blit приближение идёт
            // рывками.
            PoseStack pose = g.pose();
            pose.pushPose();
            pose.translate(view.x() + view.w() / 2f, view.y() + view.h() / 2f, 0);
            if (view.flipped()) pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180f));
            pose.scale(view.w() / 256f, view.h() / 256f, 1f);
            g.blit(texture, -128, -128, 0, 0, 256, 256, 256, 256);
            detail(g, mc, map, view, f);
            pose.popPose();
        } else {
            MapLoading.render(
                    g, map, f.contentX() + 3, f.contentY() + 3, f.contentW() - 6, f.contentH() - 6);
            g.disableScissor();
            return;
        }
        BfTheme.outline(g, x, y, w, h, BfTheme.BORDER);
        BfTheme.ticks(g, x, y, w, h, 5, BfTheme.AMBER);
        // Снимок -- текстурный квад, а он выливается позже цветных: без этого маркеры и подпись,
        // нарисованные после него, оказывались под картой (видно на картах без точек захвата).
        g.flush();
        if (map.has("base")) {
            JsonObject base = map.getAsJsonObject("base");
            if (base.has("x") && base.has("z")) {
                float[] at =
                        view.clampedScreen(
                                base.get("x").getAsDouble(), base.get("z").getAsDouble());
                drawBase(g, at[0], at[1], teamColor(HudRenderer.playerTeam()));
            }
        }

        boolean workshop = WorkshopMarks.active(map);
        if (workshop) drawWorkshop(g, view, map, x, y, w, h);

        for (PointMarkerRenderer.Marker marker : PointMarkerRenderer.markers()) {
            if (!view.contains(marker.x(), marker.z())) continue;
            drawPointZone(g, view, marker);
            float[] at = view.screen(marker.x(), marker.z());
            PointMarkerRenderer.drawMarker(g, marker, Math.round(at[0]), Math.round(at[1]), 0xF0);
        }

        for (PointMarkerRenderer.Marker ping : MapPings.markers()) {
            if (!view.contains(ping.x(), ping.z())) continue;
            float[] at = view.screen(ping.x(), ping.z());
            int px = Math.round(at[0]);
            int py = Math.round(at[1]);
            PointMarkerRenderer.drawMarker(g, ping, px, py, 0xF0);
            int width = mc.font.width(ping.name());
            g.fill(
                    px - width / 2 - 2,
                    py + 9,
                    px + width / 2 + 2,
                    py + 20,
                    BfTheme.alpha(BfTheme.BG, 0xB0));
            g.drawString(
                    mc.font, ping.name(), px - width / 2, py + 11, BfTheme.AMBER_BRIGHT, false);
        }

        drawPlayers(g, mc, view, workshop);
        // Свой маркер и координаты — только живому в бою: убитый в посмертном лобби остаётся
        // в том же измерении, поэтому проверки измерения мало — сервер присылает ему dead
        // (см. HudRenderer.isDeployed). Чужие карты мёртвых уже не видят: MapTeammates шлёт
        // только живых.
        boolean onBattlefield =
                mc.player != null && showSelf(map, HudRenderer.isDeployed(), levelDimension(mc));
        if (onBattlefield) {
            float[] at = view.clampedScreen(mc.player.getX(), mc.player.getZ());
            drawSelf(g, at[0], at[1], mc.player.getYRot() + (view.flipped() ? 180f : 0f));
        }
        g.disableScissor();

        if (view.flipped()) drawNorth(g, mc, f);
        if (workshop) drawCursor(g, mc, f, view, mouseX, mouseY);
        if (onBattlefield) {
            String coords = Math.round(mc.player.getX()) + " / " + Math.round(mc.player.getZ());
            // Подпись поверх снимка: без тёмной подложки цифры теряются на светлых участках карты.
            int width = mc.font.width(coords) + 6;
            int top = f.contentY() + f.contentH() - 11;
            g.fill(
                    f.contentX(),
                    top,
                    f.contentX() + width,
                    top + 11,
                    BfTheme.alpha(BfTheme.BG, 0xC0));
            g.drawString(mc.font, coords, f.contentX() + 3, top + 2, BfTheme.TEXT_SECONDARY, false);
        }
    }

    /** Измерение уровня клиента строкой; null — уровня нет. */
    private static String levelDimension(Minecraft mc) {
        return mc.level == null ? null : mc.level.dimension().location().toString();
    }

    /** Границы боя из UI-пакета относятся к этому измерению; без поля dimension сервер старый. */
    static boolean dimensionMatches(JsonObject map, String levelDimension) {
        return map != null
                && (!map.has("dimension")
                        || (levelDimension != null
                                && map.get("dimension").getAsString().equals(levelDimension)));
    }

    /**
     * Свой маркер и подпись координат — только живому в бою ({@code deployed} — серверный флаг
     * {@code dead}, см. HudRenderer.isDeployed).
     */
    static boolean showSelf(JsonObject map, boolean deployed, String levelDimension) {
        return deployed && dimensionMatches(map, levelDimension);
    }

    /** Серверы до поля dimension границы слали только в бою -- считаем, что игрок на карте. */
    private static boolean inMapDimension(Minecraft mc, JsonObject map) {
        return mc.player != null && mc.level != null && dimensionMatches(map, levelDimension(mc));
    }

    /** Развёрнутая карта: север снизу, без метки стороны света не угадать. */
    private static void drawNorth(GuiGraphics g, Minecraft mc, TabletFrame f) {
        int x = f.contentX() + 3;
        int y = f.contentY() + 3;
        g.fill(x, y, x + 22, y + 11, BfTheme.alpha(BfTheme.BG, 0xC0));
        for (int i = 0; i < 4; i++)
            g.fill(x + 3 + i, y + 2 + i, x + 10 - i, y + 3 + i, BfTheme.AMBER);
        g.drawString(mc.font, "С", x + 12, y + 2, BfTheme.AMBER, false);
    }

    private static String snapshotId = "";
    private static ResourceLocation snapshotTexture;

    /** Наличие снимка проверяем раз на карту: поиск в ResourceManager каждый кадр стоит дорого. */
    public static ResourceLocation snapshot(Minecraft mc, String id) {
        return snapshot(mc, id, false);
    }

    public static ResourceLocation snapshot(Minecraft mc, String id, boolean minimap) {
        ResourceLocation live =
                minimap
                        ? MapSnapshots.minimapTexture(mc, zone())
                        : MapSnapshots.texture(mc, zone());
        if (live != null) return live;
        JsonObject map = zone();
        if (map != null && map.has("serverSnapshot") && map.get("serverSnapshot").getAsBoolean())
            return null;
        if (id.isEmpty()) return null;
        if (!id.equals(snapshotId)) {
            snapshotId = id;
            ResourceLocation candidate =
                    ResourceLocation.fromNamespaceAndPath(
                            "blockfield",
                            "textures/gui/map_" + id.toLowerCase(java.util.Locale.ROOT) + ".png");
            snapshotTexture =
                    mc.getResourceManager().getResource(candidate).isPresent() ? candidate : null;
        }
        return snapshotTexture;
    }

    /**
     * Zone of any shape (union of boxes): fill row by row of blocks, so overlapping boxes do not
     * darken, and the outline only along edges with no zone on the other side.
     */
    private static void drawPointZone(
            GuiGraphics g, MapView view, PointMarkerRenderer.Marker marker) {
        int[][] boxes = marker.boxes();
        if (boxes == null) return;
        int fill = BfTheme.alpha(marker.ownerColor(), 0x28),
                line = BfTheme.alpha(marker.ownerColor(), 0xB0);
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] b : boxes) {
            minZ = Math.min(minZ, b[1]);
            maxZ = Math.max(maxZ, b[3]);
        }
        List<int[]> spans = new java.util.ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) {
            spans.clear();
            for (int[] b : boxes) if (b[1] <= z && z <= b[3]) spans.add(new int[] {b[0], b[2]});
            spans.sort(java.util.Comparator.comparingInt(s -> s[0]));
            int from = Integer.MIN_VALUE, to = Integer.MIN_VALUE;
            for (int[] s : spans) {
                if (from != Integer.MIN_VALUE && s[0] <= to + 1) {
                    to = Math.max(to, s[1]);
                    continue;
                }
                if (from != Integer.MIN_VALUE) zoneRect(g, view, from, z, to + 1, z + 1, fill);
                from = s[0];
                to = s[1];
            }
            if (from != Integer.MIN_VALUE) zoneRect(g, view, from, z, to + 1, z + 1, fill);
        }
        for (int[] b : boxes) {
            for (int x = b[0]; x <= b[2]; x++) {
                if (!PointMarkerRenderer.covers(boxes, x + 0.5, b[1] - 0.5))
                    zoneLine(g, view, x, b[1], x + 1, b[1], line);
                if (!PointMarkerRenderer.covers(boxes, x + 0.5, b[3] + 1.5))
                    zoneLine(g, view, x, b[3] + 1, x + 1, b[3] + 1, line);
            }
            for (int z = b[1]; z <= b[3]; z++) {
                if (!PointMarkerRenderer.covers(boxes, b[0] - 0.5, z + 0.5))
                    zoneLine(g, view, b[0], z, b[0], z + 1, line);
                if (!PointMarkerRenderer.covers(boxes, b[2] + 1.5, z + 0.5))
                    zoneLine(g, view, b[2] + 1, z, b[2] + 1, z + 1, line);
            }
        }
    }

    /** World rectangle [x0, x1) x [z0, z1) on the map. */
    private static void zoneRect(
            GuiGraphics g, MapView view, double x0, double z0, double x1, double z1, int color) {
        float[] p = view.screen(x0, z0), q = view.screen(x1, z1);
        int left = Math.round(Math.min(p[0], q[0])), right = Math.round(Math.max(p[0], q[0]));
        int top = Math.round(Math.min(p[1], q[1])), bottom = Math.round(Math.max(p[1], q[1]));
        if (right > left && bottom > top) g.fill(left, top, right, bottom, color);
    }

    /** 1-pixel world edge (horizontal or vertical) on the map. */
    private static void zoneLine(
            GuiGraphics g, MapView view, double x0, double z0, double x1, double z1, int color) {
        float[] p = view.screen(x0, z0), q = view.screen(x1, z1);
        int left = Math.round(Math.min(p[0], q[0])), right = Math.round(Math.max(p[0], q[0]));
        int top = Math.round(Math.min(p[1], q[1])), bottom = Math.round(Math.max(p[1], q[1]));
        g.fill(left, top, Math.max(right, left + 1), Math.max(bottom, top + 1), color);
    }

    private static void drawPlayers(GuiGraphics g, Minecraft mc, MapView view, boolean names) {
        for (MapPlayerMotion.Position other : mapPlayers()) {
            if (!view.contains(other.x(), other.z())) continue;
            float[] at = view.screen(other.x(), other.z());
            int px = Math.round(at[0]), py = Math.round(at[1]);
            drawMapPlayer(
                    g,
                    px,
                    py,
                    other.enemy() ? BfTheme.DANGER : teamColor(HudRenderer.playerTeam()),
                    other.enemy());
            String name = names ? WorkshopMarks.playerName(other.id()) : null;
            if (name != null) {
                int width = mc.font.width(name);
                g.fill(px + 4, py - 5, px + 8 + width, py + 5, BfTheme.alpha(BfTheme.BG, 0xB0));
                g.drawString(mc.font, name, px + 6, py - 4, BfTheme.OK, false);
            }
        }
    }

    /**
     * Workshop: block grid (16/64/256 by zoom), point zones and every markup mark with its label.
     */
    private static void drawWorkshop(
            GuiGraphics g, MapView view, JsonObject map, int x, int y, int w, int h) {
        double perBlock = w / Math.max(1e-6, view.maxX() - view.minX());
        int step = WorkshopMarks.gridStep(perBlock, 10);
        if (step > 0) {
            int line = BfTheme.alpha(BfTheme.TEXT, 0x22);
            for (double wx = Math.ceil(view.minX() / step) * step; wx <= view.maxX(); wx += step) {
                int sx = Math.round(view.screen(wx, view.minZ())[0]);
                g.fill(sx, y, sx + 1, y + h, line);
            }
            for (double wz = Math.ceil(view.minZ() / step) * step; wz <= view.maxZ(); wz += step) {
                int sy = Math.round(view.screen(view.minX(), wz)[1]);
                g.fill(x, sy, x + w, sy + 1, line);
            }
        }
        List<WorkshopMarks.Mark> marks = WorkshopMarks.of(map);
        for (WorkshopMarks.Mark mark : marks) {
            if (mark.boxes() == null) continue;
            drawPointZone(
                    g,
                    view,
                    new PointMarkerRenderer.Marker(
                            mark.x(),
                            0,
                            mark.z(),
                            mark.name(),
                            "",
                            BfTheme.AMBER,
                            0,
                            BfTheme.AMBER,
                            mark.box(),
                            mark.boxes()));
        }
        for (WorkshopMarks.Mark mark : marks) {
            if (!view.contains(mark.x(), mark.z())) continue;
            float[] at = view.screen(mark.x() + 0.5, mark.z() + 0.5);
            WorkshopMarks.draw(g, mark, Math.round(at[0]), Math.round(at[1]), 0xF0, true);
        }
    }

    /**
     * Workshop: world coordinates under the cursor and the right-click hint, bottom right of the
     * map.
     */
    private static void drawCursor(
            GuiGraphics g, Minecraft mc, TabletFrame f, MapView view, int mouseX, int mouseY) {
        String text = "ПКМ — телепорт";
        if (f.inContent(mouseX, mouseY) && view.onMap(mouseX, mouseY)) {
            double[] world = view.world(mouseX, mouseY);
            text =
                    "X "
                            + (int) Math.floor(world[0])
                            + "  Z "
                            + (int) Math.floor(world[1])
                            + "  ·  "
                            + text;
        }
        int width = mc.font.width(text) + 6;
        int left = f.contentX() + f.contentW() - width, top = f.contentY() + f.contentH() - 11;
        g.fill(left, top, left + width, top + 11, BfTheme.alpha(BfTheme.BG, 0xC0));
        g.drawString(mc.font, text, left + 3, top + 2, BfTheme.TEXT_SECONDARY, false);
    }

    /**
     * Workshop: right click on the map asks the server to teleport there (it checks rights, bounds
     * and void).
     */
    public static boolean teleport(double screenX, double screenY, TabletFrame f) {
        JsonObject map = zone();
        MapView view = view(f);
        if (!WorkshopMarks.active(map)
                || view == null
                || !f.inContent(screenX, screenY)
                || !view.onMap(screenX, screenY)) return false;
        if (!ClientPlayNetworking.canSend(
                com.lunares.blockfield.network.packet.MenuActionC2SPacket.TYPE)) return false;
        double[] world = view.world(screenX, screenY);
        ClientPlayNetworking.send(
                new com.lunares.blockfield.network.packet.MenuActionC2SPacket(
                        "workshop:tpxz:"
                                + (int) Math.floor(world[0])
                                + ":"
                                + (int) Math.floor(world[1])));
        return true;
    }

    /** Миникарта: позиции имеют смысл, только пока сам игрок в измерении боя. */
    public static List<MapPlayerMotion.Position> players(Minecraft mc) {
        JsonObject map = zone();
        if (map == null || !map.has("dimension") || !inMapDimension(mc, map)) return List.of();
        return mapPlayers();
    }

    /** Планшет показывает союзников и убитому: сервер сам решает, кого ему присылать. */
    private static List<MapPlayerMotion.Position> mapPlayers() {
        JsonObject map = zone();
        if (map == null) return List.of();
        Minecraft mc = Minecraft.getInstance();
        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);
        List<MapPlayerMotion.Position> list = new ArrayList<>();
        for (var other : playerMotion.positions(map, System.nanoTime() / 1_000_000)) {
            // Only authorized allies use tracked entities; revealed enemies stay at server-provided
            // positions.
            var entity =
                    !other.enemy() && other.id() != null && inMapDimension(mc, map)
                            ? mc.level.getPlayerByUUID(other.id())
                            : null;
            if (entity != null && entity.isAlive() && !entity.isSpectator()) {
                var position = entity.getPosition(partialTick);
                list.add(new MapPlayerMotion.Position(other.id(), position.x, position.z, false));
            } else list.add(other);
        }
        return list;
    }

    public static void drawMapPlayer(GuiGraphics g, int x, int y, int color, boolean enemy) {
        g.fill(x - 3, y - 3, x + 3, y + 3, BfTheme.alpha(BfTheme.BG, 0xE0));
        if (enemy) {
            g.fill(x - 1, y - 3, x + 1, y + 3, color);
            g.fill(x - 3, y - 1, x + 3, y + 1, color);
        } else {
            g.fill(x - 2, y - 2, x + 2, y + 2, color);
        }
    }

    /** Свой маркер — стрелка по направлению взгляда (yaw MC: 0 = юг, ось Z карты вниз). */
    public static void drawSelf(GuiGraphics g, float cx, float cy, float yaw) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0);
        // yaw MC: 0 = юг (+Z), ось Y экрана вниз -> поворот на yaw+180 от стрелки, смотрящей вверх.
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(yaw + 180f));
        pose.scale(0.25f, 0.25f, 1f);
        g.blit(SELF_ARROW, -32, -32, 0, 0, 64, 64, 64, 64);
        pose.popPose();
    }

    static void drawBase(GuiGraphics g, float x, float y, int color) {
        int cx = Math.round(x);
        int cy = Math.round(y);
        g.fill(cx - 1, cy - 7, cx, cy + 5, BfTheme.TEXT);
        g.fill(cx, cy - 7, cx + 7, cy - 2, color);
    }

    public static int teamColor(String team) {
        if ("RedTeam".equals(team)) return BfTheme.RED_TEAM;
        if ("BlueTeam".equals(team)) return BfTheme.BLUE_TEAM;
        return BfTheme.OK;
    }

    /** Заголовок вкладки: имя карты и масштаб зоны в блоках. */
    public static String subtitle() {
        return subtitle(zone());
    }

    static String subtitle(JsonObject map) {
        if (map == null) return "";
        int width = Mth.ceil(map.get("maxX").getAsDouble() - map.get("minX").getAsDouble());
        int depth = Mth.ceil(map.get("maxZ").getAsDouble() - map.get("minZ").getAsDouble());
        return width + "x" + depth + " М";
    }
}
