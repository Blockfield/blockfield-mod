package com.lunares.blockfield.client.render;

import com.google.gson.JsonObject;
import com.lunares.blockfield.client.ClientConfig;
import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.tablet.MapPings;
import com.lunares.blockfield.client.gui.tablet.MinimapView;
import com.lunares.blockfield.client.gui.tablet.TabletMapTab;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Миникарта в левом верхнем углу: вырезанный кусок готового снимка карты ({@code map_<id>.png})
 * вокруг игрока, стрелка направления, союзники, точки захвата и метки с планшета. Своих чанков не
 * сканирует: картинка и границы зоны приходят готовыми, поэтому пересозданное каждый раунд
 * измерение матча для неё ничего не значит. Проекция и поворот — {@link MinimapView}, рисунок
 * маркеров и стрелки общий с планшетом.
 */
public final class MinimapHud {

    private static final int MARGIN = 4;
    private static final int MARKER_RADIUS = 4;

    /** Маркер у рамки не должен вылезать половиной кольца наружу. */
    private static final int INSET = MARKER_RADIUS + 2;

    private static final int CREDITS_H = 16;
    private static final int FOG = 12;

    /** GUI-пиксели: при GUI scale 4 на 1080p это ~17% ширины экрана; 96 занимали пятую часть. */
    private static final int DEFAULT_SIZE = 80;

    private static int lastX, lastY, lastW, lastH;
    private static boolean lastVisible;

    private MinimapHud() {}

    public static void init() {
        HudRenderCallback.EVENT.register(MinimapHud::render);
    }

    private static final int GAIN_LINE_H = 10;

    /** Зазор под подчёркиванием счётчика: всплеск суммы первой строки растёт вверх. */
    private static final int GAIN_TOP = 4;

    private static int lastGainLines;

    /**
     * Прямоугольник виджета вместе с балансом БК и лентой начислений; null — миникарты сейчас нет.
     */
    public static int[] rect() {
        return lastVisible
                ? new int[] {
                    lastX,
                    lastY,
                    lastW,
                    lastH
                            + CREDITS_H
                            + (lastGainLines == 0 ? 0 : GAIN_TOP + lastGainLines * GAIN_LINE_H)
                }
                : null;
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        lastVisible = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null
                || mc.level == null
                || mc.options.hideGui
                || mc.options.keyPlayerList.isDown()
                || mc.screen != null) return;
        // F3 занимает тот же левый верхний угол: карта с балансом БК закрывала его строки.
        if (mc.getDebugOverlay().showDebugScreen()) return;

        JsonObject map = TabletMapTab.zone();
        String id = map != null && map.has("id") ? map.get("id").getAsString() : "";
        ResourceLocation texture = TabletMapTab.snapshot(mc, id, true);
        // isHudActive() не годится: Deathmatch не шлёт билеты, а карта там нужна.
        // The workshop has no battle to deploy into: its map and markup are shown whenever a member
        // is on a map.
        boolean workshop = com.lunares.blockfield.client.gui.tablet.WorkshopMarks.active(map);
        if (!MinimapView.shouldRender(
                ClientConfig.USER.getBoolean("minimap.enabled", true),
                HudRenderer.isDeployed() || workshop,
                !PointMarkerRenderer.aiming(mc),
                map != null,
                texture != null)) return;

        int size = ClientConfig.USER.getInt("minimap.size", DEFAULT_SIZE, 48, 220);
        int span = ClientConfig.USER.getInt("minimap.blocks", 96, 24, 512);
        boolean rotate = ClientConfig.USER.getBoolean("minimap.rotate", true);
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        // С пульта дрона карта идёт за дроном: SBW камеру не переставляет, см. DroneCamera.
        var position =
                com.lunares.blockfield.client.DroneCamera.pingSource(mc).getPosition(partialTick);
        float yaw = mc.gameRenderer.getMainCamera().getYRot();

        MinimapView view =
                MinimapView.of(
                        map.get("minX").getAsDouble(),
                        map.get("minZ").getAsDouble(),
                        map.get("maxX").getAsDouble(),
                        map.get("maxZ").getAsDouble(),
                        position.x,
                        position.z,
                        yaw,
                        rotate,
                        span,
                        MARGIN,
                        MARGIN,
                        size,
                        size);

        lastX = view.x();
        lastY = view.y();
        lastW = view.w();
        lastH = view.h();
        lastVisible = true;

