package com.lunares.blockfield.client.combat;

import com.tacz.guns.api.entity.IGunOperator;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the first shot after a draw until the server draw is observed.
 *
 * <p>TaCZ's client lock releases 250 ms after a draw even when the server has not processed the
 * {@code DrawGun} packet yet (deploy tick stalls, proxy RTT): the client then predicts a shot the
 * server refuses as {@code IS_DRAWING}, and the click is lost. While armed, a synced draw cooldown
 * of 0 means "server draw not seen yet", not "ready". Once a synced cooldown above 0 is sampled,
 * the server draw is running and plain TaCZ gating takes over; the hold never outlives the gun's
 * own draw plus slack.
 *
 * <p>A deploy draw can stall so long that the server draw only starts after the armed hold already
 * expired (kit menus outlast the bound): the guard is then disarmed exactly while the server draw
 * is running, TaCZ refuses the tap as {@code IS_DRAWING}, and nothing is queued. A synced cooldown
 * above 0 therefore always holds the shot — plain TaCZ gating would refuse it anyway, so holding
 * never delays a live shot — and the press is remembered.
 *
 * <p>A press that arrives during the hold is remembered and fired exactly once, when the server
 * draw was observed and plain TaCZ gating allows the shot again. A held trigger needs no replay
 * (TaCZ's own tick fires once the guard releases).
 *
 * <p>The queued tap is an explicit state machine (see {@code .dev/stw4-r5/state.md}): {@code NONE
 * -> QUEUED(drawId, weaponKey) -> DRAW_SEEN(drawId) -> FIRE once | DROPPED}. A draw counts when its
 * server draw is observed after its {@link #onDrawToGun} (draw id): a fresh 0 -&gt; &gt;0 cooldown
 * edge, or a jump of the implied end time (sample time + cooldown) past tolerance (TaCZ syncs no
 * draw id, and the countdown decays monotonically per draw with a constant end, so only a new draw
 * can move the end — up for a longer draw, down discontinuously for a shorter one). A cooldown
 * already running at arm time merely decays with a constant end, so it holds but never marks seen.
 * A missed detection must still cost at most {@link #SETTLE_NS} past the cooldown reaching 0 (see
 * {@link #sampleSyncedDraw}): then the draw counts as seen, live shots unblock, and a queued tap
 * replays once. A stale cooldown still can not fire before its own end + settle. The tap has no
 * time limit, but any tick with attacks disallowed (tablet, pre-match no-attacks) drops it via
 * {@link #noteAttacksBlocked}, so it can never fire when such a window ends. Otherwise it is
 * dropped — never fired — on death or respawn, when a different gun is drawn (a re-draw of the same
 * gun rebinds it to the new draw id), when the gun is gone, when the screen changes, when a new
 * fire press is no longer held (the player is shooting normally, so the old tap is superseded), and
 * when TaCZ performs a real shot (which would double with the replay).
 */
public final class FirstShotDrawGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger(FirstShotDrawGuard.class);

    /** Extra hold after the gun's draw time before giving up waiting for the server draw. */
    static final long SLACK_NS = 1_000_000_000L;

    /** Queued tap lifecycle: NONE -&gt; QUEUED -&gt; DRAW_SEEN -&gt; fired once, or DROPPED. */
    private enum TapState {
        NONE,
        QUEUED,
        DRAW_SEEN
    }

    /** Id of the latest local draw; every tap is bound to the draw id it waits for. */
    private static long drawSeq = 0;

    private static long armedAtNs = -1;
    private static long boundNs = 0;
    private static long armedSeq = -1;
    private static String armedWeapon = null;

    /**
     * Last sampled server draw cooldown: only a 0 -&gt; &gt;0 edge or an implied-end jump marks a
     * draw seen (R1).
     */
    private static long lastSynMs = 0;

    /**
     * Implied end of the running server draw (sample time + cooldown) in ms, or -1 when no draw is
     * running. A stale cooldown decays with a constant end; only a jump past {@link
     * #END_TOLERANCE_MS} is a new draw.
     */
    private static long lastEndMs = -1;

    /**
     * Same-draw wobble budget for the implied end: 1.5 server ticks (tick = 50 ms). Tick
     * quantization and ms rounding stay inside it; a real new draw moves the end by hundreds of ms.
     */
    static final long END_TOLERANCE_MS = 75;

    /**
     * Settle window past a post-arm cooldown end after which a still-unseen draw counts as seen. No
     * RTT signal reaches this class, so fixed 150 ms inside the 100..250 ms band: ~2x the
     * end-tolerance, so tick quantization and wobble settle before a real end jump is sampled, far
     * below the ~1.7 s bound.
     */
    static final long SETTLE_NS = 150_000_000L;

    /** Draw id whose server draw was observed. */
    private static long seenSeq = -1;

    /**
     * Ns time of the first 0-cooldown sample after a running (&gt;0) cooldown, reset by every
     * {@link #onDrawToGun}; -1 when no post-arm draw ran yet. Only a draw that actually ran and
     * ended can settle — a hold that never saw any cooldown keeps waiting for detection.
     */
    private static long cooldownZeroSinceNs = -1;

    private static TapState tapState = TapState.NONE;
    private static long tapDrawId = -1;

    private FirstShotDrawGuard() {}

    /**
     * A local draw to a gun started now; the server draw for it is not observed yet. A draw of a
     * different gun means a new gun context: a tap queued for the previous one is dropped, never
     * replayed for the new gun. A re-draw of the same gun (deploy double draw) rebinds the queued
     * tap to the new draw id: it waits for the new draw's own start instead of firing for the
     * superseded one.
     *
     * @param weaponKey gun id + slot of the drawn gun, from the caller; only a change drops the tap
     */
    public static synchronized void onDrawToGun(long nowNs, long drawTimeMs, String weaponKey) {
        drawSeq++;
        armedAtNs = nowNs;
        boundNs = Math.max(0, drawTimeMs) * 1_000_000L + SLACK_NS;
        armedSeq = drawSeq;
        cooldownZeroSinceNs = -1;
        if (!Objects.equals(armedWeapon, weaponKey)) {
            dropQueuedTap();
        } else if (tapState != TapState.NONE) {
            tapDrawId = drawSeq;
            tapState = TapState.QUEUED;
        }
        armedWeapon = weaponKey;
        if (LOGGER.isDebugEnabled()) LOGGER.debug("First-shot hold armed, drawMs={}", drawTimeMs);
    }

    /** Sample of the server-synced draw cooldown (0 = none running or none seen yet). */
    public static synchronized void sampleSyncedDraw(long nowNs, long synDrawCoolDownMs) {
        if (synDrawCoolDownMs > 0) {
            long endMs = nowNs / 1_000_000L + synDrawCoolDownMs;
            // New server draw started: a fresh 0 -> >0 edge, or a jump of the implied end past
            // tolerance (a longer B raises it, a shorter B drops it discontinuously). A stale
            // cooldown merely decays with a constant end and never marks seen (R1).
            if (lastSynMs == 0
                    || (lastEndMs >= 0 && Math.abs(endMs - lastEndMs) > END_TOLERANCE_MS)) {
                if (seenSeq != drawSeq)
                    LOGGER.debug("First-shot hold released: server draw observed");
                seenSeq = drawSeq;
                if (tapState == TapState.QUEUED && tapDrawId == drawSeq)
                    tapState = TapState.DRAW_SEEN;
            }
            lastEndMs = endMs;
        } else {
            if (lastSynMs > 0) cooldownZeroSinceNs = nowNs;
            lastEndMs = -1;
        }
        lastSynMs = synDrawCoolDownMs;
        // Settle fallback: detection missed (e.g. coincident implied ends), but a post-arm draw
        // ran and ended. Treat it as seen once the cooldown has read 0 for SETTLE_NS: live shots
        // unblock below and a queued tap replays once. Nothing can fire before its own end.
        if (synDrawCoolDownMs == 0
                && cooldownZeroSinceNs >= 0
                && nowNs - cooldownZeroSinceNs >= SETTLE_NS) {
            if (seenSeq != drawSeq) LOGGER.debug("First-shot hold released: draw settle elapsed");
            seenSeq = drawSeq;
            if (tapState == TapState.QUEUED && tapDrawId == drawSeq) tapState = TapState.DRAW_SEEN;
        }
        if (armedAtNs < 0) return;
        if (seenSeq == armedSeq || nowNs - armedAtNs >= boundNs) {
            if (seenSeq != armedSeq)
                LOGGER.debug("First-shot hold expired: server draw never seen");
            armedAtNs = -1;
        }
    }

    /**
     * True when a shot attempt must be held locally: drawn to a gun with the server draw not
     * observed yet and still within the bound, or with a server draw running right now (plain TaCZ
     * gating would refuse it anyway, so holding never delays a live shot). Callers report {@code
     * IS_DRAWING} without sending anything. Pure predicate: it never remembers anything (see {@link
     * #noteFireAttempt}).
     */
    public static synchronized boolean shouldBlockShoot(long nowNs, long synDrawCoolDownMs) {
        sampleSyncedDraw(nowNs, synDrawCoolDownMs);
        if (synDrawCoolDownMs > 0) return true;
        return armedAtNs >= 0 && seenSeq != armedSeq;
    }

    /**
     * Records a genuine fire press blocked by {@link #shouldBlockShoot}, for {@link
     * #pollPendingShot}. Call only for real attempts (a {@code shoot()} call, or a {@code
     * chargeShoot} with the trigger down) — never for TaCZ's idle per-tick polls with a released
     * trigger, which must not queue a shot the user never asked for.
     */
    public static synchronized void noteFireAttempt(long nowNs) {
        if (tapState == TapState.NONE) {
            tapDrawId = drawSeq;
            tapState = seenSeq == drawSeq ? TapState.DRAW_SEEN : TapState.QUEUED;
            LOGGER.debug("First-shot hold: tap queued for replay");
        }
    }

    /**
     * Attacks are disallowed right now (tablet open, pre-match no-attacks window). A tap queued
     * earlier dies: firing it when the window ends would surprise the player. Callers report every
     * disallowed tick, so the tap cannot outlive the window — it fires only while attacks were
     * allowed continuously since queueing.
     */
    public static synchronized void noteAttacksBlocked() {
        dropQueuedTap();
    }

    /**
     * True when a tap queued during the hold must be fired now — exactly once. Returns false while
     * its own draw is still unseen and unsettled (it may start late — firing now would be refused
     * as {@code IS_DRAWING}), while the server draw is still running (plain TaCZ gating would
     * refuse), and while the trigger is still held (TaCZ's own tick fires).
     */
    public static synchronized boolean pollPendingShot(
            long nowNs, long synDrawCoolDownMs, boolean triggerDown) {
        sampleSyncedDraw(nowNs, synDrawCoolDownMs);
        if (tapState != TapState.DRAW_SEEN) return false;
        // Server draw still running: keep waiting (plain TaCZ gating would refuse).
        if (synDrawCoolDownMs > 0) return false;
        tapState = TapState.NONE;
        tapDrawId = -1;
        // Held trigger: TaCZ's own tick fires once the guard releases; a synthetic shot would
        // double it.
        if (triggerDown) return false;
        LOGGER.debug("First-shot replay: firing queued tap");
        return true;
    }

    /** Every client tick: record a running server draw, so a later click still sees it. */
    public static void samplePlayerTick() {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        // TaCZ reads the same value every tick in tickStateLock; no extra failure mode here.
        sampleSyncedDraw(
                System.nanoTime(), IGunOperator.fromLivingEntity(player).getSynDrawCoolDown());
    }

    /**
     * Drops the queued tap without firing it (screen change, gun gone, a new unheld fire press, a
     * real TaCZ shot, a tick with attacks disallowed). The armed hold, if any, survives: later
     * presses during the same draw still queue and replay.
     */
    public static synchronized void dropQueuedTap() {
        if (tapState != TapState.NONE)
            LOGGER.debug("First-shot replay dropped: queued tap discarded");
        tapState = TapState.NONE;
        tapDrawId = -1;
    }

    /** Clears the armed hold and any queued tap (death, respawn, logout). */
    public static synchronized void reset() {
        armedAtNs = -1;
        boundNs = 0;
        armedSeq = -1;
        armedWeapon = null;
        lastSynMs = 0;
        lastEndMs = -1;
        cooldownZeroSinceNs = -1;
        seenSeq = -1;
        dropQueuedTap();
    }
}
