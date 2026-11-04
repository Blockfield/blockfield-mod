package com.lunares.blockfield.client.gui.screen;

import com.google.gson.JsonObject;

final class DeploymentCountdown {
    private DeploymentCountdown() {}

    static String text(JsonObject data) {
        if (data == null) return "";
        if (data.has("mapLoading") && data.get("mapLoading").getAsBoolean())
            return "Подготовка карты…";
        if (data.has("autoSpawnTimer") && data.get("autoSpawnTimer").getAsInt() >= 0) {
            return "Автовысадка через " + data.get("autoSpawnTimer").getAsInt() + " с";
        }
        if (data.has("dead") && data.get("dead").getAsBoolean()) {
            int seconds = data.has("deathTimer") ? data.get("deathTimer").getAsInt() : 0;
            return seconds > 0 ? "До возрождения: " + seconds + " с" : "Возрождение доступно";
        }
        return "";
    }
}
