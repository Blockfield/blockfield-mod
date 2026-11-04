package com.lunares.blockfield.client.gui.screen;

import com.google.gson.JsonObject;
import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.tablet.Tablet;
import com.lunares.blockfield.network.packet.MenuActionC2SPacket;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

final class MatchActions {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 24;
    private static final int GAP = 10;

    private MatchActions() {}

    private static boolean canSwitch(JsonObject data) {
        return data != null
                && data.has("canSwitchTeam")
                && data.get("canSwitchTeam").getAsBoolean();
    }

    /** В FFA и на полигоне команд нет: кнопка смены команды не показывается. */
    private static boolean isFfa(JsonObject data) {
        Minecraft mc = Minecraft.getInstance();
        return data != null && data.has("ffa") && data.get("ffa").getAsBoolean()
                || mc.level != null
                        && mc.level
                                .dimension()
                                .location()
                                .toString()
                                .equals("blockfield_shared:firing_range");
    }

    static void render(
            GuiGraphics graphics,
            Font font,
            int width,
            int height,
            int mouseX,
            int mouseY,
            JsonObject data,
            String focus) {
        int x = width - 40 - WIDTH * 2 - GAP;
        int y = height - 44;
        if (isFfa(data)) {
            x += WIDTH + GAP;
            BfTheme.button(
                    graphics,
                    font,
                    x,
                    y,
                    WIDTH,
                    HEIGHT,
                    leaveLabel(),
                    BfTheme.hit(mouseX, mouseY, x, y, WIDTH, HEIGHT) || "match:leave".equals(focus),
                    true,
                    false);
            return;
        }
        boolean enabled = canSwitch(data);
        BfTheme.button(
                graphics,
                font,
                x,
                y,
                WIDTH,
                HEIGHT,
                "СМЕНИТЬ КОМАНДУ",
                BfTheme.hit(mouseX, mouseY, x, y, WIDTH, HEIGHT) || "match:switch".equals(focus),
                enabled,
                false);
        if (!enabled && BfTheme.hit(mouseX, mouseY, x, y, WIDTH, HEIGHT)) {
            graphics.drawString(
                    font,
                    "Переход доступен из большей команды при наличии места",
                    x,
                    y - 12,
                    BfTheme.TEXT_SECONDARY,
                    false);
        }
        x += WIDTH + GAP;
        BfTheme.button(
                graphics,
                font,
                x,
                y,
                WIDTH,
                HEIGHT,
                leaveLabel(),
                BfTheme.hit(mouseX, mouseY, x, y, WIDTH, HEIGHT) || "match:leave".equals(focus),
                true,
                false);
    }

    private static String leaveLabel() {
        return DestructiveConfirm.armed("leave", Util.getMillis())
                ? "ТОЧНО ПОКИНУТЬ?"
                : "ПОКИНУТЬ МАТЧ";
    }

    static List<String> focusOrder(JsonObject data) {
        List<String> actions = new ArrayList<>();
        if (!isFfa(data) && canSwitch(data)) actions.add("match:switch");
        actions.add("match:leave");
        return actions;
    }

    static boolean activate(Minecraft minecraft, JsonObject data, String action) {
        if (!focusOrder(data).contains(action)) return false;
        if (minecraft != null && minecraft.player != null) {
            boolean leave = action.equals("match:leave");
            if (leave && !DestructiveConfirm.confirm("leave", Util.getMillis())) return true;
            ClientPlayNetworking.send(new MenuActionC2SPacket(leave ? "leave" : "switch_team"));
            if (leave) Tablet.closeDeployment();
        }
        return true;
    }

    static boolean click(
            Minecraft minecraft, int width, int height, int mouseX, int mouseY, JsonObject data) {
        int x = width - 40 - WIDTH * 2 - GAP;
        int y = height - 44;
        if (!isFfa(data) && BfTheme.hit(mouseX, mouseY, x, y, WIDTH, HEIGHT)) {
            if (minecraft != null && minecraft.player != null && canSwitch(data)) {
                ClientPlayNetworking.send(new MenuActionC2SPacket("switch_team"));
            }
            return true;
        }
        if (BfTheme.hit(mouseX, mouseY, x + WIDTH + GAP, y, WIDTH, HEIGHT)) {
            if (minecraft != null
                    && minecraft.player != null
                    && DestructiveConfirm.confirm("leave", Util.getMillis())) {
                ClientPlayNetworking.send(new MenuActionC2SPacket("leave"));
                // Leaving to the hub sends no closeDeploymentMenu; the tablet drops the choice.
                Tablet.closeDeployment();
            }
            return true;
        }
        return false;
    }
}
