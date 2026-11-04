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

    public static boolean isTabActive() {
        return tabActive;
    }

    public static void setTabActive(boolean active) {
        tabActive = active;
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

        int menuWidth = 400;
        int headerExtra = 16;
        int menuHeight = 200 + headerExtra;
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

        // Column header stripe
        g.fill(x, y + headerExtra, x + menuWidth, y + headerExtra + 15, BfTheme.BG_ELEVATED);
        if (ffa) {
            g.drawString(mc.font, "МЕСТО", x + 10, y + headerExtra + 4, BfTheme.MUTED, false);
            g.drawString(mc.font, "ИГРОК", x + 55, y + headerExtra + 4, BfTheme.MUTED, false);
        } else {
            g.drawString(mc.font, "ИГРОК", x + 10, y + headerExtra + 4, BfTheme.MUTED, false);
            g.drawString(mc.font, "КЛАСС", x + 95, y + headerExtra + 4, BfTheme.MUTED, false);
            g.drawString(mc.font, "ОЧКИ", x + 165, y + headerExtra + 4, BfTheme.MUTED, false);
        }
        g.drawString(mc.font, "УБ.", x + 225, y + headerExtra + 4, BfTheme.MUTED, false);
        g.drawString(mc.font, "СМ.", x + 265, y + headerExtra + 4, BfTheme.MUTED, false);
        if (ffa) g.drawString(mc.font, "СЕРИЯ", x + 305, y + headerExtra + 4, BfTheme.MUTED, false);
        else if (objective)
            g.drawString(mc.font, "ВРЕМЯ", x + 305, y + headerExtra + 4, BfTheme.MUTED, false);
        g.drawString(mc.font, "ПИНГ", x + 355, y + headerExtra + 4, BfTheme.MUTED, false);
        g.fill(x, y + headerExtra + 15, x + menuWidth, y + headerExtra + 16, BfTheme.BORDER_SOFT);

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

        int rowY = y + headerExtra + 20;
        int rowIndex = 0;
        for (Map.Entry<UUID, PlayerStatsRow> entry : rows) {
            if (rowY > y + menuHeight - 15) break;

            PlayerStatsRow stats = entry.getValue();
            var networkInfo =
                    mc.getConnection() != null
                            ? mc.getConnection().getPlayerInfo(entry.getKey())
                            : null;
            String name = networkInfo != null ? networkInfo.getProfile().getName() : "Offline/Left";

            if (rowIndex % 2 == 1) {
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
                g.drawString(mc.font, "#" + (rowIndex + 1), x + 10, rowY, BfTheme.AMBER, true);
                g.drawString(mc.font, name, x + 55, rowY, nameColor, true);
            } else {
                g.drawString(mc.font, name, x + 10, rowY, nameColor, true);
                String classDisplayName = getLocalizedClassName(stats.classId);
                g.drawString(mc.font, classDisplayName, x + 95, rowY, BfTheme.TEXT_SECONDARY, true);
                String scoreStr =
                        (stats.score % 1.0 == 0.0)
                                ? String.valueOf((int) stats.score)
                                : String.format(java.util.Locale.US, "%.1f", stats.score);
                g.drawString(mc.font, scoreStr, x + 165, rowY, BfTheme.AMBER, true);
            }

            g.drawString(mc.font, String.valueOf(stats.kills), x + 225, rowY, BfTheme.OK, true);
            g.drawString(
                    mc.font, String.valueOf(stats.deaths), x + 265, rowY, BfTheme.DANGER, true);

            if (ffa) {
                g.drawString(
                        mc.font, String.valueOf(stats.streak), x + 305, rowY, BfTheme.AMBER, true);
            } else if (objective) {
                String timeStr =
                        (stats.pointTime / 60)
                                + ":"
                                + String.format("%02d", (stats.pointTime % 60));
                g.drawString(mc.font, timeStr, x + 305, rowY, BfTheme.MUTED, true);
            }

            if (networkInfo != null) {
                g.drawString(
                        mc.font,
                        pingText(networkInfo),
                        x + 355,
                        rowY,
                        pingColor(networkInfo),
                        true);
            }

            rowY += 12;
            rowIndex++;
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
