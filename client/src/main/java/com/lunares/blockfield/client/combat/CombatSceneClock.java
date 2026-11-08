package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.AnimationTiming;
import com.lunares.blockfield.common.combat.AnimationTypes;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.LongToDoubleFunction;

/**
 * Monotonic main-frame clocks R and A. Neither client ticks nor render partials enter their
 * arithmetic. R and A both follow ADR4 §3.1 ({@link AnimationTiming#advance} drives A) from the
 * same arrival estimate, R advanced once per main frame and quantized to Q16.
 */
public final class CombatSceneClock {
    // Bounded catch-up after a server stall (remote-snap U1, amends ADR4 §3.1): instead of
    // freezing through a backlog and snapping on recovery, R advances at
    // 1 + gain·(occupancy − delay), clamped to CATCH_UP_MAX, until occupancy ≤ delay + 1,
    // then hands back to the servo. A snap happens only beyond the lag bound: over 40 ticks
    // (the buffer holds 39, so older R is unresolvable) or still beyond the ticket age bound
    // 400 ms into the catch-up. Real teleports clear the buffer (ClientSnapshotBuffer) and
    // re-init R instead, so they never take this path. Rendered stays pose(R): hit tickets exact.
    static final double CATCH_UP_MAX = 3;
    static final double CATCH_UP_GAIN = .5;
    // At CATCH_UP_MAX a 20-tick (1 s) stall drains to the minimum age bound (8) in ~310 ms,
    // so the budget carries margin over that; anything still beyond the bound then snaps.
    static final long CATCH_UP_BUDGET_MS = 400;
    static final long LAG_SNAP_TICKS = 40;

    /**
     * Sustained-burst regime (PING366-c4): back-to-back gap episodes mean arrivals flow but
     * bunched, so playback must never rush: the rate caps at a smooth 1.25x slew and the episode
     * budget carries the same drain capacity as CATCH_UP_BUDGET_MS at CATCH_UP_MAX. A single burst
     * is indistinguishable from a genuine stall and still drains at full rate.
     */
    static final double CATCH_UP_BURST_MAX = 1.25;

    static final long CATCH_UP_BURST_WINDOW_MS = 1500;

    /**
     * Speed-capped traversal of non-teleport steps (remote-snap U2): while R crosses a tick whose
     * eligible horizontal displacement exceeds this cap, R advances at most cap metres per tick, so
     * the drawn speed stays ≤ cap. 1.0 m/tick clears the fastest legit single-tick ParCool move
     * with margin: Dodge sets 0.9 × min(DodgeSpeedModifier 1.0, MaxDodgeSpeedModifier 1.0) velocity
     * on a unit input vector (pinned ParCool bf7 defaults, ParCoolConfig), FastRun adds 2.0/100
     * flat speed (~0.34 m/tick sustained), vanilla sprint is ~0.28 m/tick. Horizontal only: gravity
     * falls reach ~3.9 m/tick and are continuous, so capping them would freeze falling bodies
     * mid-air instead of smoothing a stall step.
     */
    static final double STEP_CAP_M_PER_TICK = 1.0;

    /**
     * A run of more than this many consecutive above-cap ticks is sustained fast motion (elytra,
     * knockback tail), not a stall step: the scene exempts it from the cap, so R keeps tracking
     * arrivals and the shared clock never lags behind one fast entity.
     */
    static final int STEP_CAPPED_RUN = 2;

    /**
     * Servo-branch traversal backstop (remote-snap U2 round 3): without a backlog there is no stall
     * to repay, so steady fast motion — constant or fluctuating around the cap, which no
     * streak/window count can catch — must never pile lag past this. Once occupancy exceeds delay
     * by more than this, the cap releases for the frame and R tracks arrivals again. Knockback
     * tails (occupancy ≈ 8 at delay 3) still traverse capped; only runaway lag releases.
     */
    static final int SERVO_CAP_LAG_TICKS = 8;

