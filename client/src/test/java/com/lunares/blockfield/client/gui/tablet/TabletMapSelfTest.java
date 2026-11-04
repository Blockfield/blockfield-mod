package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class TabletMapSelfTest {
    private static final String OVERWORLD = "minecraft:overworld";

    private static JsonObject map(String dimension) {
        JsonObject map =
                JsonParser.parseString(
                                "{\"id\":\"arena\",\"minX\":0,\"maxX\":100,\"minZ\":0,\"maxZ\":100}")
                        .getAsJsonObject();
        if (dimension != null) map.addProperty("dimension", dimension);
        return map;
    }

    @Test
    void aliveShowsOwnMarkerInBattleDimension() {
        assertTrue(TabletMapTab.showSelf(map(OVERWORLD), true, OVERWORLD));
    }

    @Test
    void deadHidesOwnMarkerInSameDimension() {
        // Посмертное лобби держит игрока в том же измерении: одной проверки измерения мало.
        assertFalse(TabletMapTab.showSelf(map(OVERWORLD), false, OVERWORLD));
    }

    @Test
    void otherDimensionHidesOwnMarkerEvenWhenAlive() {
        assertFalse(TabletMapTab.showSelf(map(OVERWORLD), true, "blockfield:arena_1"));
    }

    @Test
    void legacyMapWithoutDimensionShowsOwnMarkerWhenAlive() {
        assertTrue(TabletMapTab.showSelf(map(null), true, OVERWORLD));
        assertFalse(TabletMapTab.showSelf(map(null), false, OVERWORLD));
    }

    @Test
    void noMapOrLevelNeverShowsOwnMarker() {
        assertFalse(TabletMapTab.showSelf(null, true, OVERWORLD));
        assertFalse(TabletMapTab.showSelf(map(OVERWORLD), true, null));
    }
}
