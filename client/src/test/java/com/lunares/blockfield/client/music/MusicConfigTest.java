package com.lunares.blockfield.client.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class MusicConfigTest {
    @Test
    void missingTracksAndUnknownStatesAreDroppedWithWarnings() {
        MusicConfig config =
                MusicConfig.parse(
                        new StringReader(
                                """
                {"tracks": {"a": {"length": 120.5}},
                 "states": {"combat": {"tracks": ["a", "ghost"], "first": "ghost"}, "banana": {}},
                 "stingers": {"captured": {"track": "nope"}}}"""));
        List<String> problems = config.validate();
        assertEquals(4, problems.size(), problems.toString());
        assertEquals(List.of("a"), config.state(MusicState.COMBAT).tracks);
        assertNull(config.state(MusicState.COMBAT).first);
        assertEquals(120.5, config.length("a"));
        assertEquals(0, config.stingers.size());
    }

    @Test
    void defaultsSurviveMissingSections() {
        MusicConfig config =
                MusicConfig.parse(
                        new StringReader("{\"states\": {\"combat\": {\"volume\": 0.8}}}"));
        config.validate();
        assertEquals(0.8f, config.state(MusicState.COMBAT).volume);
        assertEquals(4, config.state(MusicState.COMBAT).fadeOut);
        assertEquals(6, config.combat.combat);
        assertEquals(MusicState.SILENT.id(), "silent");
        assertEquals(0, config.state(MusicState.LOBBY).tracks.size(), "absent state is silent");
    }

    @Test
    void deathmatchTracksReplaceObjectiveTracks() {
        MusicConfig config =
                MusicConfig.parse(
                        new StringReader(
                                """
                {"tracks": {"c": {}, "dm": {}}, "states": {"combat": {"tracks": ["c"], "tracksWithoutObjectives": ["dm"]}}}"""));
        config.validate();
        assertEquals(List.of("c"), config.state(MusicState.COMBAT).tracks(true));
        assertEquals(List.of("dm"), config.state(MusicState.COMBAT).tracks(false));
    }

    @Test
    void workshopFallsBackToTheHubUntilItHasItsOwnSet() {
        MusicConfig hubOnly =
                MusicConfig.parse(new StringReader("{\"states\": {\"lobby\": {\"volume\": 0.3}}}"));
        assertEquals(0.3, hubOnly.state(MusicState.WORKSHOP).volume, 1e-6);
        MusicConfig own =
                MusicConfig.parse(
                        new StringReader(
                                "{\"states\": {\"lobby\": {\"volume\": 0.3}, \"workshop\": {\"volume\": 0.5}}}"));
        assertEquals(0.5, own.state(MusicState.WORKSHOP).volume, 1e-6);
    }
}
