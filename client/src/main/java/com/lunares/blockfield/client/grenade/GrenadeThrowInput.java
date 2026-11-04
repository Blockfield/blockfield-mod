package com.lunares.blockfield.client.grenade;

import com.lunares.blockfield.common.grenade.GrenadeThrowMode;
import com.lunares.blockfield.common.grenade.GrenadeThrowSupport;
import com.lunares.blockfield.network.packet.GrenadeThrowC2SPacket;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/**
 * Два способа броска поверх нативного броска мода: ЛКМ -- дальний, ПКМ -- короткий. Удержание,
 * анимация, звук, сила и запал (cooking) остаются у LR Tactical/SuperbWarfare: клиент запускает их
 * обычный use, сообщает серверу способ и держит use, пока нажата ЛКМ. Короткий бросок замедляет
 * сервер.
 */
public final class GrenadeThrowInput {
    // Способ текущего удержания гранаты; null -- гранату не держим.
    private static GrenadeThrowMode active;
    // Use запущен ЛКМ: ванильный handleKeybinds отпускает предмет, когда «использовать» не нажато.
    private static boolean heldByAttack;

    private GrenadeThrowInput() {}

    public static void register() {
        com.lunares.blockfield.client.combat.PreMatchCombat.register();
        ClientTickEvents.START_CLIENT_TICK.register(GrenadeThrowInput::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(client));
    }

    public static boolean holdingSupported(Minecraft mc) {
        return mc.player != null && GrenadeThrowSupport.kind(mc.player.getMainHandItem()) != null;
    }

    /**
     * Из миксина startAttack/startUseItem. true -- ввод принадлежит броску, ванильная обработка не
     * нужна.
     */
    public static boolean start(Minecraft mc, GrenadeThrowMode mode) {
        LocalPlayer player = mc.player;
        if (!holdingSupported(mc) || mc.gameMode == null) return false;
        if (com.lunares.blockfield.client.combat.PreMatchCombat.noAttacks()) return true;
        if (player.isUsingItem() || player.isHandsBusy()) return true;
        if (ClientPlayNetworking.canSend(GrenadeThrowC2SPacket.TYPE)) {
            ClientPlayNetworking.send(
                    new GrenadeThrowC2SPacket(mode.wireId(), player.getInventory().selected));
        }
        InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (result.consumesAction() && result.shouldSwing())
            player.swing(InteractionHand.MAIN_HAND);
        if (player.isUsingItem()) {
            active = mode;
            heldByAttack = mode == GrenadeThrowMode.OVERHAND;
        }
        return true;
    }

    private static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || !player.isUsingItem()) {
            reset(mc);
            return;
        }
        // До handleKeybinds этого тика: отпускание ЛКМ отпускает гранату так же, как отпускание
        // ПКМ.
        if (heldByAttack) mc.options.keyUse.setDown(mc.options.keyAttack.isDown());
    }

    private static void reset(Minecraft mc) {
        if (heldByAttack) mc.options.keyUse.setDown(false);
        heldByAttack = false;
        active = null;
    }
}
