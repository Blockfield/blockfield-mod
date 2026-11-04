package com.lunares.blockfield.client.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lunares.blockfield.client.music.MusicContext.Scene;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class MusicBrainTest {
    static final MusicConfig CONFIG =
            MusicConfig.parse(
                    new StringReader(
                            """
            {"tracks": {"t": {}},
             "states": {
               "tactical":   {"enter": 2, "exit": 15, "hold": 20},
               "deployment": {"max": 45},
               "combat":     {"enter": 1.5, "exit": 10, "hold": 20},
               "vehicle":    {"enter": 1.5, "exit": 10, "hold": 20},
               "contested":  {"enter": 2, "exit": 8, "hold": 15},
               "critical":   {"enter": 3, "exit": 20, "hold": 30},
               "final_push": {"enter": 1, "exit": 30, "hold": 60},
               "victory":    {"max": 45},
               "defeat":     {"max": 45},
               "post_match": {"max": 150}
             }}"""));

    private final MusicBrain brain = new MusicBrain();
    private long now = 1_000_000;

    private static MusicContext game(double combat) {
        return new MusicContext(
                Scene.GAME, true, true, false, false, false, 0, combat, MatchPressure.Result.NONE);
    }

    private MusicState run(MusicContext context, double seconds) {
        MusicState state = null;
        for (int i = 0; i < seconds * 20; i++) {
            state = brain.update(context, CONFIG, now);
            now += 50;
        }
        return state;
    }

    /** Боец высадился и отыграл высадку: дальше обычный бой. */
    private void deployed() {
        run(game(0), 50);
        assertEquals(MusicState.AMBIENT, brain.state());
    }

    @Test
    void firstDeploymentPlaysDeploymentThenCalmsDown() {
        assertEquals(MusicState.LOADING, run(MusicContext.scene(Scene.PREPARING), 1));
        assertEquals(MusicState.DEPLOYMENT, run(game(0), 1));
        assertEquals(MusicState.AMBIENT, run(game(0), 50));
    }

    @Test
    void combatDuringDeploymentDoesNotBringDeploymentBack() {
        run(MusicContext.scene(Scene.PREPARING), 1);
        assertEquals(MusicState.DEPLOYMENT, run(game(0), 1));
        assertEquals(MusicState.COMBAT, run(game(8), 3));
        MusicState after = run(game(0), 25);
        assertEquals(
                MusicState.AMBIENT, after, "deployment latch (45 s) must not return after combat");
    }

    @Test
    void notYetDeployedPlayerKeepsBriefingMusic() {
        MusicContext choosing =
                new MusicContext(
                        Scene.GAME,
                        false,
                        true,
                        false,
                        false,
                        false,
                        0,
                        0,
                        MatchPressure.Result.NONE);
        assertEquals(MusicState.LOADING, run(choosing, 5));
    }

    @Test
    void singleShotSpikeDoesNotStartCombat() {
        deployed();
        CombatMeter meter = new CombatMeter(4);
        meter.shot(7, 0.6, 1.5, now);
        for (int i = 0; i < 40; i++) {
            brain.update(game(meter.value(now)), CONFIG, now);
            now += 50;
        }
        assertTrue(
                brain.state().compareTo(MusicState.COMBAT) < 0, "one shot gave " + brain.state());
    }

    @Test
    void sustainedCombatEscalatesAfterEnterDelay() {
        deployed();
        assertEquals(MusicState.AMBIENT, run(game(8), 1), "enter delay of combat not over yet");
        assertEquals(MusicState.COMBAT, run(game(8), 1));
    }

    @Test
    void combatDoesNotDropInstantlyWhenFightingStops() {
        deployed();
        run(game(8), 25);
        assertEquals(MusicState.COMBAT, brain.state());
        assertEquals(MusicState.COMBAT, run(game(0), 9), "exit delay is 10 s");
        assertEquals(MusicState.AMBIENT, run(game(0), 2));
    }

    @Test
    void combatHoldsItsMinimumDurationEvenIfFightStopsAtOnce() {
        deployed();
        run(game(8), 3);
        assertEquals(MusicState.COMBAT, brain.state());
        assertEquals(MusicState.COMBAT, run(game(0), 15), "hold is 20 s since entering combat");
        assertEquals(MusicState.AMBIENT, run(game(0), 5));
    }

    @Test
    void walkingInAndOutOfTheObjectiveDoesNotFlapMusic() {
        deployed();
        MusicContext inside =
                new MusicContext(
                        Scene.GAME, true, true, false, true, true, 2, 8, MatchPressure.Result.NONE);
        MusicContext outside =
                new MusicContext(
                        Scene.GAME,
                        true,
                        true,
                        false,
                        false,
                        true,
                        2,
                        8,
                        MatchPressure.Result.NONE);
        run(inside, 3);
        assertEquals(MusicState.CONTESTED, brain.state());
        int switches = 0;
        MusicState seen = brain.state();
        for (int i = 0; i < 20; i++) {
            run(i % 2 == 0 ? outside : inside, 1.5);
            if (brain.state() != seen) switches++;
            seen = brain.state();
        }
        assertEquals(0, switches);
    }

    @Test
    void contestedNeedsActualPresenceOfTheFight() {
        deployed();
        MusicContext aloneOnPoint =
                new MusicContext(
                        Scene.GAME, true, true, false, true, true, 0, 0, MatchPressure.Result.NONE);
        assertEquals(MusicState.TACTICAL, run(aloneOnPoint, 5));
    }

    @Test
    void vehicleMusicOnlyWithCombat() {
        deployed();
        MusicContext driving =
                new MusicContext(
                        Scene.GAME,
                        true,
                        true,
                        true,
                        false,
                        false,
                        0,
                        0,
                        MatchPressure.Result.NONE);
        assertEquals(MusicState.AMBIENT, run(driving, 5));
        MusicContext assault =
                new MusicContext(
                        Scene.GAME,
                        true,
                        true,
                        true,
                        false,
                        false,
                        1,
                        9,
                        MatchPressure.Result.NONE);
        assertEquals(MusicState.VEHICLE, run(assault, 3));
    }

    @Test
    void finalPushOverridesEverythingBelow() {
        deployed();
        run(
                new MusicContext(
                        Scene.GAME, true, true, false, true, true, 3, 9, MatchPressure.Result.NONE),
                5);
        assertEquals(MusicState.CONTESTED, brain.state());
        MatchPressure.Result finale = new MatchPressure.Result(MusicState.FINAL_PUSH, "test");
        assertEquals(
                MusicState.FINAL_PUSH,
                run(new MusicContext(Scene.GAME, true, true, false, true, true, 3, 9, finale), 2));
    }

    @Test
    void deadPlayerDoesNotEscalate() {
        deployed();
        MusicContext dead =
                new MusicContext(
                        Scene.GAME,
                        false,
                        true,
                        false,
                        false,
                        false,
                        0,
                        12,
                        MatchPressure.Result.NONE);
        assertEquals(MusicState.AMBIENT, run(dead, 5));
    }

    @Test
    void victoryOverridesGameplayThenPostMatch() {
        deployed();
        run(game(9), 5);
        brain.matchResult("win", now);
        assertEquals(
                MusicState.VICTORY,
                run(game(9), 1),
                "UI still says GAME for a moment after the result");
        assertEquals(MusicState.VICTORY, run(MusicContext.scene(Scene.MATCH_END), 30));
        assertEquals(MusicState.POST_MATCH, run(MusicContext.scene(Scene.HUB), 20));
        assertEquals(
                MusicState.LOADING,
                run(MusicContext.scene(Scene.PREPARING), 1),
                "next round clears the result");
    }

    @Test
    void defeatAndDraw() {
        deployed();
        brain.matchResult("loss", now);
        assertEquals(MusicState.DEFEAT, run(MusicContext.scene(Scene.HUB), 1));
        brain.matchResult("draw", now);
        assertEquals(MusicState.POST_MATCH, run(MusicContext.scene(Scene.HUB), 1));
    }

    @Test
    void leavingToTitleForgetsTheResult() {
        brain.matchResult("win", now);
        assertEquals(MusicState.MENU, run(MusicContext.scene(Scene.MENU), 1));
    }

    @Test
    void scenesSwitchImmediately() {
        assertEquals(MusicState.MENU, run(MusicContext.scene(Scene.MENU), 0.05));
        assertEquals(MusicState.LOBBY, run(MusicContext.scene(Scene.HUB), 0.05));
        assertEquals(MusicState.SILENT, run(MusicContext.scene(Scene.RANGE), 0.05));
        assertEquals(MusicState.WORKSHOP, run(MusicContext.scene(Scene.WORKSHOP), 0.05));
        assertEquals(MusicState.LOADING, run(MusicContext.scene(Scene.VOTING), 0.05));
    }

    @Test
    void forcedStateWinsUntilAuto() {
        brain.force(MusicState.FINAL_PUSH);
        assertEquals(MusicState.FINAL_PUSH, run(MusicContext.scene(Scene.HUB), 0.1));
        brain.force(null);
        assertEquals(MusicState.LOBBY, run(MusicContext.scene(Scene.HUB), 0.1));
    }
}
