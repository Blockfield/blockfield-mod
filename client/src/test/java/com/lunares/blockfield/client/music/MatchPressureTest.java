package com.lunares.blockfield.client.music;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MatchPressureTest {
    private final MusicConfig.Pressure t = new MusicConfig.Pressure();

    @Test
    void earlyAndEvenMatchIsNotCritical() {
        assertEquals(MusicState.AMBIENT, MatchPressure.tickets(250, 260, 300, t).state());
        assertEquals(
                MusicState.AMBIENT,
                MatchPressure.tickets(70, 80, 300, t).state(),
                "both low but not decisive");
    }

    @Test
    void losingBadlyIsCritical() {
        assertEquals(MusicState.CRITICAL, MatchPressure.tickets(60, 200, 300, t).state());
    }

    @Test
    void closeFinaleIsFinalPushAndStompIsNot() {
        assertEquals(MusicState.FINAL_PUSH, MatchPressure.tickets(25, 40, 300, t).state());
        assertEquals(
                MusicState.AMBIENT,
                MatchPressure.tickets(250, 20, 300, t).state(),
                "winning by a mile is not a final push");
        assertEquals(MusicState.CRITICAL, MatchPressure.tickets(20, 250, 300, t).state());
    }

    @Test
    void finishedOrUnknownMatchHasNoPressure() {
        assertEquals(MusicState.AMBIENT, MatchPressure.tickets(0, 100, 300, t).state());
        assertEquals(MusicState.AMBIENT, MatchPressure.tickets(10, 12, 0, t).state());
    }

    @Test
    void teamDeathmatchUsesFragsAndTime() {
        assertEquals(MusicState.AMBIENT, MatchPressure.frags(10, 12, 50, 900, t).state());
        assertEquals(MusicState.FINAL_PUSH, MatchPressure.frags(47, 48, 50, 400, t).state());
        assertEquals(MusicState.FINAL_PUSH, MatchPressure.frags(20, 21, 50, 45, t).state());
        assertEquals(MusicState.CRITICAL, MatchPressure.frags(30, 40, 50, 400, t).state());
    }
}
