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
        if (ui == null || !ui.has("tabActive") || !ui.get("tabActive").getAsBoolean()) return false;
        return !(ui.has("dead") && ui.get("dead").getAsBoolean());
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
        if (ui == null || !ui.has("battleCredits")) return -1;
        double balance = ui.get("battleCredits").getAsDouble();
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
        if (latestUIData != null && latestUIData.has("battleCredits") && gain.has("balance")) {
            latestUIData.addProperty("battleCredits", gain.get("balance").getAsDouble());
        }
        CREDIT_FEED.add(
                gain.get("source").getAsString(),
                gain.get("label").getAsString(),
                gain.get("amount").getAsDouble(),
                net.minecraft.Util.getMillis());
    }

    /** Последний UI-пакет матча (только чтение); null вне матча. */
    public static JsonObject uiData() {
        return latestUIData;
    }

    public static String playerTeam() {
        JsonObject ui = latestUIData;
        return ui != null && ui.has("playerTeam") ? ui.get("playerTeam").getAsString() : "";
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
                    && !next.get("tabActive").getAsBoolean()
                    && !java.util.Objects.equals(
                            latestUIData.get("tabActive"), next.get("tabActive"))) clearKillFeed();
            latestUIData = next;
            if (latestUIData.has("showHud") && !latestUIData.get("showHud").getAsBoolean()) {
                shouldRenderMainHud = false;
                PointMarkerRenderer.clearAllPoints();
                return;
            }
            if (latestUIData.has("rT") && latestUIData.has("bT")) {
                redTickets = latestUIData.get("rT").getAsInt();
                blueTickets = latestUIData.get("bT").getAsInt();
                shouldRenderMainHud = true;
            }
            if (latestUIData.has("pts")) {
                PointMarkerRenderer.updatePoints(UiJson.arr(latestUIData, "pts"));
            }
        } catch (Exception e) {
            // Ignore parse errors
        }
    }

    /** «m:ss» из timeLeft UI-пакета; null -- у матча нет лимита времени (или сервер старый). */
    static String matchTime(JsonObject ui) {
        if (ui == null || !ui.has("timeLeft")) return null;
        int left = Math.max(0, ui.get("timeLeft").getAsInt());
        return left / 60 + ":" + String.format("%02d", left % 60);
    }

    private static void onRenderGui(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        WeaponHud.render(graphics);
        if (mc.options.hideGui || mc.options.keyPlayerList.isDown() || mc.screen != null) return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        if (!shouldRenderMainHud) return;

        int centerX = screenWidth / 2;
        int yPos = 6;
        int barHeight = 20;

        // Calculate dynamic dimensions
        String redStr = String.valueOf(redTickets);
        String blueStr = String.valueOf(blueTickets);
        int redWidth = mc.font.width(redStr);
        int blueWidth = mc.font.width(blueStr);

        int count = 0;
        int size = 11;
        int gap = 6;
        int pointsWidth = 0;
        JsonArray ptsArray = null;

        if (latestUIData != null && latestUIData.has("pts")) {
            ptsArray = UiJson.arr(latestUIData, "pts");
            count = ptsArray.size();
            if (count > 0) {
                pointsWidth = count * size + (count - 1) * gap;
            }
        }

        // Лимит времени есть только в режимах без точек (командный бой, снайперы): таймер занимает
        // их место в центре.
        String timeStr = count == 0 ? matchTime(latestUIData) : null;
        if (timeStr != null) pointsWidth = mc.font.width(timeStr);

        // Spacing: 10px edge padding, 15px gap to points
        int maxTicketWidth = Math.max(redWidth, blueWidth);
        int halfWidth = (pointsWidth / 2) + 15 + maxTicketWidth + 10;
        barHalfWidth = halfWidth;
        topStackBottom = yPos + 38;

        int barLeft = centerX - halfWidth;
        int barRight = centerX + halfWidth;

        // 1. Draw background
        graphics.fill(barLeft, yPos, barRight, yPos + barHeight, BfTheme.alpha(BfTheme.BG, 0xCC));
        BfTheme.outline(graphics, barLeft, yPos, barRight - barLeft, barHeight, BfTheme.BORDER);
        BfTheme.ticks(graphics, barLeft, yPos, barRight - barLeft, barHeight, 4, BfTheme.AMBER);

        // 2. Draw Red tickets (10px padding from left edge, vertically centered at yPos + 6)
        graphics.drawString(mc.font, redStr, barLeft + 10, yPos + 6, BfTheme.RED_TEAM, true);

        // 3. Draw Blue tickets (10px padding from right edge, vertically centered at yPos + 6)
        int blueStrWidth = mc.font.width(blueStr);
        graphics.drawString(
                mc.font, blueStr, barRight - 10 - blueStrWidth, yPos + 6, BfTheme.BLUE_TEAM, true);

        if (timeStr != null)
            graphics.drawCenteredString(mc.font, timeStr, centerX, yPos + 6, BfTheme.TEXT);

        // 5. Draw capture points
        if (ptsArray != null && count > 0) {
            int startX = -pointsWidth / 2 + size / 2;

            for (int i = 0; i < count; i++) {
                JsonObject ptObj = UiJson.asObject(ptsArray.get(i));
                if (ptObj == null) continue;
                String name = ptObj.has("n") ? ptObj.get("n").getAsString() : "?";
                String owner = ptObj.has("o") ? ptObj.get("o").getAsString() : "NEUTRAL";
                double progress = ptObj.has("p") ? ptObj.get("p").getAsDouble() : 0.0;
                String capturingTeam =
                        ptObj.has("claiming")
                                ? ptObj.get("claiming").getAsString()
                                : ptObj.has("c") ? ptObj.get("c").getAsString() : "NONE";

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
            if (zoneObj.has("active") && zoneObj.get("active").getAsBoolean()) {
                String name =
                        zoneObj.has("name")
                                ? PointMarkerRenderer.displayName(zoneObj.get("name").getAsString())
                                : "?";
                String owner =
                        zoneObj.has("owner") ? zoneObj.get("owner").getAsString() : "NEUTRAL";
                int progress = zoneObj.has("progress") ? zoneObj.get("progress").getAsInt() : 0;
                String status = zoneObj.has("status") ? zoneObj.get("status").getAsString() : "";
                String claiming =
                        zoneObj.has("claiming") ? zoneObj.get("claiming").getAsString() : "NONE";
                String capturing =
                        zoneObj.has("capturing") ? zoneObj.get("capturing").getAsString() : "NONE";

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
                                halfWidth,
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
