package com.lunares.blockfield.client.render;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.network.packet.ScoreDeltaPacket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public class TabMenuRenderer {

    private static final Map<UUID, PlayerStatsRow> statsCache = new HashMap<>();
    private static boolean tabActive = false;
    private static boolean ffaMode = false;

    /** Design width the absolute column offsets below were laid out for. */
    static final int BASE_W = 400;

    static final int HEADER = 16;
    static final int ROW_H = 12;
    static final int FOOTER = 14;

    /** Fixed right-anchored numeric columns start this far left of the right edge. */
    static final int RIGHT_ZONE = 175;

    private static int tabPage = 0;

    public static boolean isTabActive() {
        return tabActive;
    }

    public static void setTabActive(boolean active) {
        if (active != tabActive) tabPage = 0;
        tabActive = active;
    }

    /** Table width: full design width on wide screens, shrunk to fit narrow GUI areas. */
    static int tabWidth(int screenWidth) {
        return Math.max(160, Math.min(BASE_W, screenWidth - 16));
    }

    /** Table height follows the screen so tall windows show more rows without paging. */
    static int tableHeight(int screenHeight) {
        return Math.max(100, screenHeight - 32);
    }

    /** Rows per page with the header, column stripe and footer reserved. */
    static int rowsPerPage(int height) {
        return Math.max(1, (height - HEADER - 20 - 16 - FOOTER) / ROW_H + 1);
    }

    /** Pages for the current roster; 0 with no rows. */
    public static int pageCount(int screenWidth, int screenHeight) {
        return pagesFor(statsCache.size(), rowsPerPage(tableHeight(screenHeight)));
    }

    static int pagesFor(int totalRows, int perPage) {
        if (totalRows <= 0) return 0;
        return (totalRows + perPage - 1) / perPage;
    }

    /**
     * Wheel paging while TAB is held. Returns false with a single page so the wheel keeps its
     * normal behavior (hotbar); the mixin cancels only consumed scrolls.
     */
    public static boolean scrollPages(double y, int pages) {
        if (pages <= 1 || y == 0) return false;
        tabPage = Math.max(0, Math.min(pages - 1, tabPage + (y > 0 ? -1 : 1)));
        return true;
    }

    static int currentPage(int pages) {
        if (pages <= 0) return 0;
        tabPage = Math.max(0, Math.min(pages - 1, tabPage));
        return tabPage;
    }

    /**
     * Left text-column offset: absolute at design width, squeezed into the space left of the fixed
     * right numeric columns on narrow screens. Values are ellipsized into their slots, so long
     * names can never reach the next column.
     */
    static int leftX(int base, int menuWidth) {
        return base * (menuWidth - RIGHT_ZONE) / (BASE_W - RIGHT_ZONE);
    }

    /**
     * Deathmatch has no scoreboard teams (see DeathmatchMode), so class/score/objective columns are
     * meaningless; the table shows place/kills/deaths/streak instead (bug: "no custom TAB in DM").
     */
    public static void setFfaMode(boolean ffa) {
        ffaMode = ffa;
    }

    public static void updatePlayerStatsCache(ScoreDeltaPacket packet) {
        statsCache.put(packet.playerUUID, new PlayerStatsRow(packet));
    }

    public static void removePlayer(UUID uuid) {
        statsCache.remove(uuid);
    }

    public static void clearCache() {
        statsCache.clear();
    }

    public static String getLocalizedClassName(String classId) {
        if (classId == null) return "Не выбран";
        switch (classId.toLowerCase(java.util.Locale.ROOT)) {
            case "assault":
                return "Штурмовик";
            case "mark":
                return "Марксман";
            case "tech":
                return "Техник";
            case "sniper":
                return "Снайпер";
            case "drone":
                return "Дроновод";
            default:
                return classId;
        }
    }

    /**
     * Ванильный latency: замер keepalive сервером (см. серверный KeepAliveLatencyMixin); 0 = замера
     * ещё не было.
     */
    public static String pingText(net.minecraft.client.multiplayer.PlayerInfo info) {
        return info.getLatency() > 0 ? String.valueOf(info.getLatency()) : "-";
    }

    public static int pingColor(net.minecraft.client.multiplayer.PlayerInfo info) {
        int ms = info.getLatency();
        if (ms <= 0) return BfTheme.MUTED;
        return ms < 80 ? BfTheme.OK : ms < 160 ? BfTheme.AMBER : BfTheme.DANGER;
    }

    /**
     * Рисуется после HUD модов через ScoreboardLayerMixin; ванильный список отменён отдельно.
     *
     * @return true, если меню отрисовано и ванильный список рисовать не нужно.
     */
    public static boolean renderTabMenu(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null || mc.getWindow() == null || mc.screen != null) return false;

        if (!isTabActive()) return false;

        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        int menuWidth = tabWidth(width);
        int menuHeight = tableHeight(height);
        int x = (width - menuWidth) / 2;
        int y = (height - menuHeight) / 2;

        BfTheme.panel(g, x, y, menuWidth, menuHeight, true);
        BfTheme.ticks(g, x, y, menuWidth, menuHeight, 6, BfTheme.AMBER);
        BfTheme.header(
                g,
                mc.font,
                x + 8,
                y + 5,
                menuWidth - 16,
                "ТАБЛИЦА БОЯ",
                statsCache.size() + " В БОЮ");

        boolean ffa = ffaMode;
        boolean objective = !ffa && HudRenderer.hasCapturePoints();

        // Column header stripe; numeric columns ride the right edge, text columns squeeze left.
        int nameX = x + leftX(ffa ? 55 : 10, menuWidth);
        int placeX = x + leftX(10, menuWidth);
        int classX = x + leftX(95, menuWidth);
        int scoreX = x + leftX(165, menuWidth);
        int killsX = x + menuWidth - 175;
        int deathsX = x + menuWidth - 135;
        int extraX = x + menuWidth - 95;
        int pingX = x + menuWidth - 45;
        g.fill(x, y + HEADER, x + menuWidth, y + HEADER + 15, BfTheme.BG_ELEVATED);
        if (ffa) {
            g.drawString(mc.font, "МЕСТО", placeX, y + HEADER + 4, BfTheme.MUTED, false);
            g.drawString(mc.font, "ИГРОК", nameX, y + HEADER + 4, BfTheme.MUTED, false);
        } else {
            g.drawString(mc.font, "ИГРОК", nameX, y + HEADER + 4, BfTheme.MUTED, false);
            g.drawString(mc.font, "КЛАСС", classX, y + HEADER + 4, BfTheme.MUTED, false);
            g.drawString(mc.font, "ОЧКИ", scoreX, y + HEADER + 4, BfTheme.MUTED, false);
        }
        g.drawString(mc.font, "УБ.", killsX, y + HEADER + 4, BfTheme.MUTED, false);
        g.drawString(mc.font, "СМ.", deathsX, y + HEADER + 4, BfTheme.MUTED, false);
        if (ffa) g.drawString(mc.font, "СЕРИЯ", extraX, y + HEADER + 4, BfTheme.MUTED, false);
        else if (objective)
            g.drawString(mc.font, "ВРЕМЯ", extraX, y + HEADER + 4, BfTheme.MUTED, false);
        g.drawString(mc.font, "ПИНГ", pingX, y + HEADER + 4, BfTheme.MUTED, false);
        g.fill(x, y + HEADER + 15, x + menuWidth, y + HEADER + 16, BfTheme.BORDER_SOFT);

        // FFA has no scoreboard teams to sort by, so rank by kills (ties broken by fewer deaths)
        // instead
        // of the HashMap's arbitrary order -- "МЕСТО" would otherwise be meaningless.
        List<Map.Entry<UUID, PlayerStatsRow>> rows = new ArrayList<>(statsCache.entrySet());
        if (ffa) {
            rows.sort(
                    Comparator.<Map.Entry<UUID, PlayerStatsRow>>comparingInt(
                                    e -> -e.getValue().kills)
                            .thenComparingInt(e -> e.getValue().deaths));
        } else {
            rows.sort(
                    Comparator.<Map.Entry<UUID, PlayerStatsRow>>comparingDouble(
                                    e -> -e.getValue().score)
                            .thenComparingInt(e -> -e.getValue().kills));
        }

        int perPage = rowsPerPage(menuHeight);
        int pages = pagesFor(rows.size(), perPage);
        int page = currentPage(pages);
        int lastRowY = y + menuHeight - 15 - (pages > 1 ? FOOTER : 0);

        int rowY = y + HEADER + 20;
        int rowIndex = page * perPage;
        int end = Math.min(rows.size(), rowIndex + perPage);
        for (int i = rowIndex; i < end; i++) {
            if (rowY > lastRowY) break;
            Map.Entry<UUID, PlayerStatsRow> entry = rows.get(i);

            PlayerStatsRow stats = entry.getValue();
            var networkInfo =
                    mc.getConnection() != null
                            ? mc.getConnection().getPlayerInfo(entry.getKey())
                            : null;
            String name = networkInfo != null ? networkInfo.getProfile().getName() : "Offline/Left";

            if ((i - rowIndex) % 2 == 1) {
                g.fill(
                        x + 1,
                        rowY - 2,
                        x + menuWidth - 1,
                        rowY + 10,
                        BfTheme.alpha(BfTheme.POPOVER, 0x80));
            }

            int nameColor = BfTheme.TEXT;
            if (!ffa && mc.level != null) {
                net.minecraft.world.scores.PlayerTeam playerTeam =
                        mc.level.getScoreboard().getPlayersTeam(name);
                if (playerTeam != null) {
                    String teamName = playerTeam.getName();
                    if ("RedTeam".equals(teamName)) {
                        nameColor = BfTheme.RED_TEAM;
                    } else if ("BlueTeam".equals(teamName)) {
                        nameColor = BfTheme.BLUE_TEAM;
                    }
                }
            }

            if (ffa) {
                g.drawString(mc.font, "#" + (i + 1), placeX, rowY, BfTheme.AMBER, true);
                g.drawString(
                        mc.font,
                        BfTheme.ellipsize(mc.font, name, killsX - nameX - 4),
                        nameX,
                        rowY,
                        nameColor,
                        true);
            } else {
                g.drawString(
                        mc.font,
                        BfTheme.ellipsize(mc.font, name, classX - nameX - 4),
                        nameX,
                        rowY,
                        nameColor,
                        true);
                String classDisplayName = getLocalizedClassName(stats.classId);
                g.drawString(
                        mc.font,
                        BfTheme.ellipsize(mc.font, classDisplayName, scoreX - classX - 4),
                        classX,
                        rowY,
                        BfTheme.TEXT_SECONDARY,
                        true);
                String scoreStr =
                        (stats.score % 1.0 == 0.0)
                                ? String.valueOf((int) stats.score)
                                : String.format(java.util.Locale.US, "%.1f", stats.score);
                g.drawString(
                        mc.font,
                        BfTheme.ellipsize(mc.font, scoreStr, killsX - scoreX - 4),
                        scoreX,
                        rowY,
                        BfTheme.AMBER,
                        true);
            }

            g.drawString(mc.font, String.valueOf(stats.kills), killsX, rowY, BfTheme.OK, true);
            g.drawString(
                    mc.font, String.valueOf(stats.deaths), deathsX, rowY, BfTheme.DANGER, true);

            if (ffa) {
                g.drawString(
                        mc.font, String.valueOf(stats.streak), extraX, rowY, BfTheme.AMBER, true);
            } else if (objective) {
                String timeStr =
                        (stats.pointTime / 60)
                                + ":"
                                + String.format("%02d", (stats.pointTime % 60));
                g.drawString(mc.font, timeStr, extraX, rowY, BfTheme.MUTED, true);
            }

            if (networkInfo != null) {
                g.drawString(
                        mc.font, pingText(networkInfo), pingX, rowY, pingColor(networkInfo), true);
            }

            rowY += ROW_H;
        }
        if (pages > 1) {
            String pager = "‹ стр. " + (page + 1) + "/" + pages + " · колесо ›";
            g.drawCenteredString(
                    mc.font, pager, x + menuWidth / 2, y + menuHeight - 11, BfTheme.MUTED);
        }
        return true;
    }

    public static class PlayerStatsRow {
        public final int kills, deaths, pointTime, streak;
        public final double score;
        public final String classId;

        public PlayerStatsRow(ScoreDeltaPacket p) {
            this.kills = p.kills;
            this.deaths = p.deaths;
            this.score = p.score;
            this.pointTime = p.pointTime;
            this.classId = p.classId;
            this.streak = p.streak;
        }
    }
}