    private final NavigableSet<Long> ticks = new TreeSet<>();
    private long high = -1;
    private double lastArrival = Double.NaN;
    private double lastFrame = Double.NaN;
    private double lastSlew = Double.NEGATIVE_INFINITY;
    private double smooth;
    private double jitter;
    private double time = Double.NaN;
    private double rate = 1;
    private double nextServoMs;
    private boolean servoMissed;
    private int batches;
    private int delay = 3;
    private int min = 2;
    private int max = 6;
    private int holdMs = 500;
    private double dilation = .1;
    private boolean recovery;
    private boolean silenceRecovery;
    private boolean gapRecovery;
    private boolean backlogSnap;
    private boolean catchUp;
    private double catchUpSinceMs;
    private boolean catchUpBurst;
    private double catchUpBudgetMs = CATCH_UP_BUDGET_MS;
    private double lastCatchUpEndMs = Double.NEGATIVE_INFINITY;
    // Windowed burstiness signals for the servo: a tick gap with arrivals still flowing, and
    // the peak occupancy seen since the last slew. Reset on every slew evaluation.
    private boolean burstSeen;
    private double occPeak;
    private boolean backlog;
    private double backlogSinceMs;
    private long recoveryStart;
    private long recoverySnaps;
    private long delayChanges;
    private AnimationTiming.ClockState animation = AnimationTiming.ClockState.cold();
    private AnimationTiming.ClockReason animationReason = AnimationTiming.ClockReason.WARMUP;
    private final java.util.Map<AnimationTiming.ClockReason, Long> animationCounts =
            new java.util.EnumMap<>(AnimationTiming.ClockReason.class);
    // Per frame: whether R's servo slot fired, why R recovered, and whether R is catching up,
    // for A's law.
    private boolean servoSlot;
    private boolean silenceSnap;
    private boolean gapSnap;

    /**
     * Eligible horizontal step in metres by server tick (NaN when the tick carries no capped
     * traversal: teleport/seat/vehicle boundary or no record). Set per frame by the scene; null
     * means no traversal cap. Rendered stays pose(R): only R's advance is limited.
     */
    private LongToDoubleFunction stepMetres;

    /** Frozen budget so far this catch-up episode (S1 bound), and the step that bound it. */
    private double catchUpFrozenMs;

    private double lastCappedStep;

    /** Per-frame traversal input for the next {@link #frame} calls; null clears the cap. */
    public void noteSteps(LongToDoubleFunction steps) {
        stepMetres = steps;
    }

    public void arrival(PlayerSnapshotS2CPacket packet, double nowMs) {
        double silence = Double.isNaN(lastArrival) ? 0 : nowMs - lastArrival;
        boolean gap = high >= 0 && packet.serverTick() - high > 2;
        double dev = nowMs - packet.serverTick() * 50.0;
        // Detect beyond ordinary jitter; drain to less than one tick of excess age before snapping.
        boolean wasBacklog = backlog;
        backlog = batches > 0 && dev - smooth >= (backlog ? 50 : 150);
        if (backlog && !wasBacklog) backlogSinceMs = nowMs;
        // A stall backlog drains in one burst; excess age that outlasts a hold window is a lasting
        // latency step, so re-seed the timing estimate instead of holding presentation forever.
        if (backlog && nowMs - backlogSinceMs >= holdMs) {
            smooth = dev;
            backlog = false;
        }
        if (backlog || gap || (high >= 0 && silence >= holdMs)) {
            // A new recovery only starts from steady state. While catching up, further burst
            // arrivals join the same stall episode: the budget timer keeps running instead of
            // restarting on every arrival.
            if (!recovery && !catchUp) {
                recoveryStart = packet.serverTick();
                silenceRecovery = !backlog && !gap;
                gapRecovery = gap;
            }
            recovery = true;
        }
        min = packet.interpMin();
        max = packet.interpMax();
        dilation = packet.dilationPercent() / 100.0;
        holdMs = packet.holdToSnapMs();
        rate = Math.clamp(rate, 1 - dilation, 1 + dilation);
        high = packet.serverTick();
        lastArrival = nowMs;
        ticks.add(high);
        ticks.headSet(Math.max(0, high - 39), false).clear();
        // Bursty arrivals bunch ticks without moving the jitter mean (constant high latency
        // does the same), so the servo also watches occupancy: a tick gap with arrivals still
        // flowing, and the peak occupancy since the last slew.
        if (!Double.isNaN(time)) occPeak = Math.max(occPeak, high - time);
        if (gap && !backlog) burstSeen = true;
        // Ordered backlog remains valid decode history, but is not a network timing sample.
        if (backlog) return;
        if (batches++ == 0) {
            smooth = dev;
            delay = Math.clamp(3, min, max);
        } else {
            smooth += .1 * (dev - smooth);
            jitter += .1 * (Math.abs(dev - smooth) - jitter);
        }
        // One servo sample per received tick, half an interval after its smoothed arrival: H is
        // then unaffected by arrival jitter below 25 ms, and server tick-rate drift cannot walk a
        // free-running sample grid onto the arrivals.
        // A frame slower than half a tick can miss the slot: the next arrival re-arms it first.
        // Unsampled, the last rate holds and R walks away from H without bound.
        servoMissed = batches > 1 && nextServoMs != Double.POSITIVE_INFINITY;
        nextServoMs = smooth + (high + .5) * 50;
        if (nowMs - lastSlew >= 500
                && (Math.abs(rate - 1) <= .05000001 || (catchUp && catchUpBurst))) {
            int desired = batches < 20 ? 3 : 2 + (int) Math.ceil(jitter / 50 * 1.5);
            if (burstSeen)
                desired = Math.max(desired, Math.clamp((int) Math.ceil(occPeak) - 1, min, max));
            desired = Math.clamp(desired, min, max);
            if (delay != desired) {
                delay += Integer.signum(desired - delay);
                delayChanges++;
            }
            lastSlew = nowMs;
            burstSeen = false;
            occPeak = 0;
        }
    }

