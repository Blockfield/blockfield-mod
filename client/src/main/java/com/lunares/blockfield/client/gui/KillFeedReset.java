package com.lunares.blockfield.client.gui;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * SBW-лента живёт дольше матча: чистим при смене комнаты, фазы/раунда и измерения. Пакеты уже в
 * главном потоке, очередь чистим сразу.
 */
public final class KillFeedReset {
    private KillFeedReset() {}

    private static String lastRoom;
    private static String lastPhase;
    private static int lastRound;
    private static boolean armed;
    private static ResourceKey<Level> lastDimension;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(KillFeedReset::onClientTick);
    }

    public static void onPresence(JsonObject presence) {
        String room = presence.has("id") ? presence.get("id").getAsString() : "?";
        String phase = presence.has("phase") ? presence.get("phase").getAsString() : "?";
        int round = presence.has("round") ? presence.get("round").getAsInt() : -1;
        if (!armed) {
            lastRoom = room;
            lastPhase = phase;
            lastRound = round;
            armed = true;
            return;
        }
        if (!room.equals(lastRoom) || !phase.equals(lastPhase) || round != lastRound) {
            lastRoom = room;
            lastPhase = phase;
            lastRound = round;
            clear();
        }
    }

    private static void onClientTick(Minecraft mc) {
        ResourceKey<Level> dimension = mc.level == null ? null : mc.level.dimension();
        if (mc.level == null) armed = false;
        if (!java.util.Objects.equals(dimension, lastDimension)) {
            lastDimension = dimension;
            clear();
        }
    }

    static void clear() {
        com.atsuishio.superbwarfare.event.KillMessageHandler.INSTANCE.getQUEUE().clear();
    }

    /** Только тесты: сбросить запомненный матч между кейсами. */
    static void resetForTest() {
        lastRoom = null;
        lastPhase = null;
        lastRound = 0;
        armed = false;
        lastDimension = null;
    }
}
