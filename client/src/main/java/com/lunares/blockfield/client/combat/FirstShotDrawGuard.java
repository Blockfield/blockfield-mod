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
 * <p>The queued tap has no time limit: it is kept until the server draw is observed and finished,
 * however late the draw starts. A stale tap never fires by surprise: it is dropped — never fired —
 * on death or respawn, when a different gun is drawn (a re-draw of the same gun keeps it), when the
 * gun is gone, when the screen changes, when a new fire press is no longer held (the player is
 * shooting normally, so the old tap is superseded), and when TaCZ performs a real shot (which would
 * double with the replay).
 */
public final class FirstShotDrawGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger(FirstShotDrawGuard.class);

    /** Extra hold after the gun's draw time before giving up waiting for the server draw. */
    static final long SLACK_NS = 1_000_000_000L;

    private static long armedAtNs = -1;
    private static long boundNs = 0;
    private static boolean serverDrawSeen = false;
    private static String armedWeapon = null;
    private static boolean pendingShot = false;

    private FirstShotDrawGuard() {}

    /**
     * A local draw to a gun started now; the server draw for it is not observed yet. A draw of a
     * different gun means a new gun context: a tap queued for the previous one is dropped, never
     * replayed for the new gun. A re-draw of the same gun (deploy double draw) keeps the queued tap
     * and its lifetime, which stays counted from the draw that queued it.
     *
     * @param weaponKey gun id + slot of the drawn gun, from the caller; only a change drops the tap
     */
    public static synchronized void onDrawToGun(long nowNs, long drawTimeMs, String weaponKey) {
        armedAtNs = nowNs;
        boundNs = Math.max(0, drawTimeMs) * 1_000_000L + SLACK_NS;
        serverDrawSeen = false;
        if (!Objects.equals(armedWeapon, weaponKey)) dropQueuedTap();
        armedWeapon = weaponKey;
        if (LOGGER.isDebugEnabled()) LOGGER.debug("First-shot hold armed, drawMs={}", drawTimeMs);
    }

    /** Sample of the server-synced draw cooldown (0 = none running or none seen yet). */
    public static synchronized void sampleSyncedDraw(long nowNs, long synDrawCoolDownMs) {
        if (synDrawCoolDownMs > 0) {
            // A running server draw counts as observed even without an armed hold (deploy
            // stall): the queued tap replays once this draw ends instead of dying unseen.
            if (!serverDrawSeen) LOGGER.debug("First-shot hold released: server draw observed");
            serverDrawSeen = true;
            return;
        }
        if (armedAtNs < 0) return;
        if (serverDrawSeen || nowNs - armedAtNs >= boundNs) {
            if (!serverDrawSeen) LOGGER.debug("First-shot hold expired: server draw never seen");
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
        return armedAtNs >= 0 && !serverDrawSeen;
    }

    /**
     * Records a genuine fire press blocked by {@link #shouldBlockShoot}, for {@link
     * #pollPendingShot}. Call only for real attempts (a {@code shoot()} call, or a {@code
     * chargeShoot} with the trigger down) — never for TaCZ's idle per-tick polls with a released
     * trigger, which must not queue a shot the user never asked for.
     */
    public static synchronized void noteFireAttempt(long nowNs) {
        if (!pendingShot) LOGGER.debug("First-shot hold: tap queued for replay");
        pendingShot = true;
    }

    /**
     * True when a tap queued during the hold must be fired now — exactly once. Returns false while
     * still holding, while the server draw is still running (plain TaCZ gating would refuse), while
     * the server draw was never observed (it may still start late — firing now would be refused as
     * {@code IS_DRAWING}), and while the trigger is still held (TaCZ's own tick fires).
     */
    public static synchronized boolean pollPendingShot(
            long nowNs, long synDrawCoolDownMs, boolean triggerDown) {
        sampleSyncedDraw(nowNs, synDrawCoolDownMs);
        if (!pendingShot) return false;
        if (armedAtNs >= 0 && !serverDrawSeen) return false;
        if (!serverDrawSeen) return false;
        // Held trigger: TaCZ's own tick fires once the guard releases; a synthetic shot would
        // double it.
        if (synDrawCoolDownMs > 0) return false;
        if (triggerDown) {
            pendingShot = false;
            return false;
        }
        pendingShot = false;
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
     * real TaCZ shot). The armed hold, if any, survives: later presses during the same draw still
     * queue and replay.
     */
    public static synchronized void dropQueuedTap() {
        if (pendingShot) LOGGER.debug("First-shot replay dropped: queued tap discarded");
        pendingShot = false;
    }

    /** Clears the armed hold and any queued tap (death, respawn, logout). */
    public static synchronized void reset() {
        armedAtNs = -1;
        boundNs = 0;
        serverDrawSeen = false;
        armedWeapon = null;
        dropQueuedTap();
    }
}