    /**
     * One main frame: R by the occupancy law, then A from the same frame; false when R is not live.
     */
    public boolean frameNs(long nowNs) {
        servoSlot = silenceSnap = gapSnap = backlogSnap = false;
        if (!frame(nowNs / 1_000_000.0)) return false;
        var step =
                AnimationTiming.advance(
                        animation,
                        new AnimationTiming.Frame(
                                r(),
                                high,
                                nowNs,
                                jitter,
                                delay,
                                dilation,
                                high,
                                servoSlot,
                                silenceSnap,
                                gapSnap,
                                backlogSnap,
                                false,
                                recoverySnaps));
        animation = step.state();
        animationReason = step.reason();
        animationCounts.merge(animationReason, 1L, Long::sum);
        return true;
    }

    public boolean frame(double nowMs) {
        double dt = Double.isNaN(lastFrame) ? 0 : Math.max(0, nowMs - lastFrame);
        lastFrame = nowMs;
        boolean missed = servoMissed;
        servoMissed = false;
        if (high < 0 || timedOut(nowMs)) return false;
        if (Double.isNaN(time) && backlog) return false;
        double lag = Double.isNaN(time) ? 0 : high - time;
        if ((recovery || catchUp)
                && (Double.isNaN(time)
                        || lag > LAG_SNAP_TICKS
                        || (catchUp
                                && nowMs - catchUpSinceMs >= catchUpBudgetMs
                                && lag > ageBound()))) {
            // Beyond the lag bound: hard snap, and A may snap with R (ADR4 §3.1 shares it).
            silenceSnap = silenceRecovery;
            gapSnap = gapRecovery;
            backlogSnap = !silenceSnap && !gapSnap;
            double desired = high - delay;
            Long a = ticks.floor((long) Math.floor(desired));
            Long b = ticks.ceiling((long) Math.ceil(desired));
            time =
                    a != null && b != null && a >= recoveryStart && b - a <= 2
                            ? desired
                            : recoveryStart;
            recovery = false;
            catchUp = false;
            catchUpBurst = false;
            lastCatchUpEndMs = nowMs;
            silenceRecovery = gapRecovery = false;
            recoverySnaps++;
            rate = 1;
        } else if (recovery || backlog || catchUp) {
            // Bounded catch-up: keep advancing instead of freezing, no snap on the way
            // out; R stays continuous, so the drawn body fast-forwards instead of jumping.
            if (!catchUp) {
                catchUp = true;
                catchUpSinceMs = nowMs;
                catchUpFrozenMs = 0;
                // An episode hot on the previous one's heels means arrivals flow but bunched:
                // slew smoothly instead of rushing. Isolated stalls keep the full rate.
                catchUpBurst = nowMs - lastCatchUpEndMs < CATCH_UP_BURST_WINDOW_MS;
                catchUpBudgetMs =
                        catchUpBurst
                                ? CATCH_UP_BUDGET_MS * (CATCH_UP_MAX - 1) / (CATCH_UP_BURST_MAX - 1)
                                : CATCH_UP_BUDGET_MS;
            }
            recovery = false;
            rate =
                    catchUpRate(
                            high - time,
                            delay,
                            dilation,
                            catchUpBurst ? CATCH_UP_BURST_MAX : CATCH_UP_MAX);
            double next = Math.min(high, time + rate * dt / 50);
            double capped = clampStep(time, next, dt);
            // The step cap trades ticket staleness for smoothness (remote-snap U2): frames it
            // binds do not consume the episode budget, or every large step would end in the snap
            // the traversal exists to avoid — but only up to twice the current step's traversal
            // need, so pathological episodes still snap. The 40-tick hard bound above applies.
            if (capped < next) {
                double allowance = 2 * (lastCappedStep / STEP_CAP_M_PER_TICK) * 50;
                if (catchUpFrozenMs + dt <= allowance) {
                    catchUpSinceMs += dt;
                    catchUpFrozenMs += dt;
                }
            }
            time = capped;
            // Hand back only at a resolvable R: inside a data gap the resolver holds the
            // pre-gap pose, so keep walking (continuously) instead of handing back and
            // snapping over the gap on the next steady frame.
            Long a = ticks.floor((long) Math.floor(time));
            Long b = ticks.ceiling((long) Math.ceil(time));
            if (high - time <= delay + 1 && (a == null || b == null || b - a <= 2)) {
                catchUp = false;
                catchUpBurst = false;
                lastCatchUpEndMs = nowMs;
                silenceRecovery = gapRecovery = false;
                rate = 1;
            }
        } else if (Double.isNaN(time)) {
            double desired = high - delay;
            Long a = ticks.floor((long) Math.floor(desired));
            Long b = ticks.ceiling((long) Math.ceil(desired));
            if (a == null || b == null || b - a > 2) return false;
            time = desired;
            rate = 1;
        } else {
            // Sample-and-hold of the per-render law: occupancy H-R is a deterministic sawtooth of
            // packet arrival, so reacting to it every frame would chatter (r8 §2.4(3) deviation).
            if (missed || nowMs >= nextServoMs) {
                // A missed slot samples H-R as of the slot, not at this frame's phase.
                double occupancy = high - time + (missed ? (nowMs - nextServoMs) / 50 : 0);
                rate = servo(occupancy, delay, dilation);
                nextServoMs = Double.POSITIVE_INFINITY;
                servoSlot = true;
            }
            double next = Math.min(high, time + rate * dt / 50);
            // Servo has no stall to repay: past the backstop the motion is real no matter the
            // pattern, so the cap releases for the frame instead of piling lag without limit.
            time = high - time > delay + SERVO_CAP_LAG_TICKS ? next : clampStep(time, next, dt);
            Long a = ticks.floor((long) Math.floor(time));
            Long b = ticks.ceiling((long) Math.ceil(time));
            if (a != null && b != null && b - a > 2) {
                time = b;
                recoverySnaps++;
                rate = 1;
                gapSnap = true;
            }
        }
        return true;
    }

