package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class MapPlayerMotionTest {
    private static JsonObject map(String dimension, double x, boolean enemy) {
        return JsonParser.parseString(
                        """
                {"id":"arena","dimension":"%s","teammates":%s,"revealedEnemies":%s}
                """
                                .formatted(
                                        dimension,
                                        enemy ? "[]" : player(x),
                                        enemy ? player(x) : "[]"))
                .getAsJsonObject();
    }

    private static String player(double x) {
        return "[{\"uuid\":\"00000000-0000-0000-0000-000000000001\",\"x\":" + x + ",\"z\":20}]";
    }

    @Test
    void motionIsSmoothBetweenUpdatesAndDoesNotExtrapolate() {
        var motion = new MapPlayerMotion();
        var first = map("blockfield:arena_1", 0, false);
        var next = map("blockfield:arena_1", 10, false);
        motion.update(first, 0);
        motion.update(next, 200);
        assertEquals(0, motion.positions(next, 200).getFirst().x());
        assertEquals(2.5, motion.positions(next, 250).getFirst().x());
        assertEquals(5, motion.positions(next, 300).getFirst().x());
        assertEquals(10, motion.positions(next, 400).getFirst().x());
        assertEquals(10, motion.positions(next, 600).getFirst().x());
        // A late packet begins at the displayed position instead of jumping back.
        motion.update(map("blockfield:arena_1", 20, false), 600);
        assertEquals(15, motion.positions(next, 700).getFirst().x());
    }

    @Test
    void removalTeleportAndRoundChangeNeverLeaveGhostMarkers() {
        var motion = new MapPlayerMotion();
        var first = map("blockfield:arena_1", 0, false);
        motion.update(first, 0);
        var teleported = map("blockfield:arena_1", 500, false);
        motion.update(teleported, 200);
        assertEquals(500, motion.positions(teleported, 200).getFirst().x());
        var enemy = map("blockfield:arena_1", 510, true);
        motion.update(enemy, 400);
        assertEquals(510, motion.positions(enemy, 400).getFirst().x());
        var empty =
                JsonParser.parseString(
                                """
                {"id":"arena","dimension":"blockfield:arena_1","teammates":[],"revealedEnemies":[]}
                """)
                        .getAsJsonObject();
        motion.update(empty, 600);
        assertTrue(motion.positions(first, 600).isEmpty());
        var nextRound = map("blockfield:arena_2", 30, false);
        motion.update(nextRound, 800);
        assertEquals(30, motion.positions(nextRound, 800).getFirst().x());
        assertEquals(
                0,
                motion.positions(first, 800).getFirst().x(),
                "Another dimension uses its own UI snapshot");
        motion.clear();
        assertEquals(0, motion.positions(first, 900).getFirst().x());
    }

    @Test
    void malformedServerJsonNeverThrows() {
        var motion = new MapPlayerMotion();
        // The audit repro: a teammate entry without coordinates or dimension.
        var noCoords =
                JsonParser.parseString(
                                """
                {"id":"arena","dimension":"blockfield:arena_1","teammates":[{"x":1}],"revealedEnemies":[]}
                """)
                        .getAsJsonObject();
        assertDoesNotThrow(() -> motion.update(noCoords, 0));
        assertTrue(motion.positions(noCoords, 0).isEmpty());
        // Missing dimension/id, primitive entries, malformed uuids.
        var malformed =
                JsonParser.parseString(
                                """
                {"teammates":[1,"s",{"z":5},{"uuid":"not-a-uuid","x":1,"z":2},{"x":null,"z":2}],"revealedEnemies":{}}
                """)
                        .getAsJsonObject();
        assertDoesNotThrow(() -> motion.update(malformed, 100));
        assertTrue(motion.positions(malformed, 100).isEmpty());
        assertDoesNotThrow(() -> motion.update(new JsonObject(), 200));
    }

    @Test
    void oversizedRosterIsCapped() {
        // Distinct uuids: entries share the players map by id, so identical entries collapse.
        // The 256 literal mirrors MAX_ENTRIES (a named constant would not compile pre-fix).
        var players = new StringBuilder("[");
        for (int i = 0; i < 256 + 44; i++) {
            if (i > 0) players.append(',');
            players.append("{\"uuid\":\"00000000-0000-0000-0000-")
                    .append(String.format("%012d", i))
                    .append("\",\"x\":")
                    .append(i)
                    .append(",\"z\":0}");
        }
        var map =
                JsonParser.parseString(
                                """
                {"id":"arena","dimension":"blockfield:arena_1","teammates":%s,"revealedEnemies":[]}
                """
                                        .formatted(players + "]"))
                        .getAsJsonObject();
        var motion = new MapPlayerMotion();
        motion.update(map, 0);
        // The 256 literal mirrors MAX_ENTRIES (a named constant would not compile pre-fix).
        assertEquals(256, motion.positions(map, 0).size());
    }
}
