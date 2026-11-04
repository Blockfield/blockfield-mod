package com.lunares.blockfield.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lunares.blockfield.client.gui.BfTheme;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Маркеры точек захвата в духе Battlefield: буква в кольце, кольцо заполняется прогрессом захвата,
 * спорная точка пульсирует, при ADS разметка гаснет. Сверху -- лента-компас с точками по азимуту.
 * Мировые маркеры используют проекцию кадра до покачивания камеры и вид из {@link
 * WorldRenderEvents#LAST}. Так сохраняется реальный FOV (включая ADS TaCZ и спринт), но шаги не
 * трясут HUD. Мировой конвейер не используем: Sodium искажает иммедиат-квады в мировых фазах.
 */
public final class PointMarkerRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlockField-PointMarker");

    /**
     * Радиус кольца маркера в GUI-пикселях (общий для HUD, миникарты и планшета): буква 5x7 плюс
     * поле ~2 px до кольца.
     */
    static final int RADIUS = 8;

    // Заглавная буква стандартного шрифта -- 7 строк; без тени, иначе буква кажется сдвинутой
    // вправо-вниз.
    private static final float LETTER_HEIGHT = 7f;
    // Default Minecraft glyphs occupy 7 pixels plus one shadow pixel; lineHeight includes leading.
    private static final int MARKER_TEXT_HEIGHT = 8;
    private static final int LABEL_PADDING = 2;

    /** Вблизи игрок и так внутри зоны, статус показывает баннер HUD. */
    private static final float HIDE_UNDER = 6f;

    // Высоко над точкой: у земли маркер перекрывал прицел и цели рядом с флагом.
    private static final double HEIGHT_OVER_BEACON = 50.0;

    /** В зоне прицела маркер только мешает целиться: гасим его до полупрозрачного. */
    private static final float CENTER_FADE_RADIUS = 48f;

    private static final int ALPHA = 0xD0;
    private static final int ALPHA_CENTER = 0x50;
    private static final int ALPHA_ADS = 0x40;

    /** Прицел ближе этого к маркеру -- показываем имя, дистанцию и процент (идея BetterPoints). */
    private static final float HOVER_RADIUS = 12f;

    private static final int COMPASS_Y = 30;
    private static final int COMPASS_RADIUS = 5;
    private static final int COMPASS_H = 2 * (COMPASS_RADIUS + 1) + 2;
    private static final float COMPASS_HALF_SPAN = 90f;

    private static volatile List<Marker> markers = List.of();
    private static volatile ResourceKey<Level> dimension;

    private static final Matrix4f projection = new Matrix4f();
    private static final Matrix4f view = new Matrix4f();
    private static Vec3 cameraPos = Vec3.ZERO;

    /**
     * Зона захвата в блоках: bounds -- охватывающий прямоугольник minX, minZ, maxX, maxZ; boxes --
     * её коробки того же вида (зона сложной формы, bs от сервера; иначе одна bounds). null, если
     * сервер границ не прислал.
     */
    public record Marker(
            double x,
            double y,
            double z,
            String name,
            String letter,
            int ownerColor,
            double progress,
            int barColor,
            int[] bounds,
            int[][] boxes) {
        public Marker(
                double x,
                double y,
                double z,
                String name,
                String letter,
                int ownerColor,
                double progress,
                int barColor,
                int[] bounds) {
            this(
                    x,
                    y,
                    z,
                    name,
                    letter,
                    ownerColor,
                    progress,
                    barColor,
                    bounds,
                    bounds == null ? null : new int[][] {bounds});
        }

        public boolean contested() {
            return progress > 0.0 && progress < 100.0;
        }
    }

    static {
        HudRenderCallback.EVENT.register(PointMarkerRenderer::renderHud);
        WorldRenderEvents.LAST.register(
                ctx -> {
                    view.set(ctx.positionMatrix());
                    cameraPos = ctx.camera().getPosition();
                });
    }

    /** Форсирует загрузку класса (и регистрацию событий статическим блоком) при старте клиента. */
    public static void init() {}

    public static void captureProjection(Matrix4f frameProjection) {
        projection.set(frameProjection);
    }

    public static void updatePoints(JsonArray pointsArray) {
        Minecraft mc = Minecraft.getInstance();
        dimension = mc.level == null ? null : mc.level.dimension();
        markers = parseSafe(pointsArray);
    }

    public static String displayName(String id) {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "alpha" -> "Альфа";
            case "beta" -> "Бета";
            case "gamma" -> "Гамма";
            case "delta" -> "Дельта";
            case "epsilon" -> "Эпсилон";
            case "iota" -> "Йота";
            default -> id;
        };
    }

    static boolean overlaps(float x, float y, int radius, int[] rect) {
        return rect != null
                && x + radius >= rect[0]
                && x - radius <= rect[0] + rect[2]
                && y + radius >= rect[1]
                && y - radius <= rect[1] + rect[3];
    }

    public static void clearAllPoints() {
        markers = List.of();
        dimension = null;
    }

    /** Снимок точек, актуальный только в измерении {@link #dimension()}. */
    public static List<Marker> markers() {
        return markers;
    }

    /** Точки захвата вместе с временными метками команды: их рисуют мир, компас и карты. */
    public static List<Marker> markersWithPings() {
        List<Marker> pings = com.lunares.blockfield.client.gui.tablet.MapPings.markers();
        if (pings.isEmpty()) return markers;
        List<Marker> all = new ArrayList<>(markers);
        all.addAll(pings);
        return all;
    }

    public static ResourceKey<Level> dimension() {
        return dimension;
    }

    /**
     * Одиночная кривая точка не должна ронять весь снимок, а вылет наружу глотается updateHUDData
     * вместе с 2D-HUD.
     */
    private static List<Marker> parseSafe(JsonArray pointsArray) {
        try {
            return parse(pointsArray);
        } catch (RuntimeException e) {
            LOGGER.error("points parse failed", e);
            return List.of();
        }
    }

    public static List<Marker> parse(JsonArray pointsArray) {
        if (pointsArray == null || pointsArray.isEmpty()) return List.of();
        List<Marker> list = new ArrayList<>(pointsArray.size());
        for (JsonElement el : pointsArray) {
            if (!el.isJsonObject()) continue;
            JsonObject pt = el.getAsJsonObject();
            if (!pt.has("x") || !pt.has("y") || !pt.has("z")) continue;
            try {
                double x = pt.get("x").getAsDouble();
                double y = pt.get("y").getAsDouble();
                double z = pt.get("z").getAsDouble();
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) continue;

                String name = stringValue(pt, "n", "").strip();
                String owner = stringValue(pt, "o", "NEUTRAL");
                String capturing = stringValue(pt, "claiming", stringValue(pt, "c", "NONE"));
                double progress =
                        pt.has("p") && !pt.get("p").isJsonNull() ? pt.get("p").getAsDouble() : 0.0;
                progress = Double.isFinite(progress) ? Mth.clamp(progress, 0.0, 100.0) : 0.0;

                int ownerColor = teamColor(owner, BfTheme.NEUTRAL);
                // Прогресс своей точки = остаток контроля, даже пока её нейтрализует противник.
                int barColor =
                        ownerColor != BfTheme.NEUTRAL
                                ? ownerColor
                                : teamColor(capturing, BfTheme.AMBER);
                String letter =
                        name.isEmpty()
                                ? "?"
                                : name.substring(0, name.offsetByCodePoints(0, 1))
                                        .toUpperCase(Locale.ROOT);
                int[] bounds = box4(pt.get("b"));
                int[][] boxes = boxes(pt.get("bs"));
                list.add(
                        new Marker(
                                x + 0.5,
                                y,
                                z + 0.5,
                                name.isEmpty() ? "?" : name,
                                letter,
                                ownerColor,
                                progress,
                                barColor,
                                bounds,
                                boxes != null
                                        ? boxes
                                        : bounds == null ? null : new int[][] {bounds}));
            } catch (IllegalArgumentException
                    | IllegalStateException
                    | UnsupportedOperationException ignored) {
                // Битая точка не должна оставлять на экране весь предыдущий снимок.
            }
        }
        return List.copyOf(list);
    }

    /** [minX, minZ, maxX, maxZ] или null. */
    public static int[] box4(JsonElement e) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 4) return null;
        JsonArray b = e.getAsJsonArray();
        return new int[] {
            b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(), b.get(3).getAsInt()
        };
    }

    /** Список коробок [[minX, minZ, maxX, maxZ], ...] или null, если его нет или он битый. */
    public static int[][] boxes(JsonElement e) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().isEmpty()) return null;
        JsonArray a = e.getAsJsonArray();
        int[][] out = new int[a.size()][];
        for (int i = 0; i < a.size(); i++) {
            out[i] = box4(a.get(i));
            if (out[i] == null) return null;
        }
        return out;
    }

    /** Точка (px, pz) в объединении коробок. */
    public static boolean covers(int[][] boxes, double px, double pz) {
        if (boxes == null) return false;
        for (int[] b : boxes) {
            if (px >= b[0] && px < b[2] + 1 && pz >= b[1] && pz < b[3] + 1) return true;
        }
        return false;
    }

    private static String stringValue(JsonObject point, String key, String fallback) {
        JsonElement value = point.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static int teamColor(String team, int fallback) {
        if ("RED".equals(team)) return BfTheme.RED_TEAM;
        if ("BLUE".equals(team)) return BfTheme.BLUE_TEAM;
        return fallback;
    }

    /** Пульс спорной точки: 0.65..1.0 с периодом ~1 с. */
    static float pulse(Marker m) {
        if (!m.contested()) return 1f;
        return 0.825f
                + 0.175f
                        * Mth.sin(
                                (float) (System.currentTimeMillis() % 1000L) / 1000f * Mth.TWO_PI);
    }

    /** Внутри своей зоны маркер в мире лишний: статус показывает баннер HUD. */
    static boolean inside(Marker m, double px, double pz) {
        return covers(m.boxes(), px, pz);
    }

    static boolean aiming(Minecraft mc) {
        return mc.player != null && IClientPlayerGunOperator.fromLocalPlayer(mc.player).isAim();
    }

    /**
     * Общая для HUD и карт отрисовка маркера с центром в (cx, cy): тёмный диск, кольцо-дорожка,
     * дуга прогресса цветом захватчика (или владельца при нейтрализации), буква цветом владельца.
     */
    public static void drawMarker(GuiGraphics g, Marker m, int cx, int cy, int alpha) {
        drawMarker(g, m, cx, cy, alpha, RADIUS);
    }

    public static void drawMarker(GuiGraphics g, Marker m, int cx, int cy, int alpha, int r) {
        alpha = Math.round(alpha * pulse(m));
        ring(g, cx, cy, r, 0, 0f, 1f, BfTheme.alpha(BfTheme.BG, Math.min(alpha, 0x90)));
        int track =
                m.contested()
                        ? BfTheme.alpha(BfTheme.BORDER_HOVER, alpha)
                        : BfTheme.alpha(m.ownerColor(), alpha);
        ring(g, cx, cy, r + 1, r - 1, 0f, 1f, track);
        if (m.contested()) {
            ring(
                    g,
                    cx,
                    cy,
                    r + 1,
                    r - 1,
                    0f,
                    (float) (m.progress() / 100.0),
                    BfTheme.alpha(m.barColor(), alpha));
        }
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        float textScale = (float) r / RADIUS;
        g.pose().scale(textScale, textScale, 1);
        drawCenteredText(
                g, m.letter(), 0, -LETTER_HEIGHT / 2f, BfTheme.alpha(m.ownerColor(), alpha), false);
        g.pose().popPose();
    }

    /**
     * Центрирует видимую часть текста: ширина шрифта включает 1 px межбуквенного зазора, тень
     * добавляет его обратно.
     */
    private static void drawCenteredText(
            GuiGraphics g, String text, float cx, float top, int color, boolean shadow) {
        Minecraft mc = Minecraft.getInstance();
        g.pose().pushPose();
        g.pose().translate(cx - (mc.font.width(text) - (shadow ? 0 : 1)) / 2f, top, 0);
        // Дробное смещение карты/зума кладёт границы текселей шрифта между экранными пикселями, и
        // штрихи
        // буквы выходят разной толщины. Прижимаем начало текста к целому экранному пикселю (без
        // поворота).
        Matrix4f m = g.pose().last().pose();
        if (m.m01() == 0 && m.m10() == 0 && m.m00() > 0 && m.m11() > 0) {
            float gs = (float) mc.getWindow().getGuiScale();
            g.pose()
                    .translate(
                            (Math.round(m.m30() * gs) / gs - m.m30()) / m.m00(),
                            (Math.round(m.m31() * gs) / gs - m.m31()) / m.m11(),
                            0);
        }
        g.drawString(mc.font, text, 0, 0, color, shadow);
        g.pose().popPose();
    }

    /** Кольцо/сектор из квадов: от доли from до to (0..1 по часовой от «12 часов»). */
    public static void ring(
            GuiGraphics g,
            float cx,
            float cy,
            float outer,
            float inner,
            float from,
            float to,
            int argb) {
        if (to <= from) return;
        Matrix4f pose = g.pose().last().pose();
        VertexConsumer buf = g.bufferSource().getBuffer(RenderType.gui());
        int segments = Math.max(1, Math.round(32 * (to - from)));
        float step = (to - from) / segments;
        for (int i = 0; i < segments; i++) {
            float a0 = (from + i * step) * Mth.TWO_PI - Mth.HALF_PI;
            float a1 = (from + (i + 1) * step) * Mth.TWO_PI - Mth.HALF_PI;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            // Порядок против часовой (как у GuiGraphics.fill): RenderType.gui() отсекает заднюю
            // грань.
            buf.addVertex(pose, cx + c0 * inner, cy + s0 * inner, 0).setColor(argb);
            buf.addVertex(pose, cx + c1 * inner, cy + s1 * inner, 0).setColor(argb);
            buf.addVertex(pose, cx + c1 * outer, cy + s1 * outer, 0).setColor(argb);
            buf.addVertex(pose, cx + c0 * outer, cy + s0 * outer, 0).setColor(argb);
        }
        g.flush();
    }

    private static void renderHud(GuiGraphics g, DeltaTracker delta) {
        List<Marker> list = markersWithPings();
        if (list.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui
                || mc.options.keyPlayerList.isDown()
                || mc.screen != null
                || mc.level == null
                || mc.player == null) return;
        if (!HudRenderer.isHudActive() || !HudRenderer.isDeployed()) return;
        if (!mc.level.dimension().equals(dimension)) return;

        boolean ads = aiming(mc);
        Vec3 cam = cameraPos;
        drawCompass(g, list, cam, mc.gameRenderer.getMainCamera().getYRot(), ads);

        Matrix4f viewProj = new Matrix4f(projection).mul(view);
        int guiW = g.guiWidth();
        int guiH = g.guiHeight();
        float centerX = guiW / 2f;
        float centerY = guiH / 2f;

        record Projected(Marker m, float x, float y, float dist) {}
        List<Projected> projected = new ArrayList<>(list.size());
        for (Marker m : list) {
            double dx = m.x() - cam.x;
            double dy = m.y() + HEIGHT_OVER_BEACON - cam.y;
            double dz = m.z() - cam.z;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < HIDE_UNDER || inside(m, mc.player.getX(), mc.player.getZ())) continue;

            Vector4f clip =
                    viewProj.transform(new Vector4f((float) dx, (float) dy, (float) dz, 1f));
            if (clip.w() <= 0.01f) continue; // позади камеры
            float x = (clip.x() / clip.w() * 0.5f + 0.5f) * guiW;
            float y = (-clip.y() / clip.w() * 0.5f + 0.5f) * guiH;
            if (x < -RADIUS || y < -RADIUS || x > guiW + RADIUS || y > guiH + RADIUS) continue;
            // Билеты, компас и баннер захвата важнее маркера в мире.
            if (y - RADIUS < HudRenderer.topStackBottom()
                    && Math.abs(x - centerX) < HudRenderer.barHalfWidth() + RADIUS) continue;
            if (overlaps(x, y, RADIUS, MinimapHud.rect())) continue;
            projected.add(new Projected(m, x, y, dist));
        }
        // Дальние первыми: ближний маркер ложится поверх. Наведение -- только на ближайший под
        // прицелом.
        projected.sort((a, b) -> Float.compare(b.dist(), a.dist()));
        Projected hovered = null;
        for (Projected p : projected) {
            if (Math.hypot(p.x() - centerX, p.y() - centerY) < HOVER_RADIUS) hovered = p;
        }
        for (Projected p : projected) {
            float fromCenter = (float) Math.hypot(p.x() - centerX, p.y() - centerY);
            int alpha =
                    ads
                            ? ALPHA_ADS
                            : p == hovered
                                    ? ALPHA
                                    : fromCenter < CENTER_FADE_RADIUS ? ALPHA_CENTER : ALPHA;
            g.pose().pushPose();
            g.pose().translate(p.x(), p.y(), 0);
            drawMarker(g, p.m(), 0, 0, alpha);
            if (p == hovered && !ads) drawHoverInfo(g, p.m(), 0, 0, p.dist());
            g.pose().popPose();
        }
    }

    /** Подпись под маркером, на который смотрит игрок: дистанция и процент, если точка спорная. */
    private static void drawHoverInfo(GuiGraphics g, Marker m, int cx, int cy, float dist) {
        Minecraft mc = Minecraft.getInstance();
        String line =
                Math.round(dist)
                        + " м"
                        + (m.contested() ? "  " + Math.round(m.progress()) + "%" : "");
        int w = mc.font.width(line);
        int top = cy + RADIUS + 3;
        int halfWidth = (w + 1) / 2 + 3;
        g.fill(
                cx - halfWidth,
                top,
                cx + halfWidth,
                top + MARKER_TEXT_HEIGHT + LABEL_PADDING * 2,
                BfTheme.alpha(BfTheme.BG, 0xA0));
        drawCenteredText(g, line, cx, top + LABEL_PADDING, m.ownerColor(), true);
    }

    /**
     * Лента-компас под панелью билетов: деления, стороны света и точки по азимуту в пределах ±90°.
     */
    private static void drawCompass(
            GuiGraphics g, List<Marker> list, Vec3 cam, float yaw, boolean ads) {
        Minecraft mc = Minecraft.getInstance();
        int alpha = ads ? ALPHA_ADS : ALPHA;
        int cx = g.guiWidth() / 2;
        int halfW = HudRenderer.barHalfWidth();
        // Кольцо точки у края ±90° не должно вылезать за ленту.
        int span = halfW - COMPASS_RADIUS - 1;
        int left = cx - halfW;
        int top = COMPASS_Y;
        g.fill(
                left,
                top,
                left + halfW * 2,
                top + COMPASS_H,
                BfTheme.alpha(BfTheme.BG, Math.min(alpha, 0x90)));
        g.fill(cx, top, cx + 1, top + 3, BfTheme.alpha(BfTheme.AMBER, alpha));

        // Сначала считаем позиции точек: буква стороны света уступает место маркеру, если они
        // совпали.
        List<int[]> pointXs = new ArrayList<>();
        List<Marker> shown = new ArrayList<>();
        for (Marker m : list) {
            double dx = m.x() - cam.x;
            double dz = m.z() - cam.z;
            // Стоя на самом маяке азимут вырожден: держим точку по центру ленты.
            float rel =
                    dx * dx + dz * dz < 1.0
                            ? 0f
                            : Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)) - yaw);
            if (Math.abs(rel) > COMPASS_HALF_SPAN) continue;
            pointXs.add(new int[] {cx + Math.round(rel / COMPASS_HALF_SPAN * span)});
            shown.add(m);
        }

        String[] cardinal = {"Ю", "З", "С", "В"}; // MC yaw 0 = юг, далее по часовой
        for (int deg = -180; deg < 180; deg += 15) {
            float rel = Mth.wrapDegrees(deg - yaw);
            if (Math.abs(rel) > COMPASS_HALF_SPAN) continue;
            int x = cx + Math.round(rel / COMPASS_HALF_SPAN * span);
            boolean major = deg % 90 == 0;
            boolean covered =
                    pointXs.stream().anyMatch(px -> Math.abs(px[0] - x) < COMPASS_RADIUS + 2);
            if (major && !covered) {
                String label = cardinal[Math.floorMod(deg / 90, 4)];
                g.drawCenteredString(
                        mc.font,
                        label,
                        x,
                        top + (COMPASS_H - MARKER_TEXT_HEIGHT) / 2,
                        BfTheme.alpha(BfTheme.TEXT_SECONDARY, alpha));
            } else if (!major) {
                g.fill(
                        x,
                        top + COMPASS_H - 3,
                        x + 1,
                        top + COMPASS_H - 1,
                        BfTheme.alpha(BfTheme.MUTED_DARK, alpha));
            }
        }

        for (int i = 0; i < shown.size(); i++) {
            g.pose().pushPose();
            g.pose().translate(pointXs.get(i)[0], top + COMPASS_H / 2f, 0);
            // Без масштаба: дробный масштаб пиксельного шрифта двоит букву.
            drawMarker(g, shown.get(i), 0, 0, alpha, COMPASS_RADIUS);
            g.pose().popPose();
        }
    }
}
