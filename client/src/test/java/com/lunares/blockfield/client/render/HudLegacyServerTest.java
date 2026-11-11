package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * u-ui48d/b: the new client against the released (old) server. The old DM payload carries only the
 * legacy keys, so every new top-bar cell must resolve to null (no top bar, the vanilla bottom
 * actionbar stays the score source) instead of crashing or drawing a wrong bar.
 */
class HudLegacyServerTest {
    private static JsonObject legacyDmPayload() {
        var ui = new JsonObject();
        ui.addProperty("showHud", true);
        ui.addProperty("ffa", true);
        ui.addProperty("dead", false);
        ui.addProperty("deathTimer", 0);
        ui.addProperty("tabActive", true);
        return ui;
    }

    @Test
    void legacyDmPayloadDrawsNoTopBar() {
        var ui = legacyDmPayload();
        assertNull(HudRenderer.ffaScore(ui));
        assertNull(HudRenderer.ffaLeader(ui));
        assertNull(HudRenderer.matchTime(ui));
        assertEquals(0, HudRenderer.protectionSecs(ui));
    }

    @Test
    void legacyTeamPayloadKeepsTicketsAndTime() {
        var ui = new JsonObject();
        ui.addProperty("showHud", true);
        ui.addProperty("rT", 100);
        ui.addProperty("bT", 87);
        ui.addProperty("timeLeft", 472);
        ui.addProperty("killLimit", 30);
        assertEquals("7:52", HudRenderer.matchTime(ui));
        assertNull(HudRenderer.ffaScore(ui));
        assertNull(HudRenderer.ffaLeader(ui));
    }
}
