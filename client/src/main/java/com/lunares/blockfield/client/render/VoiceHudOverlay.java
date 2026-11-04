package com.lunares.blockfield.client.render;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.ClientEventHandler;
import com.lunares.blockfield.client.voice.VoiceHudState;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.scores.PlayerTeam;

/**
 * Голосовой HUD под миникартой: VU-метр своего микрофона с бейджем канала (РАЦИЯ/РЯДОМ) и строки
 * говорящих с цветом их команды. Прямоугольник берётся у {@link MinimapHud} (вместе со строкой
 * координат), а когда миникарты нет — оверлей встаёт в левый верхний угол.
 */
public final class VoiceHudOverlay {

    private static final ResourceLocation RADIO_ICON = icon("radio");
    private static final ResourceLocation MIC_ICON = icon("microphone");
    private static final ResourceLocation SPEAKER_ICON = icon("speaker");
    private static final int ICON = 11;
    private static final int TEXTURE = 32;

    private static final int ROW_H = 14;
    private static final int ROW_GAP = 2;
    private static final int GAP_UNDER_MINIMAP = 3;
    private static final int NAME_MAX_W = 90;
    private static final float TEXT_SCALE = 0.7f;

    /** Строк больше экрана всё равно не прочитать, а рисовать их дешевле не становится. */
    private static final int MAX_ROWS = 6;

    private VoiceHudOverlay() {}

    public static void init() {
        HudRenderCallback.EVENT.register(VoiceHudOverlay::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> VoiceHudState.clear());
    }

    private static ResourceLocation icon(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                "blockfield", "textures/gui/voice/" + name + ".png");
    }

    private static void render(GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        // Планшет теперь настоящий Screen (mc.screen != null), открытый занимает весь экран сам.
        if (mc.player == null || mc.options.hideGui || mc.screen != null) return;

        VoiceHudState.tick(System.currentTimeMillis());

        boolean radioHeld = ClientEventHandler.isRadioHeld();
        boolean self = VoiceHudState.selfActive(radioHeld, System.currentTimeMillis());
        var speakers = VoiceHudState.speakers();
        if (!self && speakers.isEmpty()) return;

        Anchor anchor = anchor();
        int y = anchor.y();
        int rows = 0;

        if (self) {
            String badge = radioHeld ? "РАЦИЯ" : "РЯДОМ";
            drawRow(
                    g,
                    mc,
                    anchor,
                    y,
                    radioHeld ? RADIO_ICON : MIC_ICON,
                    VoiceHudState.micLevel(),
                    badge,
                    radioHeld ? BfTheme.AMBER : BfTheme.OK);
            y += ROW_H + ROW_GAP;
            rows++;
        }
        for (VoiceHudState.Speaker speaker : speakers) {
            if (rows++ >= MAX_ROWS) break;
            String name = name(mc, speaker.id);
            drawRow(
                    g,
                    mc,
                    anchor,
                    y,
                    speaker.radio() ? RADIO_ICON : SPEAKER_ICON,
                    speaker.level(),
                    mc.font.plainSubstrByWidth(name, (int) (NAME_MAX_W / TEXT_SCALE)),
                    teamColor(mc, name));
            y += ROW_H + ROW_GAP;
        }
    }

    private static void drawRow(
            GuiGraphics g,
            Minecraft mc,
            Anchor anchor,
            int y,
            ResourceLocation icon,
            double level,
            String text,
            int color) {
        int textW = (int) Math.ceil(mc.font.width(text) * TEXT_SCALE);
        int w = Math.max(60, 32 + textW + 5);
        int x = anchor.rightAligned() ? anchor.x() - w : anchor.x();

        BfTheme.panel(g, x, y, w, ROW_H, false);
        g.fill(x, y, x + 2, y + ROW_H, BfTheme.alpha(color, 0xDD));

        g.setColor(red(color), green(color), blue(color), 1f);
        g.blit(icon, x + 4, y + 2, ICON - 1, ICON - 1, 0, 0, TEXTURE, TEXTURE, TEXTURE, TEXTURE);
        g.setColor(1f, 1f, 1f, 1f);

        meter(g, x + 17, y + 3, level);

        g.pose().pushPose();
        g.pose().translate(x + 32, y + 4.5f, 0);
        g.pose().scale(TEXT_SCALE, TEXT_SCALE, 1f);
        g.drawString(mc.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    /** Четыре растущих деления: тихо — зелёное, крик — красное. */
    private static void meter(GuiGraphics g, int x, int y, double level) {
        double[] thresholds = {0.08, 0.28, 0.52, 0.78};
        int[] colors = {BfTheme.OK, BfTheme.OK, BfTheme.WARN, BfTheme.DANGER};
        for (int i = 0; i < 4; i++) {
            int h = 4 + i;
            int color =
                    level >= thresholds[i] ? colors[i] : BfTheme.alpha(BfTheme.MUTED_DARK, 0x40);
            g.fill(x + i * 3, y + (7 - h), x + i * 3 + 2, y + 7, color);
        }
    }

    private record Anchor(int x, int y, boolean rightAligned) {}

    /** Под миникартой, если она сейчас на экране; иначе — левый верхний угол. */
    private static Anchor anchor() {
        int[] box = MinimapHud.rect();
        if (box == null) return new Anchor(4, 4, false);
        return new Anchor(box[0], box[1] + box[3] + GAP_UNDER_MINIMAP, false);
    }

    private static String name(Minecraft mc, UUID id) {
        var player = mc.level == null ? null : mc.level.getPlayerByUUID(id);
        if (player != null) return player.getGameProfile().getName();
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(id);
        return info != null ? info.getProfile().getName() : "?";
    }

    private static int teamColor(Minecraft mc, String name) {
        PlayerTeam team = mc.level == null ? null : mc.level.getScoreboard().getPlayersTeam(name);
        Integer color = team == null || team.getColor() == null ? null : team.getColor().getColor();
        return color == null ? BfTheme.TEXT : 0xFF000000 | color;
    }

    private static float red(int color) {
        return ((color >> 16) & 0xFF) / 255f;
    }

    private static float green(int color) {
        return ((color >> 8) & 0xFF) / 255f;
    }

    private static float blue(int color) {
        return (color & 0xFF) / 255f;
    }
}
