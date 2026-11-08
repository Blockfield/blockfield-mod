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
        FirstShotDrawGuard.onDrawToGun(10_000_000_000L, DRAW_MS);
        // Server draw not seen yet: hold, do not predict into a refusal.
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(10_100_000_000L, 0));
        // Server draw running: plain TaCZ gating takes over.
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(10_200_000_000L, 350));
        // Draw finished: shootable again.
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(11_000_000_000L, 0));
        // stays released afterwards
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(11_050_000_000L, 0));
    }

    @Test
    void coldRaceHoldIsWhatFailedBefore() {
        // 250 ms TaCZ lock timeout passed with synced still 0: fail-before would allow the shot.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS);
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(300_000_000L, 0));
        // Server draw finally arrives late: still correct to wait for it, then release.
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(400_000_000L, 600));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(1_500_000_000L, 0));
    }

    @Test
    void neverHoldsPastTheBound() {
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS);
        // shortcut: bound (draw + slack) exceeded, server draw never observed.
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(BOUND_NS, 0));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(5_000_000_000L, 0));
    }

    @Test
    void redrawRearms() {
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS);
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 300));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(900_000_000L, 0));
        // Second draw (deploy double draw): hold again until its server draw is seen.
        FirstShotDrawGuard.onDrawToGun(950_000_000L, DRAW_MS);
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(1_000_000_000L, 0));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(1_100_000_000L, 200));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(2_000_000_000L, 0));
    }

    @Test
    void staleCooldownAfterRearmReleasesEarlyButTaczStillGates() {
        // Rapid A->B->A switch: the previous draw's server cooldown is still running when the new
        // draw arms, so the new hold releases immediately. Safe direction: plain TaCZ preCheck
        // refuses while that cooldown is above 0, exactly like the unmodded game.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS);
        FirstShotDrawGuard.onDrawToGun(100_000_000L, DRAW_MS);
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(150_000_000L, 400));
    }

    @Test
    void tapDuringHoldFiresExactlyOnceWhenObserved() {
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS);
        // Tap lands inside the hold: blocked, remembered.
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt();
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
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS);
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(200_000_000L, 0));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(300_000_000L, 600));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(1_500_000_000L, 0));
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_550_000_000L, 0, false));
    }

    @Test
    void heldTriggerNeedsNoReplay() {
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS);
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt();
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(200_000_000L, 600));
        // Trigger still held when the draw is done: TaCZ's own tick fires, no synthetic shot.
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_500_000_000L, 0, true));
        // The queued tap is consumed: releasing the trigger afterwards fires nothing extra.
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_550_000_000L, 0, false));
    }

    @Test
    void expiredBoundFiresQueuedTap() {
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS);
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt();
        // Bound expired, server draw never observed: fire once through plain TaCZ gating
        // instead of silently dropping the tap...
        assertTrue(FirstShotDrawGuard.pollPendingShot(BOUND_NS, 0, false));
        // ...and never again for the same tap.
        assertFalse(FirstShotDrawGuard.pollPendingShot(BOUND_NS + 50_000_000L, 0, false));
    }

    @Test
    void expiredBoundWithHeldTriggerDefersToTacz() {
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS);
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt();
        // Trigger still held at expiry: TaCZ's own tick fires, no synthetic shot...
        assertFalse(FirstShotDrawGuard.pollPendingShot(BOUND_NS, 0, true));
        // ...and releasing afterwards fires nothing extra.
        assertFalse(FirstShotDrawGuard.pollPendingShot(BOUND_NS + 50_000_000L, 0, false));
    }
}
