package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class MatchResultTest {

    private static MatchResult result(String winner, String me) {
        StringBuilder players = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) players.append(',');
            String team = i % 2 == 0 ? "BlueTeam" : "RedTeam";
            players.append("{\"name\":\"p")
                    .append(i)
                    .append("\",\"uuid\":\"u")
                    .append(i)
                    .append("\",\"team\":\"")
                    .append(team)
                    .append("\",\"score\":")
                    .append(1000 - i * 100)
                    .append('}');
        }
        return MatchResult.parse(
                JsonParser.parseString(
                                "{\"winner\":\""
                                        + winner
                                        + "\",\"scoreKey\":\"tickets\","
                                        + "\"red\":0,\"blue\":214,\"duration\":1122,\"players\":["
                                        + players
                                        + "]}")
                        .getAsJsonObject(),
                me);
    }

    @Test
    void droneStatsAreReadAndZeroWhenAbsent() {
        MatchResult r =
                MatchResult.parse(
                        JsonParser.parseString(
                                        "{\"winner\":\"BlueTeam\",\"players\":["
                                                + "{\"name\":\"op\",\"uuid\":\"u0\",\"team\":\"BlueTeam\",\"kills\":3,\"cls\":\"drone\",\"droneKills\":2,\"droneDamage\":57},"
                                                + "{\"name\":\"rifle\",\"uuid\":\"u1\",\"team\":\"RedTeam\",\"kills\":1}]}")
                                .getAsJsonObject(),
                        "u0");
        assertEquals(2, r.me().droneKills());
        assertEquals(57, r.me().droneDamage());
        assertEquals(0, r.players().get(1).droneKills());
        assertEquals(0, r.players().get(1).droneDamage());
    }

    @Test
    void droneDamageFitsANarrowCell() {
        assertEquals("950", MatchResult.compact(950));
        assertEquals("1.3k", MatchResult.compact(1347));
        assertEquals("12k", MatchResult.compact(12_400));
    }

    @Test
    void outcomeFollowsOwnTeam() {
        assertEquals(MatchResult.Outcome.VICTORY, result("BlueTeam", "u2").outcome());
        assertEquals(MatchResult.Outcome.DEFEAT, result("BlueTeam", "u3").outcome());
        assertEquals(MatchResult.Outcome.DRAW, result("DRAW", "u3").outcome());
        assertEquals(MatchResult.Outcome.OVER, result("BlueTeam", "nobody").outcome());
    }

    @Test
    void rosterIsOwnTeamAndRankIsOverall() {
        MatchResult r = result("BlueTeam", "u6");
        assertEquals(4, r.roster().size());
        assertTrue(r.roster().stream().allMatch(p -> p.team().equals("BlueTeam")));
        assertEquals("p0", r.best().name());
        assertEquals(7, r.rank());
    }

    @Test
    void freeForAllWinnerIsByUuid() {
        MatchResult r =
                MatchResult.parse(
                        JsonParser.parseString(
                                        "{\"winner\":\"U1\",\"scoreKey\":\"frags\",\"players\":["
                                                + "{\"name\":\"a\",\"uuid\":\"u1\",\"team\":\"\"},{\"name\":\"b\",\"uuid\":\"u2\",\"team\":\"\"}]}")
                                .getAsJsonObject(),
                        "u1");
        assertFalse(r.teams());
        assertTrue(r.frags());
        assertEquals(MatchResult.Outcome.VICTORY, r.outcome());
        assertEquals(2, r.roster().size());
    }

    @Test
    void clockPadsSeconds() {
        assertEquals("18:42", MatchResult.clock(1122));
        assertEquals("0:05", MatchResult.clock(5));
    }
}
