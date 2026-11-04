package com.lunares.blockfield.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockFieldClientMatchResultTest {
    @Test
    void routesBothServerPayloadsAndIgnoresInvalidTypes() {
        List<JsonObject> tablet = new ArrayList<>();
        List<String> music = new ArrayList<>();
        JsonObject result =
                JsonParser.parseString("{\"winner\":\"RedTeam\",\"players\":[]}").getAsJsonObject();
        BlockFieldClient.dispatchMatchResult(result, tablet::add, music::add);
        for (String outcome : List.of("win", "loss", "draw")) {
            JsonObject packet = new JsonObject();
            packet.addProperty("matchResult", outcome);
            BlockFieldClient.dispatchMatchResult(
                    packet.get("matchResult"), tablet::add, music::add);
        }
        for (String invalid : List.of("null", "[]", "true", "1", "\"unknown\"")) {
            BlockFieldClient.dispatchMatchResult(
                    JsonParser.parseString(invalid), tablet::add, music::add);
        }
        BlockFieldClient.dispatchMatchResult(null, tablet::add, music::add);
        assertEquals(List.of(result), tablet);
        assertEquals(List.of("win", "loss", "draw"), music);
    }
}
