package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.network.packet.MapPingS2CPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MapPingsTest {
    @BeforeEach
    void clear() {
        MapPings.clear();
    }

    @Test
    void dropsNonFiniteCoordinates() {
        MapPings.add(new MapPingS2CPacket("a", Double.NaN, 64, 0, 10));
        MapPings.add(new MapPingS2CPacket("b", 1, 64, Double.POSITIVE_INFINITY, 10));
        assertTrue(MapPings.markers().isEmpty());
    }

    @Test
    void capsListAndDropsOldest() {
        // The 64 literal mirrors MAX_PINGS (a named constant would not compile pre-fix).
        for (int i = 0; i < 64 + 6; i++) MapPings.add(new MapPingS2CPacket("p" + i, i, 64, 0, 10));
        var markers = MapPings.markers();
        assertEquals(64, markers.size());
        assertEquals("p6", markers.get(0).name());
    }

    @Test
    void clampsNonPositiveLifetime() {
        MapPings.add(new MapPingS2CPacket("neg", 1, 64, 0, -30));
        assertEquals(1, MapPings.markers().size());
    }
}
