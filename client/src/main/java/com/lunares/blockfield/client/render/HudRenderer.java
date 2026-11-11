package com.lunares.blockfield.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.network.packet.UiJson;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public class HudRenderer {

    public static void init() {
        HudRenderCallback.EVENT.register(HudRenderer::onRenderGui);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onClientLogout());
        // Proxy switches (workshop -> lobby) join a new server without DISCONNECT.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onClientLogout());
    }

    private static int redTickets = 0;
    private static int blueTickets = 0;

    private static int barHalfWidth = 68;
    private static int topStackBottom = 44;
    private static boolean shouldRenderMainHud = false;
    private static JsonObject latestUIData = null;

    public static boolean isHudActive() {
        return shouldRenderMainHud;
    }

    /**
     * Игрок в бою: фаза GAME и он не ждёт возрождения. В мини-хабе (подготовка, смерть) -- false.
     */
    public static boolean isDeployed() {
        JsonObject ui = latestUIData;
        if (!UiJson.bool(ui, "tabActive", false)) return false;
        return !UiJson.bool(ui, "dead", false);
    }

    private static void onClientLogout() {
        clearKillFeed();
        shouldRenderMainHud = false;
        latestUIData = null;
        CREDIT_FEED.clear();
        redTickets = 0;
        blueTickets = 0;
        TabMenuRenderer.setTabActive(false);
        TabMenuRenderer.setFfaMode(false);
        TabMenuRenderer.clearCache();
        PointMarkerRenderer.clearAllPoints();
    }

    public static void clearKillFeed() {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("superbwarfare"))
            com.atsuishio.superbwarfare.event.KillMessageHandler.INSTANCE.getQUEUE().clear();
    }

    /** Матч с точками захвата: без них (TDM, Deathmatch) время на точках ничего не значит. */
    public static boolean hasCapturePoints() {
        return !UiJson.arr(latestUIData, "pts").isEmpty();
    }

    /** Боевая карта матча для планшета: id снимка и границы зоны боя; null — игрок не в бою. */
    public static JsonObject matchMap() {
        return UiJson.obj(latestUIData, "map");
    }

    /**
     * Точки захвата из последнего UI-пакета: в конце матча их маркеры уже погашены, а итогу они
     * нужны.
     */
    public static com.google.gson.JsonArray matchPoints() {
        return UiJson.arrOrNull(latestUIData, "pts");
    }

    static final CreditFeed CREDIT_FEED = new CreditFeed();

    /** Баланс БК из UI-пакета; -1 -- в этом режиме кошелька нет. */
    static double credits() {
        return credits(latestUIData);
    }

    private static double credits(JsonObject ui) {
        double balance = UiJson.decimal(ui, "battleCredits", -1);
        return Double.isFinite(balance) && balance >= 0 ? balance : -1;
    }

    public static String creditsText() {
        return creditsText(latestUIData);
    }

    static String creditsText(JsonObject ui) {
        double balance = credits(ui);
        return balance < 0 ? "" : formatCredits(balance) + " БК";
    }

    static String formatCredits(double amount) {
        return String.format(java.util.Locale.ROOT, "%,d", (long) amount).replace(',', ' ');
    }

    /**
     * Начисление приходит отдельным пакетом сразу, а полный UI -- раз в секунду: баланс правим на
     * месте.
     */
    public static void onCreditGain(JsonObject gain) {
        double balance = UiJson.decimal(gain, "balance", Double.NaN);
        double wallet = UiJson.decimal(latestUIData, "battleCredits", Double.NaN);
        if (latestUIData != null && Double.isFinite(wallet) && Double.isFinite(balance)) {
            latestUIData.addProperty("battleCredits", balance);
        }
        CREDIT_FEED.add(
                UiJson.str(gain, "source", ""),
                UiJson.str(gain, "label", ""),
                UiJson.decimal(gain, "amount", 0),
                net.minecraft.Util.getMillis());
    }

    /** Последний UI-пакет матча (только чтение); null вне матча. */
    public static JsonObject uiData() {
        return latestUIData;
    }

    public static String playerTeam() {
        return UiJson.str(latestUIData, "playerTeam", "");
    }

    public static int barHalfWidth() {
        return barHalfWidth;
    }

    /** Нижний край верхнего стека HUD: билеты, компас и баннер захвата, если он показан. */
    public static int topStackBottom() {
        return topStackBottom;
    }

    public static void updateTickets(int red, int blue) {
        redTickets = red;
        blueTickets = blue;
        shouldRenderMainHud = true;
    }

    public static void updateHUDData(String json) {
        try {
            JsonObject next = UiJson.parse(json);
            // The active HUD can arrive after the new round's first kill.
            if (latestUIData != null
                    && next.has("tabActive")
                    && !UiJson.bool(next, "tabActive", true)
                    && !java.util.Objects.equals(
                            latestUIData.get("tabActive"), next.get("tabActive"))) clearKillFeed();
            latestUIData = next;
            if (!UiJson.bool(latestUIData, "showHud", true)) {
                shouldRenderMainHud = false;
                PointMarkerRenderer.clearAllPoints();
                return;
            }
            int red = UiJson.integer(latestUIData, "rT", Integer.MIN_VALUE);
            int blue = UiJson.integer(latestUIData, "bT", Integer.MIN_VALUE);
            if (red != Integer.MIN_VALUE && blue != Integer.MIN_VALUE) {
                redTickets = red;
                blueTickets = blue;
                shouldRenderMainHud = true;
            }
            // Deathmatch sends no tickets: the FFA flag alone activates the top bar.
            if (UiJson.bool(latestUIData, "ffa", false)) shouldRenderMainHud = true;
            if (latestUIData.has("pts")) {
                PointMarkerRenderer.updatePoints(UiJson.arr(latestUIData, "pts"));
            }
        } catch (Exception e) {
            // Ignore parse errors
        }
    }

    /** «m:ss» из timeLeft UI-пакета; null -- у матча нет лимита времени (или сервер старый). */
    static String matchTime(JsonObject ui) {
        int left = UiJson.integer(ui, "timeLeft", Integer.MIN_VALUE);
        if (left == Integer.MIN_VALUE) return null;
        left = Math.max(0, left);
        return left / 60 + ":" + String.format("%02d", left % 60);
    }

    /**
     * «Фраги 7/30» for FFA, or null when the server did not send the new fields yet (an old server
     * still feeds the bottom line, so no top bar is drawn instead of a wrong one).
     */
    static String ffaScore(JsonObject ui) {
        int frags = UiJson.integer(ui, "frags", -1);
        int limit = UiJson.integer(ui, "killLimit", -1);
        if (frags < 0 || limit <= 0) return null;
        return frags + "/" + limit;
    }

    /**
     * FFA right cell: «name kills», «лидеры: N по K», «лидера нет», or null without the new server
     * fields.
     */
    static String ffaLeader(JsonObject ui) {
        if (ui == null || !ui.has("leaderName")) return null;
        int kills = UiJson.integer(ui, "leaderKills", 0);
        if (kills <= 0) return "лидера нет";
        int tied = UiJson.integer(ui, "leaderTied", 1);
        if (tied > 1) return "лидеры: " + tied + " по " + kills;
        String name = UiJson.str(ui, "leaderName", "");
        return name.isEmpty() ? "лидера нет" : name + " " + kills;
    }

    /** Landing-protection seconds from the UI packet; 0 hides the shared chip. */
    static int protectionSecs(JsonObject ui) {
        return Math.max(0, UiJson.integer(ui, "protection", 0));
    }

    /** Unified landing-protection wording for every mode; the seconds carry the information. */
    static String protectionText(int secs) {
        return "Защита · " + secs + " с";
    }

    private static void onRenderGui(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        WeaponHud.render(graphics);
        if (mc.options.hideGui || mc.options.keyPlayerList.isDown() || mc.screen != null) return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        if (!shouldRenderMainHud) return;

        int centerX = screenWidth / 2;
        int yPos = HudTopBar.Y;

        int avail = HudTopBar.sideAvail(screenWidth);
        String left;
        int leftColor;
        String center;
        String right;
        int rightColor;
        int centerW = 0;

        boolean ffa = UiJson.bool(latestUIData, "ffa", false);
        JsonArray ptsArray = null;
        if (ffa) {
            String score = latestUIData == null ? null : ffaScore(latestUIData);
            String time = latestUIData == null ? null : matchTime(latestUIData);
            String leader = latestUIData == null ? null : ffaLeader(latestUIData);
            // An old server still feeds the bottom actionbar line: draw no top bar instead of a
            // wrong one.
            if (score == null || time == null || leader == null) return;
            left = score;
            leftColor = BfTheme.RED_TEAM;
            center = time;
            right = BfTheme.ellipsize(mc.font, leader, avail);
            rightColor = BfTheme.AMBER;
        } else {
            // Calculate dynamic dimensions
            String redStr = String.valueOf(redTickets);
            String blueStr = String.valueOf(blueTickets);
            int count = 0;

            if (latestUIData != null && latestUIData.has("pts")) {
                ptsArray = UiJson.arr(latestUIData, "pts");
                count = ptsArray.size();
                if (count > 0) centerW = HudTopBar.pointsWidth(count);
            }

            // Лимит времени есть только в режимах без точек (командный бой, снайперы): таймер
            // занимает их место в центре.
            String timeStr = count == 0 ? matchTime(latestUIData) : null;
            if (timeStr != null) {
                int limit = UiJson.integer(latestUIData, "killLimit", -1);
                if (limit > 0) timeStr += " · до " + limit;
                centerW = mc.font.width(timeStr);
            }

            left = BfTheme.ellipsize(mc.font, redStr, avail);
            leftColor = BfTheme.RED_TEAM;
            center = timeStr;
            right = BfTheme.ellipsize(mc.font, blueStr, avail);
            rightColor = BfTheme.BLUE_TEAM;
        }

        var bar =
                HudTopBar.layout(
                        screenWidth,
                        mc.font.width(left),
                        center == null ? centerW : mc.font.width(center),
                        mc.font.width(right));
        HudTopBar.render(graphics, mc.font, bar, left, leftColor, center, right, rightColor);
        barHalfWidth = bar.half();
        topStackBottom = bar.bottom();

        int prot = latestUIData == null ? 0 : protectionSecs(latestUIData);
        if (prot > 0)
            topStackBottom =
                    HudTopBar.renderChip(
                            graphics,
                            mc.font,
                            centerX,
                            HudTopBar.chipTop(bar.bottom()),
                            protectionText(prot));

        JsonArray diamonds = ffa ? null : ptsArray;
        int diamondCount = diamonds == null ? 0 : diamonds.size();
        int size = HudTopBar.POINT;
        int gap = HudTopBar.POINT_GAP;
        int pointsWidth = HudTopBar.pointsWidth(diamondCount);

        // 5. Draw capture points
        if (diamondCount > 0) {
            int startX = -pointsWidth / 2 + size / 2;

            for (int i = 0; i < diamondCount; i++) {
                JsonObject ptObj = UiJson.asObject(diamonds.get(i));
                if (ptObj == null) continue;
                String name = UiJson.str(ptObj, "n", "?");
                String owner = UiJson.str(ptObj, "o", "NEUTRAL");
                double progress = UiJson.decimal(ptObj, "p", 0.0);
                String capturingTeam = UiJson.str(ptObj, "claiming", null);
                if (capturingTeam == null) capturingTeam = UiJson.str(ptObj, "c", "NONE");

                int pointX = centerX + startX + i * (size + gap);

                // Set square colors based on state
                int borderColor;
                int bgColor;
                if ("RED".equals(owner)) {
                    borderColor = BfTheme.RED_TEAM;
                    bgColor = BfTheme.RED_TEAM;
                } else if ("BLUE".equals(owner)) {
                    borderColor = BfTheme.BLUE_TEAM;
                    bgColor = BfTheme.BLUE_TEAM;
                } else {
                    borderColor = BfTheme.BORDER_HOVER;
                    bgColor = BfTheme.MUTED_DARK;
                }

                int progressFillColor = BfTheme.MUTED_DARK;

                boolean isUncapping =
                        progress > 0.0 && progress < 100.0 && !"NEUTRAL".equals(owner);
                boolean isCappingNeutral =
                        progress > 0.0 && progress < 100.0 && "NEUTRAL".equals(owner);

                if (isUncapping) {
                    progressFillColor = borderColor;
                    bgColor = BfTheme.MUTED_DARK;
                } else if (isCappingNeutral) {
                    bgColor = BfTheme.MUTED_DARK;
                    if ("RED".equals(capturingTeam)) progressFillColor = BfTheme.RED_TEAM;
                    else if ("BLUE".equals(capturingTeam)) progressFillColor = BfTheme.BLUE_TEAM;
                    else progressFillColor = BfTheme.AMBER;
                }

                // 1. Draw outer border (11x11 square: yPos + 4 to yPos + 15)
                graphics.fill(pointX - 5, yPos + 4, pointX + 6, yPos + 15, borderColor);

                // 2. Draw inner background (9x9 square: yPos + 5 to yPos + 14)
                graphics.fill(pointX - 4, yPos + 5, pointX + 5, yPos + 14, bgColor);

                // 3. Draw capture progress fill from bottom to top inside 9x9 area if capture is
                // active (0.0 < progress < 100.0)
                if (isUncapping || isCappingNeutral) {
                    int fillHeight =
                            Math.max(0, Math.min(9, (int) Math.round((progress / 100.0) * 9)));
                    if (fillHeight > 0) {
                        graphics.fill(
                                pointX - 4,
                                yPos + 14 - fillHeight,
                                pointX + 5,
                                yPos + 14,
                                progressFillColor);
                    }
                }

                // 4. Draw point letter (scaled down to 0.7x for better progress visibility,
                // centered)
                graphics.pose().pushPose();
                graphics.pose().translate(pointX + 0.75f, yPos + 9.5f, 0.0f);
                graphics.pose().scale(0.7f, 0.7f, 1.0f);
                String letter = name.isEmpty() ? "?" : String.valueOf(name.charAt(0)).toUpperCase();
                graphics.drawCenteredString(mc.font, letter, 0, -4, BfTheme.TEXT);
                graphics.pose().popPose();
            }
        }

        // 6. Draw Zone capture banner
        if (latestUIData != null && latestUIData.has("zone")) {
            JsonObject zoneObj = UiJson.obj(latestUIData, "zone");
            if (zoneObj == null) return;
            if (UiJson.bool(zoneObj, "active", false)) {
                String name = PointMarkerRenderer.displayName(UiJson.str(zoneObj, "name", "?"));
                String owner = UiJson.str(zoneObj, "owner", "NEUTRAL");
                int progress = UiJson.integer(zoneObj, "progress", 0);
                String status = UiJson.str(zoneObj, "status", "");
                String claiming = UiJson.str(zoneObj, "claiming", "NONE");
                String capturing = UiJson.str(zoneObj, "capturing", "NONE");

                int startY = yPos + 40; // под лентой-компасом PointMarkerRenderer

                int plateH = 20;
                // При ADS баннер гаснет вместе с разметкой точек, чтобы не перекрывать прицел.
                boolean ads = PointMarkerRenderer.aiming(mc);
                if (ads) {
                    graphics.flush();
                    com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 0.25f);
                }
                // Ширина ленты тикетов считается от числа точек: при 2-3 точках имя зоны и статус в
                // неё не влезали
                // и рисовались друг поверх друга. Плашка зоны растёт под свой текст.
                String statusMsg = status + " | " + progress + "%";
                int zoneHalf =
                        Math.max(
                                bar.half(),
                                (mc.font.width(name.toUpperCase()) + mc.font.width(statusMsg) + 21)
                                        / 2);
                BfTheme.panel(graphics, centerX - zoneHalf, startY, zoneHalf * 2, plateH, false);
                topStackBottom = startY + plateH;
                barHalfWidth = Math.max(barHalfWidth, zoneHalf);

                // Get team colors for texts
                int textColor = BfTheme.TEXT_SECONDARY;
                if ("NEUTRAL".equals(owner) && progress > 0 && "NONE".equals(capturing)) {
                    textColor = BfTheme.AMBER;
                } else if ("RED".equals(owner) || "RED".equals(claiming)) {
                    textColor = BfTheme.RED_TEAM;
                } else if ("BLUE".equals(owner) || "BLUE".equals(claiming)) {
                    textColor = BfTheme.BLUE_TEAM;
                }

                graphics.drawString(
                        mc.font, name.toUpperCase(), centerX - zoneHalf + 6, startY + 3, textColor);
                graphics.drawString(
                        mc.font,
                        statusMsg,
                        centerX + zoneHalf - 6 - mc.font.width(statusMsg),
                        startY + 3,
                        BfTheme.MUTED);
                BfTheme.progress(
                        graphics,
                        centerX - zoneHalf + 6,
                        startY + 14,
                        zoneHalf * 2 - 12,
                        3,
                        progress / 100.0f,
                        textColor);
                if (ads) {
                    graphics.flush();
                    com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                }
            }
        }
    }
}
