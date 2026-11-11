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
    void staleCooldownAfterRearmHoldsUntilBound() {
        // Rapid A->B switch: B arms while A's server cooldown still runs (sampled every
        // tick, so no fresh edge appears). The stale cooldown holds B but never marks it
        // seen: it decays toward the same implied end (50 + 400 = 150 + 300), so the hold
        // lasts until B's own bound, firing nothing by itself.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(50_000_000L, 400));
        FirstShotDrawGuard.onDrawToGun(100_000_000L, DRAW_MS, "otherGun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(150_000_000L, 300));
        // Stale draw ended, B's own draw never synced: still holding.
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(500_000_000L, 0));
        // Bound expired with no draw of its own observed: release like the unmodded game.
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(100_000_000L + BOUND_NS, 0));
        assertFalse(
                FirstShotDrawGuard.shouldBlockShoot(100_000_000L + BOUND_NS + 1_000_000_000L, 0));
    }

    @Test
    void staleCooldownNeverCountsAsNewDraw() {
        // Fail-before for bot major 4507884730: gun A is mid-draw (server cooldown running,
        // sampled every tick), the player switches to gun B mid-draw. The stale cooldown
        // holds the new draw but must never mark it seen: it decays toward the same
        // implied end (100 + 400 = 250 + 250), so the tap queued during the stale
        // hold must never fire once the stale cooldown ends while B's own draw never synced.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gunA");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 400));
        FirstShotDrawGuard.onDrawToGun(200_000_000L, DRAW_MS, "gunB");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(250_000_000L, 250));
        FirstShotDrawGuard.noteFireAttempt(300_000_000L);
        // Stale draw ends, B's draw never observed: hold, never fire.
        assertFalse(FirstShotDrawGuard.pollPendingShot(420_000_000L, 0, false));
        assertFalse(FirstShotDrawGuard.pollPendingShot(500_000_000L, 0, false));
    }

    @Test
    void tapDiesWhenAttacksBlocked() {
        // Fail-before for bot major 4507884733: a tap queued during the draw must die when
        // attacks become disallowed (pre-match no-attacks window, tablet) instead of firing
        // when the window ends. The caller reports every disallowed tick (see
        // ClientEventHandler.replayQueuedTap).
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(100_000_000L);
        // Attacks blocked before the draw is seen: the tap dies, never fires later.
        FirstShotDrawGuard.noteAttacksBlocked();
        assertFalse(FirstShotDrawGuard.pollPendingShot(300_000_000L, 600, false));
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_500_000_000L, 0, false));
    }

    @Test
    void redrawAfterDrawSeenWaitsForNewDraw() {
        // The tap's draw finished syncing, then the same gun re-draws before the replay:
        // the tap now waits for the new draw's own start instead of firing for the old one.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(100_000_000L);
        assertFalse(FirstShotDrawGuard.pollPendingShot(300_000_000L, 600, false));
        FirstShotDrawGuard.onDrawToGun(500_000_000L, DRAW_MS, "gun");
        // Old draw done, new draw never started: must not fire for the superseded draw.
        assertFalse(FirstShotDrawGuard.pollPendingShot(900_000_000L, 0, false));
        // New draw runs and ends: fires once...
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_000_000_000L, 400, false));
        assertTrue(FirstShotDrawGuard.pollPendingShot(1_500_000_000L, 0, false));
        // ...and never again for the same tap.
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_550_000_000L, 0, false));
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
    void lateServerDrawStillFiresOnceWithoutCap() {
        // Bot round 4: bound 1.7 s, tap at 0.5 s, server draw starts at 3.3 s —
        // past the old bound + 1 s cap. The tap must survive and fire once when
        // the late draw ends, never twice.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(500_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(500_000_000L);
        // Past the old hard cap, server draw never observed: still holding.
        assertFalse(FirstShotDrawGuard.pollPendingShot(3_200_000_000L, 0, false));
        assertFalse(FirstShotDrawGuard.pollPendingShot(3_250_000_000L, 0, false));
        // Late server draw starts: keep holding while it runs...
        assertFalse(FirstShotDrawGuard.pollPendingShot(3_300_000_000L, 400, false));
        // ...fire once when it ends...
        assertTrue(FirstShotDrawGuard.pollPendingShot(4_100_000_000L, 0, false));
        // ...and never again for the same tap.
        assertFalse(FirstShotDrawGuard.pollPendingShot(4_150_000_000L, 0, false));
    }

    @Test
    void supersededTapNeverFires() {
        // A new fire press after the hold, or a real TaCZ shot, supersedes the
        // queued tap: the mixin drops it via dropQueuedTap, so it never fires —
        // even when a late draw is observed and finished afterwards.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 0));
        FirstShotDrawGuard.noteFireAttempt(100_000_000L);
        // New press after the hold ended (player shooting normally).
        FirstShotDrawGuard.dropQueuedTap();
        assertFalse(FirstShotDrawGuard.pollPendingShot(3_300_000_000L, 400, false));
        assertFalse(FirstShotDrawGuard.pollPendingShot(4_100_000_000L, 0, false));
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
    void confirmedTapRacingRedrawRebindsAndFiresOnce() {
        // Fail-before for the FS1 1/12 loss: the tap lands during draw A after A's server draw
        // was observed (confirmed press), then a different gun is drawn before the replay. The
        // press must rebind to the new draw and fire once when it ends — never lost. An
        // unconfirmed tap (draw never observed) still dies with its draw (see above).
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gunA");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 400));
        FirstShotDrawGuard.noteFireAttempt(150_000_000L);
        // Racing redraw of a different gun: rebind, still holding while the new draw runs...
        FirstShotDrawGuard.onDrawToGun(200_000_000L, DRAW_MS, "gunB");
        assertFalse(FirstShotDrawGuard.pollPendingShot(300_000_000L, 600, false));
        // ...fire once when the new draw ends...
        assertTrue(FirstShotDrawGuard.pollPendingShot(1_500_000_000L, 0, false));
        // ...and never again for the same tap.
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_550_000_000L, 0, false));
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

    @Test
    void overlappingDrawInsidePreviousCooldownReachesDrawSeen() {
        // Fail-before for bot major 4507946755: B's server draw starts inside A's
        // cooldown, so the synced value never drops to 0 and there is no 0 -> >0
        // edge. The jump up (A remainder -> B full) must still mark B seen, or
        // the tap never fires and the hold blocks until the bound.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gunA");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 400));
        FirstShotDrawGuard.onDrawToGun(300_000_000L, DRAW_MS, "gunB");
        // B's server draw starts while A's cooldown still runs: synced value
        // jumps up, never touching 0.
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(400_000_000L, 650));
        FirstShotDrawGuard.noteFireAttempt(500_000_000L);
        // B's draw still running: hold, no fire yet.
        assertFalse(FirstShotDrawGuard.pollPendingShot(600_000_000L, 600, false));
        // B's draw finished: fire once...
        assertTrue(FirstShotDrawGuard.pollPendingShot(1_100_000_000L, 0, false));
        // ...and never again; the hold is released.
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_150_000_000L, 0, false));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(1_200_000_000L, 0));
    }

    @Test
    void sameGunRedrawInsideCooldownReachesDrawSeen() {
        // Same-gun re-draw inside the first draw's cooldown: same shape as the
        // overlap above, but the tap is rebound instead of dropped.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 400));
        FirstShotDrawGuard.onDrawToGun(300_000_000L, DRAW_MS, "gun");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(400_000_000L, 650));
        FirstShotDrawGuard.noteFireAttempt(500_000_000L);
        assertFalse(FirstShotDrawGuard.pollPendingShot(600_000_000L, 600, false));
        assertTrue(FirstShotDrawGuard.pollPendingShot(1_100_000_000L, 0, false));
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_150_000_000L, 0, false));
    }

    @Test
    void shorterDrawInsidePreviousCooldownReachesDrawSeen() {
        // Fail-before for bot thread 4507997477: B's draw is shorter than A's
        // remainder (A 600 ms left at the switch, B full 300 ms), so the synced
        // value only falls (600 -> 300 -> 0) and no increase ever marks B seen.
        // The implied end (sample time + cooldown) still jumps, which marks B.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gunA");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 700));
        FirstShotDrawGuard.onDrawToGun(300_000_000L, DRAW_MS, "gunB");
        // Stale A remainder decaying toward the same end (400 + 400 = 800): hold.
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(400_000_000L, 400));
        FirstShotDrawGuard.noteFireAttempt(500_000_000L);
        // B's server draw runs, shorter than A's remainder: value drops, no rise.
        assertFalse(FirstShotDrawGuard.pollPendingShot(600_000_000L, 300, false));
        // B's draw finished: fire once...
        assertTrue(FirstShotDrawGuard.pollPendingShot(1_100_000_000L, 0, false));
        // ...and never again; the hold is released.
        assertFalse(FirstShotDrawGuard.pollPendingShot(1_150_000_000L, 0, false));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(1_200_000_000L, 0));
    }

    @Test
    void coincidentImpliedEndsSettleWithinWindow() {
        // Fail-before for bot major 4508131963: B's implied end (200 + 300 = 500)
        // coincides with A's (100 + 400 = 500) inside the 75 ms tolerance, so no
        // end jump ever marks B seen. The miss must cost at most SETTLE_NS past
        // the cooldown reaching 0 — never the ~1.7 s bound.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gunA");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 400));
        FirstShotDrawGuard.onDrawToGun(150_000_000L, DRAW_MS, "gunB");
        // Same implied end (500): detection misses, but the hold stays.
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(200_000_000L, 300));
        FirstShotDrawGuard.noteFireAttempt(250_000_000L);
        // B's draw ends: cooldown 0, gun ready — still settling, no fire yet.
        assertFalse(FirstShotDrawGuard.pollPendingShot(500_000_000L, 0, false));
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(500_000_000L, 0));
        // Inside the settle window a missed draw still can not fire (R1).
        long midSettle = 500_000_000L + FirstShotDrawGuard.SETTLE_NS - 50_000_000L;
        assertFalse(FirstShotDrawGuard.pollPendingShot(midSettle, 0, false));
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(midSettle, 0));
        // Settle elapsed: the tap fires exactly once, live shots unblock.
        long settled = 500_000_000L + FirstShotDrawGuard.SETTLE_NS;
        assertTrue(FirstShotDrawGuard.pollPendingShot(settled, 0, false));
        assertFalse(FirstShotDrawGuard.pollPendingShot(settled + 50_000_000L, 0, false));
        assertFalse(FirstShotDrawGuard.shouldBlockShoot(settled + 50_000_000L, 0));
    }

    @Test
    void staleSettleKeepsTapUntilRealDrawEnds() {
        // Fail-before for bot major 4508173804: A stale-settles B (A's remainder ends
        // after onDrawToGun(B), before B's own draw starts). The settle fires the replay
        // while B's draw is still unsynced and TaCZ refuses it as IS_DRAWING: the tap is
        // kept queued and retried, firing exactly once after B's real draw instead of lost.
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gunA");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 400));
        FirstShotDrawGuard.onDrawToGun(200_000_000L, DRAW_MS, "gunB");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(250_000_000L, 250));
        FirstShotDrawGuard.noteFireAttempt(300_000_000L);
        // Stale A ends at ~500 ms: still settling, hold, no fire (R1).
        assertFalse(FirstShotDrawGuard.pollPendingShot(500_000_000L, 0, false));
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(500_000_000L, 0));
        // Settle elapses on the STALE end: replay fires...
        long settled = 500_000_000L + FirstShotDrawGuard.SETTLE_NS;
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(settled - 50_000_000L, 0));
        assertTrue(FirstShotDrawGuard.pollPendingShot(settled, 0, false));
        // ...TaCZ refuses it (B's own draw unsynced): the tap must survive...
        FirstShotDrawGuard.noteReplayMissed();
        // ...B's real draw runs: hold, no fire...
        assertFalse(FirstShotDrawGuard.pollPendingShot(settled + 100_000_000L, 300, false));
        // ...ends: the press fires exactly once...
        assertTrue(FirstShotDrawGuard.pollPendingShot(settled + 500_000_000L, 0, false));
        // ...and never again.
        assertFalse(FirstShotDrawGuard.pollPendingShot(settled + 550_000_000L, 0, false));
    }

    @Test
    void cooldownJitterWithinToleranceNeverMarksNewDraw() {
        // Same-draw samples wobble a few ms (tick quantization, ms rounding) around
        // one implied end: wobble inside the tolerance must not mark a new draw (R1).
        FirstShotDrawGuard.onDrawToGun(0, DRAW_MS, "gunA");
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(100_000_000L, 400));
        FirstShotDrawGuard.onDrawToGun(200_000_000L, DRAW_MS, "gunB");
        // Stale A remainder decaying toward the same end (500), +/-30 ms wobble.
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(250_000_000L, 250));
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(300_000_000L, 230));
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(350_000_000L, 150));
        FirstShotDrawGuard.noteFireAttempt(400_000_000L);
        // Stale ended, B's own draw never synced: hold, never fire.
        assertTrue(FirstShotDrawGuard.shouldBlockShoot(500_000_000L, 0));
        assertFalse(FirstShotDrawGuard.pollPendingShot(500_000_000L, 0, false));
        assertFalse(FirstShotDrawGuard.pollPendingShot(600_000_000L, 0, false));
    }
}
