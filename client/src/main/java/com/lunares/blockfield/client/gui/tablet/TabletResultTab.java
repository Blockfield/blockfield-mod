package com.lunares.blockfield.client.gui.tablet;

import com.google.gson.JsonObject;
import com.lunares.blockfield.client.gui.BfIcon;
import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.screen.MenuLayout;
import com.lunares.blockfield.client.render.HudRenderer;
import com.lunares.blockfield.client.render.PointMarkerRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Вкладка «Итог боя»: донесение штаба после матча (владелец выбрал вариант «Планшет» из пяти
 * прототипов). Планшет сам поднимается снизу ({@link Tablet#showResult}), экран «включается» с
 * мерцанием, по нему проходит скан, и итог собирается по частям: печатается шапка, штампуется
 * ПОБЕДА/ПОРАЖЕНИЕ, отсчитываются счёт и личные цифры, въезжает состав команды. Всё по реальному
 * времени кадра от первого показа; повторное открытие вкладки показывает итог сразу целиком.
 *
 * <p>Рисуется на холсте {@link TabletCanvas}, как и меню: вёрстка одна на любой GUI-масштаб. Карту
 * и точки берём снимком из последнего UI-пакета боя: после конца матча сервер их гасит.
 */
public final class TabletResultTab {
    private TabletResultTab() {}

    /** Сколько после боя M открывает планшет сразу на итоге, а не на карте. */
    private static final long FRESH_NS = 90_000_000_000L;

    /** Пакет итога может обогнать последний UI-пакет боя: раньше этого срока бой итог не гасит. */
    private static final long BATTLE_CLEAR_NS = 5_000_000_000L;

    private static final int PAD = 14;

    /** Roster table geometry (shared paging block in {@link MenuLayout}, same as TAB). */
    static final int ROSTER_ROW_H = 14;

    static final int ROSTER_HEAD_H = 12;
    static final int ROSTER_PAGER_H = 14;

    private static MatchResult result;
    private static JsonObject zone;
    private static List<PointMarkerRenderer.Marker> points = List.of();
    private static long receivedAt;
    private static long startedAt = -1;
    private static boolean autoOpened;

    /** Current roster page; the render clamps it, the tablet owns the wheel/keys. */
    private static int resultPage;

    private static int lastPages = 1;

    /**
     * Rows per roster page; the pager line reserves height only once it has to be drawn (same
     * pattern as class/spawn page buttons).
     */
    static int perPage(int availH, boolean paged) {
        return Math.max(1, (availH - ROSTER_HEAD_H - (paged ? ROSTER_PAGER_H : 0)) / ROSTER_ROW_H);
    }

    /**
     * Data rows per roster page; with the own row pinned every paged page keeps one line free for
     * it, so no participant is ever displaced (a shared-block slice alone would cut one row per
     * page). Single-row fits keep the old priority: the own row wins.
     */
    static int dataPerPage(int availH, boolean paged, boolean pin) {
        int per = perPage(availH, paged);
        return pin ? Math.max(1, per - 1) : per;
    }

    /** Page count for a roster of {@code total} rows in {@code availH} px. */
    static int pageCount(int total, int availH, MatchResult.Player me) {
        int pages = MenuLayout.pagesFor(total, dataPerPage(availH, false, me != null));
        if (pages > 1) pages = MenuLayout.pagesFor(total, dataPerPage(availH, true, me != null));
        return pages;
    }

    /** Page count for a roster of {@code total} rows in {@code availH} px. */
    static int pageCount(int total, int availH) {
        return pageCount(total, availH, null);
    }

    /**
     * Page slice of the roster with the own row pinned to the last line on every page (same
     * guarantee as the old truncation, but on a reserved line: every participant stays on exactly
     * one page). Pure helper for tests and render.
     */
    static List<MatchResult.Player> visiblePage(
            List<MatchResult.Player> roster, MatchResult.Player me, int availH, int page) {
        int pages = pageCount(roster.size(), availH, me);
        int at = MenuLayout.clampPage(page, pages);
        boolean pin = me != null && pages > 1;
        int per = dataPerPage(availH, pages > 1, pin);
        int from = at * per;
        int to = Math.min(roster.size(), from + per);
        if (from >= to) return List.of();
        List<MatchResult.Player> shown = new ArrayList<>(roster.subList(from, to));
        if (pin && (roster.indexOf(me) < from || roster.indexOf(me) >= to)) shown.add(me);
        return shown;
    }

    /** Wheel paging on the results tab (tablet-owned input); false with a single page. */
    static boolean scroll(double y) {
        if (result == null || y == 0 || lastPages <= 1) return false;
        int next = MenuLayout.scrollPage(resultPage, y, lastPages);
        if (next == resultPage) return true;
        resultPage = next;
        return true;
    }

    /** PageUp/PageDown stepping on the results tab (tablet-owned input). */
    static boolean pageBy(int delta) {
        if (result == null || lastPages <= 1) return false;
        resultPage = MenuLayout.clampPage(resultPage + delta, lastPages);
        return true;
    }

    public static void accept(JsonObject json) {
        Minecraft mc = Minecraft.getInstance();
        result = MatchResult.parse(json, mc.player == null ? "" : mc.player.getUUID().toString());
        JsonObject map = TabletMapTab.zone();
        zone = map == null ? null : map.deepCopy();
        points = PointMarkerRenderer.parse(HudRenderer.matchPoints());
        receivedAt = System.nanoTime();
        startedAt = -1;
        autoOpened = false;
        resultPage = 0;
        lastPages = 1;
    }

    public static boolean available() {
        return result != null;
    }

    static boolean fresh() {
        return result != null && System.nanoTime() - receivedAt < FRESH_NS;
    }

    /**
     * Итог ещё не показывали сами: планшет поднимется, как только у игрока не будет другого экрана.
     */
    static boolean takeAutoOpen() {
        if (autoOpened || !fresh()) return false;
        autoOpened = true;
        return true;
    }

    /** Начался новый бой: прошлый итог больше не нужен. */
    public static void onBattle() {
        if (result != null && System.nanoTime() - receivedAt > BATTLE_CLEAR_NS) clear();
    }

    public static void clear() {
        result = null;
        zone = null;
        points = List.of();
        startedAt = -1;
        resultPage = 0;
        lastPages = 1;
    }

    static String subtitle() {
        return result == null ? "" : result.mode().toUpperCase(Locale.ROOT);
    }

    // ---- отрисовка ----

    static void render(GuiGraphics g, TabletFrame f) {
        if (result == null) return;
        long now = System.nanoTime();
        if (startedAt < 0) startedAt = now;
        float t = (now - startedAt) / 1e9f;
        TabletCanvas canvas = TabletCanvas.forInner(f.contentW(), f.contentH());
        g.pose().pushPose();
        g.pose().translate(f.contentX(), f.contentY(), 0);
        g.pose().scale(canvas.scale(), canvas.scale(), 1f);
        Tablet.withScissorShift(() -> draw(g, canvas.width(), canvas.height(), t));
        g.pose().popPose();
        g.flush();
        screenEffects(g, f, t);
    }

    private static void draw(GuiGraphics g, int w, int h, float t) {
        Font font = BfTheme.font();
        int mapW = Math.min(h - 2 * PAD, Math.round(w * 0.34f));
        mapPanel(g, font, PAD, PAD, mapW, h - 2 * PAD, t);

        int x = PAD + mapW + 18;
        int width = w - x - PAD;
        MatchResult.Player best = result.best();
        int medalW = best == null ? 0 : 150;
        header(g, font, x, PAD + 2, width - medalW - 8, t);
        stamp(g, font, x, PAD + 16, width - medalW - 10, t);
        text(
                g,
                font,
                BfTheme.ellipsize(font, result.reason(), width - medalW - 10),
                x,
                PAD + 48,
                BfTheme.TEXT_SECONDARY,
                fade(t, 1.6f, 0.3f));
        // The medal (MEDAL_H tall) hangs in the top right: the stats row starts below it, so it
        // can use the whole column instead of squeezing under the card.
        score(g, font, x, PAD + (best == null ? 64 : 70), width, t);
        if (best != null) medal(g, font, best, x + width - medalW, PAD, medalW, t);

        int y = PAD + 104;
        if (result.me() != null) y = grid(g, font, result.me(), x, y, width, t) + 14;
        roster(g, font, x, y, width, h - PAD - y, t);
    }

    // ---- карта ----

    private static void mapPanel(GuiGraphics g, Font font, int x, int y, int w, int h, float t) {
        g.fill(x, y, x + w, y + h, BfTheme.BG_ELEVATED);
        BfTheme.outline(g, x, y, w, h, BfTheme.BORDER);
        float in = fade(t, 0.9f, 0.3f);
        if (in <= 0) return;
        int captionH = 16;
        int fx = x + 5, fy = y + 5, fw = w - 10, fh = h - 10 - captionH;
        MapView view =
                zone == null
                        ? null
                        : MapView.of(
                                zone.get("minX").getAsDouble(),
                                zone.get("minZ").getAsDouble(),
                                zone.get("maxX").getAsDouble(),
                                zone.get("maxZ").getAsDouble(),
                                baseZ(),
                                fx,
                                fy,
                                fw,
                                fh,
                                1f,
                                0,
                                0,
                                0.5f,
                                0.5f);

        // Карта «проявляется» сверху вниз под линией скана.
        float reveal = ease(clamp((t - 1.0f) / 0.9f));
        int scanY = fy + Math.round(fh * reveal);
        if (view != null && reveal > 0) {
            g.enableScissor(fx, fy, fx + fw, scanY);
            ResourceLocation texture = MapSnapshots.texture(Minecraft.getInstance(), zone);
            if (texture != null) {
                PoseStack pose = g.pose();
                pose.pushPose();
                pose.translate(view.x() + view.w() / 2f, view.y() + view.h() / 2f, 0);
                if (view.flipped()) pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180f));
                pose.scale(view.w() / 256f, view.h() / 256f, 1f);
                g.blit(texture, -128, -128, 0, 0, 256, 256, 256, 256);
                pose.popPose();
                g.flush();
                g.fill(
                        Math.round(view.x()),
                        Math.round(view.y()),
                        Math.round(view.x() + view.w()),
                        Math.round(view.y() + view.h()),
                        BfTheme.alpha(BfTheme.BG, 0x50));
            } else {
                // Same rectangle as the map and its frame drawn below, not the whole card area.
                MapLoading.render(
                        g,
                        zone,
                        Math.round(view.x()),
                        Math.round(view.y()),
                        Math.round(view.w()),
                        Math.round(view.h()));
            }
            g.disableScissor();
            BfTheme.outline(
                    g,
                    Math.round(view.x()),
                    Math.round(view.y()),
                    Math.round(view.w()),
                    Math.round(view.h()),
                    BfTheme.BORDER);
            BfTheme.ticks(
                    g,
                    Math.round(view.x()),
                    Math.round(view.y()),
                    Math.round(view.w()),
                    Math.round(view.h()),
                    4,
                    BfTheme.AMBER);
        } else if (view == null) {
            BfTheme.grid(g, fx, fy, fw, fh, 16);
        }
        if (reveal > 0 && reveal < 1) {
            g.fillGradient(fx, scanY - 10, fx + fw, scanY, 0, BfTheme.alpha(BfTheme.AMBER, 0x50));
            g.fill(fx, scanY, fx + fw, scanY + 1, BfTheme.AMBER_BRIGHT);
        }

        if (view != null) {
            if (zone.has("base") && zone.getAsJsonObject("base").has("x") && reveal >= 1) {
                JsonObject base = zone.getAsJsonObject("base");
                float[] at =
                        view.clampedScreen(
                                base.get("x").getAsDouble(), base.get("z").getAsDouble());
                TabletMapTab.drawBase(
                        g,
                        at[0],
                        at[1],
                        TabletMapTab.teamColor(result.me() == null ? "" : result.me().team()));
            }
            for (int i = 0; i < points.size(); i++) {
                PointMarkerRenderer.Marker marker = points.get(i);
                if (!view.contains(marker.x(), marker.z())) continue;
                float[] at = view.screen(marker.x(), marker.z());
                float start = 1.9f + i * 0.15f;
                float pop = back(clamp((t - start) / 0.4f));
                if (pop <= 0) continue;
                // Кольцо «пинга» расходится от каждой точки, когда карта уже собрана.
                float ping = t - 3.2f - i * 0.35f;
                if (ping > 0) {
                    float phase = (ping % 1.8f) / 1.8f;
                    float radius = 9 + phase * 9;
                    PointMarkerRenderer.ring(
                            g,
                            at[0],
                            at[1],
                            radius,
                            radius - 1.5f,
                            0f,
                            1f,
                            BfTheme.alpha(marker.ownerColor(), Math.round(0xB0 * (1 - phase))));
                }
                PointMarkerRenderer.drawMarker(
                        g,
                        marker,
                        Math.round(at[0]),
                        Math.round(at[1]),
                        0xF0,
                        Math.max(1, Math.round(8 * pop)));
            }
        }
        caption(g, font, x + 6, y + h - captionH + 4, w - 12, fade(t, 2.4f, 0.4f));
    }

    private static double baseZ() {
        if (zone == null || !zone.has("base")) return Double.NaN;
        JsonObject base = zone.getAsJsonObject("base");
        return base.has("z") ? base.get("z").getAsDouble() : Double.NaN;
    }

    private static void caption(GuiGraphics g, Font font, int x, int y, int w, float alpha) {
        if (points.isEmpty()) {
            text(g, font, "ЗОНА БОЯ " + TabletMapTab.subtitle(zone), x, y, BfTheme.MUTED, alpha);
            return;
        }
        int blue = 0, red = 0;
        for (PointMarkerRenderer.Marker m : points) {
            if (m.ownerColor() == BfTheme.BLUE_TEAM) blue++;
            else if (m.ownerColor() == BfTheme.RED_TEAM) red++;
        }
        String label = "ТОЧКИ К КОНЦУ БОЯ";
        text(g, font, label, x, y, BfTheme.MUTED, alpha);
        String counts = blue + " : " + red;
        int cx = x + w - font.width(counts);
        text(g, font, String.valueOf(blue), cx, y, BfTheme.BLUE_TEAM, alpha);
        text(g, font, " : ", cx + font.width(String.valueOf(blue)), y, BfTheme.MUTED, alpha);
        text(
                g,
                font,
                String.valueOf(red),
                cx + font.width(blue + " : "),
                y,
                BfTheme.RED_TEAM,
                alpha);
    }

    // ---- шапка, штамп, счёт ----

    private static void header(GuiGraphics g, Font font, int x, int y, int w, float t) {
        String line =
                "> ДОНЕСЕНИЕ ШТАБА · "
                        + result.map().toUpperCase(Locale.ROOT)
                        + (result.duration() > 0
                                ? " · " + MatchResult.clock(result.duration())
                                : "");
        line = BfTheme.ellipsize(font, line, w);
        float typed = clamp((t - 0.9f) / 0.5f);
        if (typed <= 0) return;
        String shown = line.substring(0, Math.round(line.length() * typed));
        boolean caret = typed < 1 || (t % 1f) < 0.5f && t < 5f;
        text(g, font, shown + (caret ? "_" : ""), x, y, BfTheme.MUTED, 1f);
    }

    private static void stamp(GuiGraphics g, Font font, int x, int y, int w, float t) {
        if (t < 1.2f) return;
        String word =
                switch (result.outcome()) {
                    case VICTORY -> "ПОБЕДА";
                    case DEFEAT -> "ПОРАЖЕНИЕ";
                    case DRAW -> "НИЧЬЯ";
                    case OVER ->
                            result.teams()
                                    ? ("RedTeam".equals(result.winner())
                                            ? "ПОБЕДА КРАСНЫХ"
                                            : "ПОБЕДА СИНИХ")
                                    : "БОЙ ОКОНЧЕН";
                };
        int color =
                switch (result.outcome()) {
                    case VICTORY -> BfTheme.AMBER_BRIGHT;
                    case DEFEAT -> BfTheme.DANGER;
                    default -> BfTheme.TEXT;
                };
        float scale = Math.min(3f, w / (float) Math.max(1, font.width(word)));
        // Глитч-штамп: полсекунды буква прыгает и мигает цветом «чужого» канала, потом встаёт.
        float glitch = (t - 1.2f) / 0.6f;
        if (glitch < 1f) {
            int step = (int) (glitch * 10);
            int[] shift = {-6, 3, -2, 5, 0, -3, 2, 0, 1, 0};
            float[] alpha = {1f, .3f, 1f, .6f, 1f, .4f, 1f, .8f, 1f, 1f};
            int ink = step % 2 == 0 ? BfTheme.BLUE_TEAM : color;
            big(g, font, word, x + shift[step], y, scale, ink, alpha[step]);
            return;
        }
        big(g, font, word, x + scale * 0.6f, y + scale * 0.6f, scale, 0xFF3A2410, 1f);
        big(g, font, word, x, y, scale, color, 1f);
    }

    private record Block(String label, String value, int color) {}

    private static List<Block> scoreBlocks(float k) {
        List<Block> blocks = new ArrayList<>();
        if (result.teams()) {
            boolean redFirst = result.me() != null && "RedTeam".equals(result.me().team());
            String unit = result.frags() ? " · ФРАГИ" : " · БИЛЕТЫ";
            Block blue =
                    new Block(
                            "СИНИЕ" + unit,
                            String.valueOf(Math.round(result.blue() * k)),
                            BfTheme.BLUE_TEAM);
            Block red =
                    new Block(
                            "КРАСНЫЕ" + unit,
                            String.valueOf(Math.round(result.red() * k)),
                            BfTheme.RED_TEAM);
            blocks.add(redFirst ? red : blue);
            blocks.add(redFirst ? blue : red);
        } else if (result.me() != null) {
            blocks.add(
                    new Block(
                            "ФРАГИ",
                            String.valueOf(Math.round(result.me().kills() * k)),
                            BfTheme.TEXT));
            blocks.add(
                    new Block(
                            "МЕСТО",
                            result.rank() + " / " + result.players().size(),
                            BfTheme.TEXT));
        }
        if (result.duration() > 0) {
            blocks.add(
                    new Block(
                            "ВРЕМЯ БОЯ",
                            MatchResult.clock(Math.round(result.duration() * k)),
                            BfTheme.TEXT));
        }
        return blocks;
    }

    /**
     * Крупность цифр, при которой ряд блоков (подпись или цифры, что шире, и зазор 20) влезает в
     * {@code width}; на самом тесном ряду цифры остаются мелкими, но не вылезают за колонку.
     */
    static float scoreScale(int[] labelW, int[] valueW, int width) {
        for (float scale : new float[] {2f, 1.5f}) {
            int total = 20 * Math.max(0, labelW.length - 1);
            for (int i = 0; i < labelW.length; i++)
                total += Math.max(labelW[i], Math.round(scale * valueW[i]));
            if (total <= width) return scale;
        }
        return 1f;
    }

    private static void score(GuiGraphics g, Font font, int x, int y, int width, float t) {
        float in = fade(t, 1.8f, 0.3f);
        if (in <= 0) return;
        float k = ease(clamp((t - 1.8f) / 0.9f));
        // Positions come from the final numbers, so the row does not shift while they count up.
        List<Block> end = scoreBlocks(1f);
        List<Block> now = scoreBlocks(k);
        int[] labelW = new int[end.size()], valueW = new int[end.size()];
        for (int i = 0; i < end.size(); i++) {
            labelW[i] = font.width(end.get(i).label());
            valueW[i] = font.width(end.get(i).value());
        }
        float scale = scoreScale(labelW, valueW, width);
        int bx = x;
        for (int i = 0; i < now.size(); i++) {
            Block block = now.get(i);
            text(g, font, block.label(), bx, y, BfTheme.MUTED, in);
            big(g, font, block.value(), bx, y + 11, scale, block.color(), in);
            bx += Math.max(labelW[i], Math.round(scale * valueW[i])) + 20;
        }
    }

    private static void medal(
            GuiGraphics g, Font font, MatchResult.Player best, int x, int y, int w, float t) {
        float pop = back(clamp((t - 2.9f) / 0.4f));
        if (pop <= 0) return;
        int h = 66;
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x + w / 2f, y + h / 2f, 0);
        float s = 0.8f + 0.2f * pop;
        pose.scale(s, s, 1f);
        pose.translate(-w / 2f, -h / 2f, 0);
        float a = clamp(pop);
        g.fill(0, 0, w, h, BfTheme.alpha(BfTheme.AMBER, Math.round(0x14 * a)));
        BfTheme.outline(g, 0, 0, w, h, BfTheme.alpha(BfTheme.AMBER_DIM, Math.round(0xFF * a)));
        BfIcon.RANK.draw(g, w / 2f - 7, 6, 14, BfTheme.alpha(BfTheme.AMBER, Math.round(0xFF * a)));
        String title = result.teams() && result.me() != null ? "ЛУЧШИЙ В КОМАНДЕ" : "ЛУЧШИЙ ИГРОК";
        centered(g, font, title, w / 2, 24, BfTheme.MUTED, a);
        centered(
                g,
                font,
                BfTheme.ellipsize(font, best.name(), w - 10),
                w / 2,
                36,
                best == result.me() ? BfTheme.AMBER_BRIGHT : BfTheme.TEXT,
                a);
        centered(
                g,
                font,
                best.score() + " очков · " + best.kills() + " / " + best.deaths(),
                w / 2,
                50,
                BfTheme.MUTED_DARK,
                a);
        pose.popPose();
    }

    // ---- личные цифры ----

    private record Cell(String label, Function<Float, String> value, boolean highlight) {}

    static int gridColumns(int width, int labelWidth, int count) {
        return Math.min(count > 6 ? 4 : 3, Math.max(1, (width + 3) / (labelWidth + 17)));
    }

    private static int grid(
            GuiGraphics g, Font font, MatchResult.Player me, int x, int y, int w, float t) {
        List<Cell> cells = new ArrayList<>();
        int rank = result.rank();
        cells.add(new Cell("ОЧКИ", k -> String.valueOf(Math.round(me.score() * k)), true));
        cells.add(
                new Cell(
                        "У / С",
                        k -> Math.round(me.kills() * k) + " / " + Math.round(me.deaths() * k),
                        false));
        if (!points.isEmpty()) {
            cells.add(
                    new Cell("ЗАХВАТЫ", k -> String.valueOf(Math.round(me.captures() * k)), false));
        } else {
            cells.add(
                    new Cell(
                            "У / С ОТНОШ.",
                            k ->
                                    String.format(
                                            Locale.ROOT,
                                            "%.2f",
                                            me.kills() / (float) Math.max(1, me.deaths()) * k),
                            false));
        }
        cells.add(new Cell("МЕСТО", k -> rank + " / " + result.players().size(), rank <= 3));
        if (!points.isEmpty()) {
            cells.add(
                    new Cell(
                            "НА ТОЧКАХ",
                            k -> MatchResult.clock(Math.round(me.pointTime() * k)),
                            false));
        }
        cells.add(
                new Cell("ЛУЧШАЯ СЕРИЯ", k -> String.valueOf(Math.round(me.streak() * k)), false));
        if (!points.isEmpty()) {
            cells.add(
                    new Cell(
                            "НЕЙТРАЛИЗАЦИИ",
                            k -> String.valueOf(Math.round(me.neutralizations() * k)),
                            false));
        }
        if (me.droneKills() > 0 || me.droneDamage() > 0) {
            // A drone operator's own cell in place of the class, which it names anyway; the grid
            // keeps its rows.
            cells.add(
                    new Cell(
                            "ДРОНЫ",
                            k ->
                                    Math.round(me.droneKills() * k)
                                            + " · "
                                            + MatchResult.compact(Math.round(me.droneDamage() * k)),
                            false));
        } else {
            cells.add(new Cell("КЛАСС", k -> me.cls().isEmpty() ? "—" : me.cls(), false));
        }

        int widestLabel = cells.stream().mapToInt(cell -> font.width(cell.label())).max().orElse(0);
        int cols = gridColumns(w, widestLabel, cells.size());
        int rows = (cells.size() + cols - 1) / cols;
        int cellW = (w - (cols - 1) * 3) / cols;
        int cellH = 34;
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            float start = 2.3f + i * 0.07f;
            float a = fade(t, start, 0.25f);
            if (a <= 0) continue;
            int cx = x + (i % cols) * (cellW + 3);
            int cy = y + (i / cols) * (cellH + 3) + Math.round((1 - a) * 5);
            g.fill(
                    cx,
                    cy,
                    cx + cellW,
                    cy + cellH,
                    BfTheme.alpha(BfTheme.CARD, Math.round(0xFF * a)));
            BfTheme.outline(
                    g, cx, cy, cellW, cellH, BfTheme.alpha(BfTheme.BORDER, Math.round(0xFF * a)));
            text(g, font, cell.label(), cx + 7, cy + 6, BfTheme.MUTED, a);
            float k = ease(clamp((t - start) / 0.8f));
            String value = cell.value().apply(k);
            float scale = Math.min(1.5f, (cellW - 14) / (float) Math.max(1, font.width(value)));
            big(
                    g,
                    font,
                    value,
                    cx + 7,
                    cy + 17,
                    scale,
                    cell.highlight() ? BfTheme.AMBER_BRIGHT : BfTheme.TEXT,
                    a);
        }
        return y + rows * (cellH + 3) - 3;
    }

    // ---- состав ----

    private static void roster(GuiGraphics g, Font font, int x, int y, int w, int h, float t) {
        boolean pointColumn = !points.isEmpty();
        int colTime = x + w - 4;
        int colKd = pointColumn ? colTime - 72 : colTime;
        int colScore = colKd - 58;
        float head = fade(t, 3.0f, 0.3f);
        String title =
                !result.teams() || result.me() == null
                        ? "УЧАСТНИКИ"
                        : "RedTeam".equals(result.me().team())
                                ? "КОМАНДА · КРАСНЫЕ"
                                : "КОМАНДА · СИНИЕ";
        int titleColor =
                !result.teams() || result.me() == null
                        ? BfTheme.MUTED_DARK
                        : TabletMapTab.teamColor(result.me().team());
        text(g, font, title, x + 18, y, titleColor, head);
        right(g, font, "ОЧКИ", colScore, y, BfTheme.MUTED_DARK, head);
        right(g, font, "У / С", colKd, y, BfTheme.MUTED_DARK, head);
        if (pointColumn) right(g, font, "НА ТОЧКАХ", colTime, y, BfTheme.MUTED_DARK, head);

        int rowH = ROSTER_ROW_H;
        List<MatchResult.Player> roster = result.roster();
        int pages = pageCount(roster.size(), h, result.me());
        lastPages = Math.max(1, pages);
        resultPage = MenuLayout.clampPage(resultPage, lastPages);
        List<MatchResult.Player> shown = visiblePage(roster, result.me(), h, resultPage);
        int rowY = y + ROSTER_HEAD_H;
        for (int i = 0; i < shown.size(); i++) {
            MatchResult.Player p = shown.get(i);
            float a = fade(t, 3.1f + i * 0.07f, 0.3f);
            if (a <= 0) continue;
            int dx = Math.round((1 - a) * 12);
            boolean mine = p == result.me();
            g.fill(
                    x,
                    rowY,
                    x + w,
                    rowY + 1,
                    BfTheme.alpha(BfTheme.BORDER_SOFT, Math.round(0xFF * a)));
            if (mine)
                g.fill(
                        x,
                        rowY + 1,
                        x + w,
                        rowY + rowH,
                        BfTheme.alpha(BfTheme.AMBER, Math.round(0x1E * a)));
            int ink =
                    mine ? BfTheme.AMBER_BRIGHT : p.deserter() ? BfTheme.MUTED_DARK : BfTheme.TEXT;
            int ty = rowY + 4;
            text(
                    g,
                    font,
                    String.valueOf(roster.indexOf(p) + 1),
                    x + 4 + dx,
                    ty,
                    BfTheme.MUTED_DARK,
                    a);
            String name = p.name() + (p.deserter() ? " · ушёл" : "");
            text(
                    g,
                    font,
                    BfTheme.ellipsize(font, name, colScore - x - 60),
                    x + 18 + dx,
                    ty,
                    ink,
                    a);
            right(g, font, String.valueOf(p.score()), colScore + dx, ty, ink, a);
            right(g, font, p.kills() + " / " + p.deaths(), colKd + dx, ty, ink, a);
            if (pointColumn)
                right(g, font, MatchResult.clock(p.pointTime()), colTime + dx, ty, ink, a);
            rowY += rowH;
        }
        if (lastPages > 1)
            centered(
                    g,
                    font,
                    MenuLayout.pageLabel(resultPage, lastPages),
                    x + w / 2,
                    y + h - 10,
                    BfTheme.MUTED,
                    head);
    }

    // ---- «железо» планшета поверх экрана: мерцание включения, скан, строки развёртки ----

    private static void screenEffects(GuiGraphics g, TabletFrame f, float t) {
        int x0 = f.contentX(), y0 = f.contentY(), x1 = x0 + f.contentW(), y1 = y0 + f.contentH();
        for (int y = y0; y < y1; y += 3) g.fill(x0, y, x1, y + 1, 0x16000000);
        if (t < 1.8f && t > 0.55f) {
            float p = (t - 0.55f) / 1.25f;
            int band = Math.max(24, f.contentH() / 5);
            int cy = y0 - band + Math.round((f.contentH() + 2 * band) * p);
            g.fillGradient(x0, Math.max(y0, cy - band), x1, Math.min(y1, cy), 0, 0x1EF5A524);
            g.fillGradient(x0, Math.max(y0, cy), x1, Math.min(y1, cy + band), 0x1EF5A524, 0);
        }
        // Экран «оживает» рывками: тьма, тёплая вспышка, провал, снова свет.
        int[] boot = {0xFF070604, 0x30FFE0B0, 0xC0070604, 0x00000000, 0x80070604, 0x00000000};
        int step = t < 0.35f ? 0 : (int) ((t - 0.35f) / 0.1f);
        if (step < boot.length && boot[step] != 0) g.fill(x0, y0, x1, y1, boot[step]);
    }

    // ---- мелочи ----

    private static void text(
            GuiGraphics g, Font font, String s, float x, float y, int color, float alpha) {
        int a = Math.round(((color >>> 24) & 255) * clamp(alpha));
        // Шрифт считает альфу меньше 4 непрозрачной -- почти прозрачный текст просто не рисуем.
        if (a < 8 || s.isEmpty()) return;
        g.drawString(font, s, Math.round(x), Math.round(y), (color & 0xFFFFFF) | a << 24, false);
    }

    private static void right(
            GuiGraphics g, Font font, String s, int rightX, int y, int color, float alpha) {
        text(g, font, s, rightX - font.width(s), y, color, alpha);
    }

    private static void centered(
            GuiGraphics g, Font font, String s, int cx, int y, int color, float alpha) {
        text(g, font, s, cx - font.width(s) / 2f, y, color, alpha);
    }

    private static void big(
            GuiGraphics g,
            Font font,
            String s,
            float x,
            float y,
            float scale,
            int color,
            float alpha) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1f);
        text(g, font, s, 0, 0, color, alpha);
        pose.popPose();
    }

    private static float fade(float t, float start, float duration) {
        return ease(clamp((t - start) / duration));
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    private static float ease(float v) {
        return 1 - (1 - v) * (1 - v) * (1 - v);
    }

    /** ease-out с перелётом: штамп и маркеры «падают» на место с отскоком. */
    static float back(float v) {
        if (v <= 0) return 0;
        float c1 = 1.70158f, c3 = c1 + 1, u = v - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }
}
