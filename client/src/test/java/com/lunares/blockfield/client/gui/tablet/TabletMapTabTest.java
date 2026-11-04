package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.lunares.blockfield.client.render.HudRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Wrong-typed zone bounds must read as invalid, like missing ones: strings and booleans are not
 * numbers, so {@link TabletMapTab#zone()} returns null and the subtitle stays empty instead of
 * letting {@code getAsDouble()} throw in render/zoom/pan.
 */
class TabletMapTabTest {
    private static JsonObject bounds() {
        JsonObject map = new JsonObject();
        map.addProperty("minX", -100);
        map.addProperty("maxX", 100);
        map.addProperty("minZ", -200);
        map.addProperty("maxZ", 200);
        return map;
    }

    private static void showMap(JsonObject map) {
        JsonObject ui = new JsonObject();
        ui.add("map", map);
        HudRenderer.updateHUDData(ui.toString());
    }

    @AfterEach
    void resetHud() {
        HudRenderer.updateHUDData("{}");
    }

    @Test
    void stringBoundIsInvalid() {
        JsonObject map = bounds();
        map.addProperty("minX", "oops");
        showMap(map);
        assertNull(TabletMapTab.zone());
        assertEquals("", TabletMapTab.subtitle(map));
    }

    @Test
    void booleanBoundIsInvalid() {
        JsonObject map = bounds();
        map.addProperty("minX", true);
        showMap(map);
        assertNull(TabletMapTab.zone());
        assertEquals("", TabletMapTab.subtitle(map));
    }

    @Test
    void nestedNullBoundIsInvalid() {
        JsonObject map = bounds();
        map.add("minX", JsonNull.INSTANCE);
        showMap(map);
        assertNull(TabletMapTab.zone());
        assertEquals("", TabletMapTab.subtitle(map));
    }
}
