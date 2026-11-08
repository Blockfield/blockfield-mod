package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FirstShotDrawGuardTest {

    // Monotonic ns clock, like the callers pass.
    private static final long DRAW_MS = 700;
    private static final long BOUND_NS = DRAW_MS * 1_000_000L + FirstShotDrawGuard.SLACK_NS;

    @BeforeEach
    void reset() {
        FirstShotDrawGuard.reset();
    }

    @Test
    void idleNeverBlocks() {
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(1_000_000_000L, 0));
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_000_000_000L, 0, false));
    }

    @Test
    void holdsUntilServerDrawObservedThenReleases() {
        FirstShotDrawGuard.onDrawToGun(10_000_000_000L, DRAW_MS, "gun");
        // Server draw not seen yet: hold, do not predict into a refusal.
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(10_100_000_000L, 0));
        // Server draw running: keep holding (plain TaCZ gating would refuse anyway).
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(10_200_000_000L, 350));
        // Draw finished: shootable again.
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(11_000_000_000L, 0));
        // stays released afterwards
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(11_050_000_000L, 0));
    }

    @Test
    void coldRaceHoldIsWhatFailedBefore() {
        // 250 ms TaCZ lock timeout passed with synced still 0: fail-before would allow the shot.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(300_000_000L, 0));
        // Server draw finally arrives late: keep holding instead of predicting into a refusal.
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(400_000_000L, 600));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(1_500_000_000L, 0));
    }

    @Test
    void neverHoldsPastTheBound() {
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        // shortcut: bound (draw + slack) exceeded, server draw never observed.
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(BOUND_NS, 0));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(5_000_000_000L, 0));
    }

    @Test
    void redrawRearms() {
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 300));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(900_000_000L, 0));
        // Second draw (deploy double draw): hold again until its server draw is seen.
        FirstShotDrawGuard.onDrawToGun(950_000_000L, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(1_000_000_000L, 0));
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(1_100_000_000L, 200));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(2_000_000_000L, 0));
    }

    @Test
    void staleCooldownAfterRearmHoldsUntilDrawEnds() {
        // Rapid A->B->A switch: the previous draw's server cooldown is still running when the new
        // draw arms, so the hold stays up. Plain TaCZ gating would refuse anyway; when the draw
        // ends the guard releases exactly like the unmodded game, firing nothing by itself.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        FirstShotDrawGuard.onDrawToGun(100_000_000L, DRAW_MS, "otherGun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(150_000_000L, 400));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(1_500_000_000L, 0));
    }

    @Test
    void slowServerDrawWithoutArmingHoldsAndReplays() {
        // Deploy stall: the server draw starts after the armed hold already expired, so the
        // guard is disarmed exactly while the server draw is running. The tap must still be
        // held and replayed when the draw ends — never silently dropped.
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(200_000_000L, 500));
        FirstShotDrawGuard.noteFireAttempt(200_000_000L);
        // Draw still running: keep waiting.
        assertFalse(FirstShotDrawGuard.pollPendingShot(300_000_000L, 500, false));
        // Draw ended: fire once...
        assertTrue(FirstShotDrawGuard.pollPendingShot(900_000_000L, 0, false));
        // ...and never again for the same tap.
        assertFalse(FirstShotDrawGuard.pollPendingShot(950_000_000L, 0, false));
    }

    @Test
    void tapDuringHoldFiresExactlyOnceWhenObserved() {
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        // Tap lands inside the hold: blocked, remembered.
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(100_000_000L);
        // Trigger released before the server draw is observed: still waiting.
        assertFalse(FirstShotDrawGuard.pollPendingShot(200_000_000L, 0, false));
        // Server draw observed but still running: TaCZ would refuse, keep waiting.
        assertFalse(FirstShotDrawGuard.pollPendingShot(300_000_000L, 600, false));
        // Server draw done: fire once...
        assertTrue(FirstShotDrawGuard.pollPendingShot(1_500_000_000L, 0, false));
        // ...and never again for the same tap.
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_550_000_000L, 0, false));
    }

    @Test
    void idleTicksNeverQueueAShot() {
        // TaCZ polls chargeShoot every tick even with the trigger released; the mixin lets
        // those idle polls through without queueing, so an untouched trigger fires nothing.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(200_000_000L, 0));
        // A running server draw holds too (plain TaCZ gating would refuse anyway); the mixin
        // still lets idle polls with a released trigger through without queueing.
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(300_000_000L, 600));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(1_500_000_000L, 0));
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_550_000_000L, 0, false));
    }

    @Test
    void heldTriggerNeedsNoReplay() {
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(100_000_000L);
        // Server draw running: keep holding (plain TaCZ gating would refuse anyway).
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(200_000_000L, 600));
        // Trigger still held when the draw is done: TaCZ's own tick fires, no synthetic shot.
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_500_000_000L, 0, true));
        // The queued tap is consumed: releasing the trigger afterwards fires nothing extra.
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_550_000_000L, 0, false));
    }

    @Test
    void triggerHeldDuringDrawThenReleasedStillReplays() {
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(100_000_000L);
        // Trigger still held while the server draw runs: the queued tap must survive.
        assertFalse(FirstShotDrawGuard.pollPendingShot(300_000_000L, 600, true));
        // Released before the draw ends: TaCZ's tick fires nothing, so the replay must.
        assertTrue(FirstShotDrawGuard.pollPendingShot(1_500_000_000L, 0, false));
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_550_000_000L, 0, false));
    }

    @Test
    void expiredBoundHoldsQueuedTapUntilDrawObserved() {
        // Firing at bound expiry shoots into a server draw that starts later (loadout menu)
        // and is refused as IS_DRAWING — exactly what the hold must prevent. Hold the queue
        // until the draw is observed instead.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(100_000_000L);
        // Bound expired, server draw never observed: hold, never fire (fail-before: true).
        assertFalse(FirstShotDrawGuard.pollPendingShot(BOUND_NS, 0, false));
        // Late server draw starts: keep holding while it runs...
        assertFalse(FirstShotDrawGuard.pollPendingShot(BOUND_NS + 100_000_000L, 400, false));
        // ...fire once when it ends (inside the hard cap)...
        assertTrue(FirstShotDrawGuard.pollPendingShot(BOUND_NS + 900_000_000L, 0, false));
        // ...and never again for the same tap.
        assertFalse(FirstShotDrawGuard.pollPendingShot(BOUND_NS + 950_000_000L, 0, false));
    }

    @Test
    void queuedTapDiesAtHardCapNeverFires() {
        // Explicit rule for a draw that never comes: past bound + 1 s the tap is dropped,
        // never fired — even if a draw shows up afterwards.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(100_000_000L);
        long hardCapNs = 100_000_000L + BOUND_NS + FirstShotDrawGuard.TAP_TTL_EXTRA_NS;
        // Still inside the lifetime: held, never fired (fail-before: the old code fires here).
        assertFalse(FirstShotDrawGuard.pollPendingShot(BOUND_NS, 0, false));
        assertFalse(FirstShotDrawGuard.pollPendingShot(hardCapNs + 1, 0, false));
        // A draw observed after the drop must not resurrect the tap.
        assertFalse(FirstShotDrawGuard.pollPendingShot(hardCapNs + 100_000_000L, 400, false));
        assertFalse(FirstShotDrawGuard.pollPendingShot(hardCapNs + 900_000_000L, 0, false));
    }

    @Test
    void deathDropsQueuedTapRespawnNeverReplays() {
        // Holding fire at death must not fire by itself after respawn: the client tick
        // resets the guard while dead, so the respawn draw starts with no queued tap.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(100_000_000L);
        // Death (see ClientEventHandler.pollGuardLifetime).
        FirstShotDrawGuard.reset();
        // Respawn re-draws the kit gun with the trigger released: nothing replays, neither
        // inside the new hold...
        FirstShotDrawGuard.onDrawToGun(500_000_000L, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(600_000_000L, 0));
        assertFalse(FirstShotDrawGuard.pollPendingShot(2_500_000_000L, 0, false));
        // ...nor after it.
        assertFalse(FirstShotDrawGuard.pollPendingShot(3_500_000_000L, 0, false));
    }

    @Test
    void redrawDropsQueuedTapFromPreviousGun() {
        // Gun switched: the new draw kills the old tap, it never replays for the new gun —
        // not even once the new draw is observed and finished.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(100_000_000L);
        FirstShotDrawGuard.onDrawToGun(200_000_000L, DRAW_MS, "otherGun");
        assertFalse(FirstShotDrawGuard.pollPendingShot(2_000_000_000L, 400, false));
        assertFalse(FirstShotDrawGuard.pollPendingShot(2_900_000_000L, 0, false));
    }

    @Test
    void expiredBoundWithHeldTriggerDefersToTacz() {
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(100_000_000L);
        // Trigger still held at expiry: TaCZ's own tick fires, no synthetic shot...
        assertFalse(FirstShotDrawGuard.pollPendingShot(BOUND_NS, 0, true));
        // ...and releasing afterwards fires nothing extra.
        assertFalse(FirstShotDrawGuard.pollPendingShot(BOUND_NS + 50_000_000L, 0, false));
    }

    @Test
    void hardCapNeverDropsTapWhileServerDrawRuns() {
        // Fail-before for bot major 1: bound 1.7 s, tap at 1.0 s (cap 2.7 s from the tap),
        // server draw runs 3.5-4.0 s. The poll at 3.71 s must hold, not drop, and the tap
        // must fire once the draw ends.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(1_000_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(1_000_000_000L);
        assertFalse(FirstShotDrawGuard.pollPendingShot(3_710_000_000L, 400, false));
        assertTrue(FirstShotDrawGuard.pollPendingShot(4_100_000_000L, 0, false));
        assertFalse(FirstShotDrawGuard.pollPendingShot(4_150_000_000L, 0, false));
    }

    @Test
    void sameGunRedrawKeepsQueuedTap() {
        // Fail-before for bot major 2: deploy double draw of the same gun must not drop
        // the tap queued during the first draw.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(300_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(300_000_000L);
        FirstShotDrawGuard.onDrawToGun(500_000_000L, DRAW_MS, "gun");
        assertFalse(FirstShotDrawGuard.pollPendingShot(600_000_000L, 600, false));
        assertTrue(FirstShotDrawGuard.pollPendingShot(1_500_000_000L, 0, false));
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_550_000_000L, 0, false));
    }
}
