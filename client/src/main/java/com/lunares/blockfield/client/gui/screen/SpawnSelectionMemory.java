package com.lunares.blockfield.client.gui.screen;

import java.util.Objects;

final class SpawnSelectionMemory {
    private String mapId;
    private String spawnId = "BASE";

    String forMap(String mapId) {
        // Visiting another map ends the previous choice even when no new point is selected.
        if (!Objects.equals(this.mapId, mapId)) {
            this.mapId = mapId;
            spawnId = "BASE";
        }
        return spawnId;
    }

    void select(String mapId, String spawnId) {
        forMap(mapId);
        this.spawnId = spawnId;
    }
}