    public static double servo(double occupancy, int delay, double dilation) {
        if (occupancy >= delay && occupancy <= delay + 1) return 1;
        double error = occupancy - (delay + .5);
        return 1 + Math.copySign(Math.min(dilation, .05 * Math.abs(error)), error);
    }

    /** Bounded catch-up rate: 1 + gain·(occupancy − delay), clamped to CATCH_UP_MAX. */
    public static double catchUpRate(double occupancy, int delay, double dilation) {
        return catchUpRate(occupancy, delay, dilation, CATCH_UP_MAX);
    }

    /** Burst-regime overload with a lower slew cap; same law otherwise. */
    static double catchUpRate(double occupancy, int delay, double dilation, double cap) {
        return Math.clamp(1 + CATCH_UP_GAIN * (occupancy - delay), 1 - dilation, cap);
    }

    /**
     * Speed-capped traversal: drawn pose(R) is piecewise linear in R with slope stepMetres per
     * tick, so bounding this frame's R advance by cap·dt/50/maxStep bounds the drawn distance by
     * cap metres per tick. The max runs over every tick the frame's span touches — the resolver
     * draws R in (t-1, t] from record t's own pair, so the tick containing the landing point counts
     * even when no integer boundary is crossed. Teleport ticks report NaN and never clamp: real
     * teleports re-init R through sceneReset instead.
     */
    private double clampStep(double oldTime, double newTime, double dt) {
        var steps = stepMetres;
        if (steps == null || !(newTime > oldTime) || !(dt > 0)) {
            lastCappedStep = 0;
            return newTime;
        }
        double max = 0;
        for (long tick = (long) Math.floor(oldTime) + 1;
                tick <= (long) Math.ceil(newTime);
                tick++) {
            double step = steps.applyAsDouble(tick);
            if (!Double.isNaN(step) && step > max) max = step;
        }
        lastCappedStep = max > STEP_CAP_M_PER_TICK ? max : 0;
        if (max <= STEP_CAP_M_PER_TICK) return newTime;
        return Math.min(newTime, oldTime + STEP_CAP_M_PER_TICK * (dt / 50) / max);
    }

