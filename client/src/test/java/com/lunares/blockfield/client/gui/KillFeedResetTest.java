package com.lunares.blockfield.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.atsuishio.superbwarfare.event.KillMessageHandler;
import com.atsuishio.superbwarfare.tools.LivingKillRecord;
import com.google.gson.JsonObject;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Лента SBW переживала смену матча: чистим при смене комнаты, фазы и раунда. */
class KillFeedResetTest {
    private static Queue<LivingKillRecord> queue() {
        return KillMessageHandler.INSTANCE.getQUEUE();
    }

    private static JsonObject presence(String id, String phase, int round) {
        JsonObject presence = new JsonObject();
        presence.addProperty("id", id);
        presence.addProperty("phase", phase);
        presence.addProperty("round", round);
        return presence;
    }

    @BeforeEach
    void reset() {
        KillFeedReset.resetForTest();
        queue().clear();
    }

    @Test
    void keepsFeedWithinSameMatch() {
        queue().offer(mock(LivingKillRecord.class));
        KillFeedReset.onPresence(presence("roomA", "GAME", 1));
        KillFeedReset.onPresence(presence("roomA", "GAME", 1));
        assertEquals(1, queue().size());
    }

    @Test
    void clearsOnRoomChange() {
        queue().offer(mock(LivingKillRecord.class));
        KillFeedReset.onPresence(presence("roomA", "GAME", 1));
        KillFeedReset.onPresence(presence("roomB", "GAME", 1));
        assertTrue(queue().isEmpty());
    }

    @Test
    void clearsOnPhaseChange() {
        queue().offer(mock(LivingKillRecord.class));
        KillFeedReset.onPresence(presence("roomA", "GAME", 1));
        KillFeedReset.onPresence(presence("roomA", "MATCH_END", 1));
        assertTrue(queue().isEmpty());
    }

    @Test
    void clearsOnRoundChange() {
        queue().offer(mock(LivingKillRecord.class));
        KillFeedReset.onPresence(presence("roomA", "GAME", 1));
        KillFeedReset.onPresence(presence("roomA", "GAME", 2));
        assertTrue(queue().isEmpty());
    }
}
