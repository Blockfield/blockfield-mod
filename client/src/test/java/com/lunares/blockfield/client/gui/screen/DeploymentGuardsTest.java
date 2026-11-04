package com.lunares.blockfield.client.gui.screen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeploymentGuardsTest {
    @Test
    void spawnLivePointDataToleratesMalformedPts() {
        var screen = new SpawnSelectionScreen("{}", "arena");
        screen.updateModel("{\"pts\":{}}");
        assertNull(screen.getLivePointData("A"));
        screen.updateModel("{\"pts\":[1]}");
        assertNull(screen.getLivePointData("A"));
        screen.updateModel("{\"pts\":[{\"n\":\"A\",\"o\":\"RED\",\"p\":100}]}");
        assertNotNull(screen.getLivePointData("A"));
    }

    @Test
    void classLiveCountsTolerateMalformedClasses() {
        var screen = new ClassSelectionScreen("{}");
        screen.updateModel("{\"classes\":[]}");
        // Not an object: live counts are unreadable, so the static fallback stands in.
        assertEquals(3, screen.getLiveCount("assault", 3));
        assertTrue(screen.getLivePlayers("assault").isEmpty());
        screen.updateModel("{\"classes\":{\"assault\":5}}");
        assertEquals(0, screen.getLiveCount("assault", 3));
        assertTrue(screen.getLivePlayers("assault").isEmpty());
        screen.updateModel("{\"classes\":{\"assault\":[\"Bob\"]}}");
        assertEquals(1, screen.getLiveCount("assault", 3));
        assertEquals(List.of("Bob"), screen.getLivePlayers("assault"));
    }

    @Test
    void paidClassesTolerateNonObjectEntries() {
        assertFalse(
                ClassSelectionScreen.hasPaidClasses(
                        JsonParser.parseString("{\"a\":1}").getAsJsonObject()));
    }
}
