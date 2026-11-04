package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class HudRendererCreditsTest {
    @Test
    void displaysServerBalanceIncludingZeroAndHidesItAfterLeaving() {
        assertEquals(
                "1 234 БК",
                HudRenderer.creditsText(
                        JsonParser.parseString("{\"battleCredits\":1234.9}").getAsJsonObject()));
        assertEquals(
                "0 БК",
                HudRenderer.creditsText(
                        JsonParser.parseString("{\"battleCredits\":0}").getAsJsonObject()));
        assertEquals(
                "",
                HudRenderer.creditsText(
                        JsonParser.parseString("{\"showHud\":false}").getAsJsonObject()));
    }
}
