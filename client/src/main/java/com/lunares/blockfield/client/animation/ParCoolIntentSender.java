package com.lunares.blockfield.client.animation;

import com.alrex.parcool.client.input.KeyBindings;
import com.lunares.blockfield.network.packet.ParCoolIntentC2SPacket;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Sends the local player's own lean keys after each client tick, the values its native SkyDive and
 * passive animation read in that tick. Observers never use their own keys for this player.
 */
public final class ParCoolIntentSender {
    private static int sent = -1;

    private ParCoolIntentSender() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(ParCoolIntentSender::tick);
        // A new connection has no accepted intent until the first send.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sent = -1);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> sent = -1);
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || !ClientPlayNetworking.canSend(ParCoolIntentC2SPacket.TYPE)) return;
        int mask =
                (KeyBindings.isKeyForwardDown() ? ParCoolIntentC2SPacket.FORWARD : 0)
                        | (KeyBindings.isKeyBackDown() ? ParCoolIntentC2SPacket.BACK : 0)
                        | (KeyBindings.isKeyLeftDown() ? ParCoolIntentC2SPacket.LEFT : 0)
                        | (KeyBindings.isKeyRightDown() ? ParCoolIntentC2SPacket.RIGHT : 0);
        if (mask == sent) return;
        sent = mask;
        ClientPlayNetworking.send(new ParCoolIntentC2SPacket(mask));
    }
}