    /**
     * Client-side lag bound mirroring the server ticket age bound ({@code CombatViews}: min(40,
     * ⌈rtt/50⌉ + interpMax + 2)), with the jitter EMA in place of the RTT (stricter at high RTT).
     */
    long ageBound() {
        return Math.min(LAG_SNAP_TICKS, max + 2 + (long) Math.ceil(jitter / 50));
    }

    public boolean timedOut(double nowMs) {
        return nowMs > deadlineMs();
    }

    /** Last instant still driven: holdToSnapMs rounded up to whole 50 ms ticks after an arrival. */
    public double deadlineMs() {
        return Double.isNaN(lastArrival)
                ? Double.POSITIVE_INFINITY
                : lastArrival + Math.ceil(holdMs / 50.0) * 50;
    }

    /**
     * A fresh R clock for a restarted stream of the same connection: A rewarms but keeps its
     * high-water, so it never decreases within the connection (ADR4 §3.1).
     */
    public CombatSceneClock restarted() {
        var next = new CombatSceneClock();
        next.animation = rewarm(animation);
        return next;
    }

    private static AnimationTiming.ClockState rewarm(AnimationTiming.ClockState state) {
        return new AnimationTiming.ClockState(
                state.a(),
                state.highWater(),
                state.sampledNs(),
                1,
                state.rateSnapshot(),
                false,
                state.incidentId());
    }

    /**
     * Observer respawn/dimension: presentation restarts and A rewarms; A never decreases within the
     * connection, so the high-water survives (ADR4 §3.1).
     */
    public void sceneReset() {
        ticks.clear();
        time = Double.NaN;
        lastFrame = Double.NaN;
        recovery = false;
        gapRecovery = false;
        catchUp = false;
        catchUpBurst = false;
        lastCatchUpEndMs = Double.NEGATIVE_INFINITY;
        burstSeen = false;
        occPeak = 0;
        animation = rewarm(animation);
    }

    public double time() {
        return Math.floor(time * 65536) / 65536;
    }

    /** R quantized by floor to Q16 ticks. */
    public AnimationTypes.Time r() {
        long tick = (long) Math.floor(time);
        int alpha = (int) Math.floor((time - tick) * 65536);
        return new AnimationTypes.Time(tick, Math.min(alpha, 65535));
    }

    /** A, frozen for this frame; meaningful only while {@link #animationReady}. */
    public AnimationTypes.Time a() {
        return animation.a();
    }

    public boolean animationReady() {
        return animation.ready();
    }

    public AnimationTiming.ClockReason animationReason() {
        return animationReason;
    }

    public java.util.Map<AnimationTiming.ClockReason, Long> animationCounts() {
        return java.util.Map.copyOf(animationCounts);
    }

    public double rate() {
        return rate;
    }

    public int delay() {
        return delay;
    }

    public double jitter() {
        return jitter;
    }

    public long recoverySnaps() {
        return recoverySnaps;
    }

    public long delayChanges() {
        return delayChanges;
    }

    public double occupancy() {
        return high - time();
    }
}