        BfTheme.panel(g, view.x(), view.y(), view.w(), view.h(), false);
        g.enableScissor(view.x(), view.y(), view.x() + view.w(), view.y() + view.h());
        g.pose().pushPose();
        if (view.rotation() != 0f) {
            g.pose().translate(view.centerX(), view.centerY(), 0);
            g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(view.rotation()));
            g.pose().translate(-view.centerX(), -view.centerY(), 0);
        }
        g.pose().translate(view.offsetX(), view.offsetY(), 0);
        // За зоной снимка нет: сетка вместо глухой заливки показывает, что это край карты, а не
        // сбой.
        // Только вокруг окна и с привязкой к шагу: сетка едет вместе с миром, а не рисуется на всю
        // зону.
        BfTheme.grid(
                g,
                Mth.floor((view.centerX() - view.offsetX() - size) / 8f) * 8,
                Mth.floor((view.centerY() - view.offsetY() - size) / 8f) * 8,
                2 * size + 8,
                2 * size + 8,
                8);
        g.blit(
                texture,
                view.zone().x(),
                view.zone().y(),
                0,
                0,
                view.zone().w(),
                view.zone().h(),
                view.zone().w(),
                view.zone().h());
        // Снимок -- текстурный квад, он выливается позже цветных: без flush туман уходит под карту.
        g.flush();
        fog(g, view.zone().w(), view.zone().h());
        g.pose().popPose();
        // Снимок -- текстурный квад, он выливается позже цветных: без flush маркеры уходят под
        // карту.
        g.flush();

        drawPlayers(g, mc, view);
        List<PointMarkerRenderer.Marker> markers = new ArrayList<>(MapPings.markers());
        // Точки приходят с UI-пакетом и переживают смену измерения: чужие не рисуем.
        if (mc.level.dimension().equals(PointMarkerRenderer.dimension()))
            markers.addAll(PointMarkerRenderer.markers());
        // Ближние первыми: у кромки место достаётся им, дальние на том же направлении пропускаем.
        markers.sort(
                Comparator.comparingDouble(
                        m -> Mth.square(m.x() - position.x) + Mth.square(m.z() - position.z)));
        List<float[]> edge = new ArrayList<>();
        for (PointMarkerRenderer.Marker marker : markers) {
            drawMarker(g, view, marker, edge);
        }
        if (workshop) {
            for (var mark : com.lunares.blockfield.client.gui.tablet.WorkshopMarks.of(map)) {
                float[] p = view.project(mark.x() + 0.5, mark.z() + 0.5);
                boolean inside = view.inFrame(p[0], p[1], INSET);
                // Off-window only the capture points point the way; pads and spawns would crowd the
                // rim.
                if (!inside && !mark.kind().equals("point")) continue;
                float[] at = inside ? p : view.clamp(p[0], p[1], INSET);
                if (!inside) {
                    if (MinimapView.crowded(at, edge, 2 * INSET)) continue;
                    edge.add(at);
                }
                com.lunares.blockfield.client.gui.tablet.WorkshopMarks.draw(
                        g, mark, Math.round(at[0]), Math.round(at[1]), inside ? 0xF0 : 0x90, false);
            }
        }
        if (rotate) {
            // Без метки севера повёрнутая карта не даёт сориентироваться по сторонам света.
            float[] projected = view.project(position.x, position.z - 100000);
            float[] north = view.clamp(projected[0], projected[1], 5);
            g.drawCenteredString(
                    mc.font, "С", Math.round(north[0]), Math.round(north[1]) - 4, BfTheme.AMBER);
        }
        // Стрелка при повороте по взгляду всегда вверх: yaw уже учтён поворотом окна.
        TabletMapTab.drawSelf(g, view.centerX(), view.centerY(), rotate ? 180f : yaw);
        g.disableScissor();

        BfTheme.outline(g, view.x(), view.y(), view.w(), view.h(), BfTheme.BORDER);
        lastGainLines = 0;
        double credits = HudRenderer.credits();
        if (credits >= 0) drawCredits(g, view, credits, net.minecraft.Util.getMillis());
        else if (workshop) {
            // Builders place things by coordinates; the minimap is the one always-on place to read
            // them.
            String coords =
                    "X "
                            + Mth.floor(position.x)
                            + "  Y "
                            + Mth.floor(position.y)
                            + "  Z "
                            + Mth.floor(position.z);
            var font = BfTheme.font();
            g.fill(
                    view.x(),
                    view.y() + view.h() + 2,
                    view.x() + font.width(coords) + 6,
                    view.y() + view.h() + 13,
                    BfTheme.alpha(BfTheme.BG, 0xB0));
            g.drawString(
                    font,
                    coords,
                    view.x() + 3,
                    view.y() + view.h() + 4,
                    BfTheme.TEXT_SECONDARY,
                    false);
        }
    }

    private static void drawCredits(GuiGraphics g, MinimapView view, double credits, long now) {
        var font = BfTheme.font();
        CreditFeed feed = HudRenderer.CREDIT_FEED;
        String balance = HudRenderer.formatCredits(feed.rollBalance(credits, now));
        float pop = feed.balancePop(now);
        float width = font.width(balance) + 4 + font.width("БК") * 0.8f;
        float scale = Math.min(1f, (view.w() - 4) / width) * (1f + 0.15f * pop);
        g.pose().pushPose();
        g.pose()
                .translate(
                        view.x() + (view.w() - width * scale) / 2f,
                        view.y() + view.h() + 3 - 1.5f * pop,
                        0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, balance, 0, 0, BfTheme.mix(BfTheme.TEXT, BfTheme.AMBER, pop), true);
        g.fill(0, 11, 12 + Math.round((width - 12) * pop), 12, BfTheme.AMBER);
        g.pose().translate(font.width(balance) + 4, 1, 0);
        g.pose().scale(0.8f, 0.8f, 1);
        g.drawString(font, "БК", 0, 0, BfTheme.MUTED, true);
        g.pose().popPose();

        float y = view.y() + view.h() + CREDITS_H + GAIN_TOP;
        for (CreditFeed.Line line : feed.lines(now)) {
            float enter = line.enter(now);
            float linePop = line.pop(now);
            // Ниже ~4 альфа шрифт Minecraft рисует строку непрозрачной.
            int alpha = Math.max(5, Math.round(255 * enter * line.alpha(now)));
            boolean fine = line.amount < 0;
            String amount = (fine ? "−" : "+") + HudRenderer.formatCredits(Math.abs(line.amount));
            g.pose().pushPose();
            g.pose().translate(view.x() - 10 * (1f - enter), y, 0);
            g.pose().scale(0.8f, 0.8f, 1);
            // Подложка: строка лежит поверх мира, без неё светлый фон съедает текст.
            g.fill(
                    -3,
                    -2,
                    font.width(amount) + 4 + font.width(line.label) + 3,
                    10,
                    BfTheme.alpha(BfTheme.BG, alpha * 0x99 / 255));
            g.pose().pushPose();
            // Всплеск суммы растёт от базовой линии, чтобы строка не прыгала по вертикали.
            g.pose().translate(0, 8, 0);
            g.pose().scale(1f + 0.35f * linePop, 1f + 0.35f * linePop, 1);
            g.drawString(
                    font,
                    amount,
                    0,
                    -8,
                    BfTheme.alpha(
                            BfTheme.mix(
                                    fine ? BfTheme.DANGER : BfTheme.AMBER, BfTheme.TEXT, linePop),
                            alpha),
                    true);
            g.pose().popPose();
            g.drawString(
                    font,
                    line.label,
                    Math.round(font.width(amount) * (1f + 0.35f * linePop)) + 4,
                    0,
                    BfTheme.alpha(BfTheme.TEXT_SECONDARY, alpha),
                    true);
            g.pose().popPose();
            // Гаснущая строка освобождает место плавно: нижние подтягиваются, а не прыгают.
            y += GAIN_LINE_H * Math.min(1f, line.alpha(now) * 2f);
            lastGainLines++;
        }
    }

    /** Туман войны: снимок у границы зоны растворяется в фоне, а не обрывается ровной линией. */
    private static void fog(GuiGraphics g, int w, int h) {
        int clear = BfTheme.alpha(BfTheme.CARD, 0);
        g.fillGradient(0, 0, w, FOG, BfTheme.CARD, clear);
        g.fillGradient(0, h - FOG, w, h, clear, BfTheme.CARD);
        // fillGradient умеет только сверху вниз: боковые полосы кладём тем же градиентом, повернув
        // его.
        g.pose().pushPose();
        g.pose().translate(0, h, 0);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-90f));
        g.fillGradient(0, 0, h, FOG, BfTheme.CARD, clear);
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(w, 0, 0);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90f));
        g.fillGradient(0, 0, h, FOG, BfTheme.CARD, clear);
        g.pose().popPose();
    }

    /**
     * За окном маркер прижимается к рамке и бледнеет: он там только указатель направления. Точки на
     * одной линии прижимаются в одно место и сливались в кашу -- занятое место пропускаем.
     */
    private static void drawMarker(
            GuiGraphics g, MinimapView view, PointMarkerRenderer.Marker m, List<float[]> edge) {
        float[] p = view.project(m.x(), m.z());
        boolean inside = view.inFrame(p[0], p[1], INSET);
        float[] at = inside ? p : view.clamp(p[0], p[1], INSET);
        if (!inside) {
            if (MinimapView.crowded(at, edge, 2 * INSET)) return;
            edge.add(at);
        }
        g.pose().pushPose();
        g.pose().translate(at[0], at[1], 0);
        PointMarkerRenderer.drawMarker(g, m, 0, 0, inside ? 0xF0 : 0x90, MARKER_RADIUS);
        g.pose().popPose();
    }

    private static void drawPlayers(GuiGraphics g, Minecraft mc, MinimapView view) {
        for (var other : TabletMapTab.players(mc)) {
            float[] p = view.project(other.x(), other.z());
            boolean inside = view.inFrame(p[0], p[1], 3);
            float[] at = inside ? p : view.clamp(p[0], p[1], 3);
            g.pose().pushPose();
            g.pose().translate(at[0], at[1], 0);
            TabletMapTab.drawMapPlayer(
                    g,
                    0,
                    0,
                    BfTheme.alpha(
                            other.enemy()
                                    ? BfTheme.DANGER
                                    : TabletMapTab.teamColor(HudRenderer.playerTeam()),
                            inside ? 0xFF : 0x90),
                    other.enemy());
            g.pose().popPose();
        }
    }
}
