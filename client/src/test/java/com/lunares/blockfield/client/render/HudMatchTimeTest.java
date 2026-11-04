package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class HudMatchTimeTest {
    @Test
    void noLimitMeansNoTimer() {
        assertNull(HudRenderer.matchTime(null));
        assertNull(HudRenderer.matchTime(new JsonObject()));
    }

    @Test
    void formatsMinutesAndPaddedSeconds() {
        JsonObject ui = new JsonObject();
        ui.addProperty("timeLeft", 582);
        assertEquals("9:42", HudRenderer.matchTime(ui));
        ui.addProperty("timeLeft", 5);
        assertEquals("0:05", HudRenderer.matchTime(ui));
        ui.addProperty("timeLeft", -3);
        assertEquals("0:00", HudRenderer.matchTime(ui));
    }
}
