package com.lunares.blockfield.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpawnPreloadTest {
    @Test
    void waitsForChunksThenSettles() {
        SpawnPreload preload = new SpawnPreload();
        assertFalse(preload.ready(true, false));
        for (int i = 0; i < SpawnPreload.SETTLE_TICKS; i++) assertFalse(preload.ready(true, true));
        assertTrue(preload.ready(true, true));
    }

    @Test
    void capReleasesTheScreenButNeverBeforeThePlayerSectionIsBuilt() {
        SpawnPreload stuck = new SpawnPreload();
        for (int i = 1; i < SpawnPreload.MAX_TICKS; i++) assertFalse(stuck.ready(true, false));
        assertTrue(stuck.ready(true, false));

        SpawnPreload noSection = new SpawnPreload();
        for (int i = 0; i <= SpawnPreload.MAX_TICKS; i++)
            assertFalse(noSection.ready(false, false));
    }
}
