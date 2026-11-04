package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunares.blockfield.network.packet.WorkshopAiStateS2CPacket;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Client text-layer guards for the workshop redesign (prep part 1): key parity and stages. */
class WorkshopAiLangTest {
    private static JsonObject table(String language) throws Exception {
        try (var stream =
                WorkshopAiLangTest.class.getResourceAsStream(
                        "/assets/blockfield/lang/" + language + ".json")) {
            assertNotNull(stream, language);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    @Test
    void clientTablesHaveMatchingKeys() throws Exception {
        var ru = table("ru_ru");
        var en = table("en_us");
        assertEquals(ru.keySet(), en.keySet());
        // phase.none is the intentionally empty phase label.
        var mayBeBlank = Set.of("blockfield.workshop.ai.phase.none");
        for (var key : ru.keySet()) {
            if (!mayBeBlank.contains(key)) {
                assertFalse(ru.get(key).getAsString().isBlank(), key);
                assertFalse(en.get(key).getAsString().isBlank(), key);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyStageHasATranslationInBothLanguages() throws Exception {
        var stages = WorkshopAiStateS2CPacket.class.getDeclaredField("STAGES");
        stages.setAccessible(true);
        var names = new HashSet<>((Set<String>) stages.get(null));
        assertFalse(names.isEmpty());
        for (String language : new String[] {"ru_ru", "en_us"}) {
            var json = table(language);
            for (String stage : names) {
                String key = "blockfield.workshop.ai.stage." + stage;
                assertTrue(json.has(key), language + " " + key);
                assertFalse(json.get(key).getAsString().isBlank(), language + " " + key);
            }
        }
    }
}
