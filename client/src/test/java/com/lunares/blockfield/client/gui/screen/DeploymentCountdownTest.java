package com.lunares.blockfield.client.gui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class DeploymentCountdownTest {
    @Test
    void preparationShowsAutomaticDeploymentEvenWhenPlayerIsNotDead() {
        assertEquals(
                "Подготовка карты…",
                text("{\"mapLoading\":true,\"autoSpawnTimer\":0,\"dead\":true}"));
        assertEquals("Автовысадка через 12 с", text("{\"autoSpawnTimer\":12,\"dead\":false}"));
        assertEquals("Автовысадка через 0 с", text("{\"autoSpawnTimer\":0}"));
    }

    @Test
    void deathCooldownDoesNotPromiseAutomaticDeployment() {
        assertEquals(
                "До возрождения: 7 с",
                text("{\"autoSpawnTimer\":-1,\"dead\":true,\"deathTimer\":7}"));
        assertEquals("Возрождение доступно", text("{\"dead\":true,\"deathTimer\":0}"));
    }

    @Test
    void aliveOrMissingStateHasNoCountdown() {
        assertEquals("", DeploymentCountdown.text(null));
        assertEquals("", text("{}"));
        assertEquals("", text("{\"autoSpawnTimer\":-1,\"dead\":false,\"deathTimer\":10}"));
    }

    private String text(String json) {
        return DeploymentCountdown.text(JsonParser.parseString(json).getAsJsonObject());
    }
}
