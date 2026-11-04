package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationTypes.*;

import java.math.BigInteger;
import java.util.*;

/** Pure clock, endpoint and causal-bound rules. Native callback scheduling belongs to U8. */
public final class AnimationTiming {
    private AnimationTiming() {}

    public enum ClockReason {
        ADVANCE,
        WARMUP,
        HOLD,
        BACKLOG_SNAP,
        SILENCE_SNAP,
        GAP_SNAP,
        REWARM,
        TIMEOUT
    }

    public record ClockState(
            Time a,
            Time highWater,
            long sampledNs,
            double rate,
            long rateSnapshot,
            boolean ready,
            long incidentId) {
        public static ClockState cold() {
            return new ClockState(Time.whole(0), Time.whole(0), 0, 1, -1, false, 0);
        }
    }

    public record Frame(
            Time r,
            long h,
            long nowNs,
            double jitterMs,
            double delayR,
            double dilation,
            long snapshotTick,
            boolean servoSlot,
            boolean silenceRecovery,
            boolean gapSnap,
            boolean backlogSnap,
            boolean timeout,
            long incidentId) {
        public Frame {
            Objects.requireNonNull(r);
            AnimationSchema.nonnegative(h, "H");
            AnimationSchema.nonnegative(snapshotTick, "snapshot tick");
            if (!Double.isFinite(jitterMs)
                    || jitterMs < 0
                    || !Double.isFinite(delayR)
                    || delayR < 0
                    || !Double.isFinite(dilation)
                    || dilation < 0
                    || dilation > 1) throw new IllegalArgumentException("Invalid clock settings");
        }
    }

    public record ClockStep(
            ClockState state, ClockReason reason, long elapsedNs, boolean ticketsEnabled) {}

    public static double offset(double jitterMs, double delayR) {
        return Math.clamp(.5 + 3 * jitterMs / 50, .5, Math.max(.5, delayR - .5));
    }

    public static double rate(double occupancy, double target, double dilation) {
        double error = occupancy - target;
        return Math.abs(error) <= .25
                ? 1
                : 1 + Math.copySign(Math.min(dilation, .05 * Math.abs(error)), error);
    }

    public static ClockStep advance(ClockState previous, Frame frame) {
        long elapsed =
                previous.ready()
                        ? Math.max(0, Math.subtractExact(frame.nowNs(), previous.sampledNs()))
                        : 0;
        Time h = Time.whole(frame.h());
        if (frame.timeout())
            return new ClockStep(
                    new ClockState(
                            previous.a(),
                            previous.highWater(),
                            frame.nowNs(),
                            previous.rate(),
                            previous.rateSnapshot(),
                            false,
                            frame.incidentId()),
                    ClockReason.TIMEOUT,
                    elapsed,
                    false);
        if (frame.r().compareTo(h) > 0 || previous.highWater().compareTo(h) > 0)
            return new ClockStep(
                    new ClockState(
                            previous.a(),
                            previous.highWater(),
                            frame.nowNs(),
                            previous.rate(),
                            previous.rateSnapshot(),
                            false,
                            frame.incidentId()),
                    ClockReason.REWARM,
                    elapsed,
                    false);
        double offset = offset(frame.jitterMs(), frame.delayR());
        double selectedRate = previous.rate();
        long rateTick = previous.rateSnapshot();
        if (frame.servoSlot() && frame.snapshotTick() > rateTick) {
            selectedRate = rate(h.difference(previous.a()), offset, frame.dilation());
            rateTick = frame.snapshotTick();
        }
        Time value;
        ClockReason reason;
        if (!previous.ready()) {
            value = Time.max(previous.highWater(), Time.max(frame.r(), subtract(h, offset)));
            reason = ClockReason.WARMUP;
            selectedRate = 1;
        } else if (frame.silenceRecovery() || frame.gapSnap() || frame.backlogSnap()) {
            // R snapped on this frame (silence hold end, data gap, or lag beyond the catch-up
            // bound); A shares the incident once instead of tracking the jump. While R catches
            // up smoothly no flag is set and A glides: value below is still floored by R.
            value = Time.max(previous.a(), Time.max(frame.r(), subtract(h, offset)));
            reason =
                    frame.silenceRecovery()
                            ? ClockReason.SILENCE_SNAP
                            : frame.gapSnap() ? ClockReason.GAP_SNAP : ClockReason.BACKLOG_SNAP;
        } else {
            double delta = selectedRate * (elapsed / 50_000_000.0);
            Time advanced = delta >= h.difference(previous.a()) ? h : add(previous.a(), delta);
            value = Time.max(frame.r(), advanced);
            value = Time.max(previous.highWater(), value);
            reason = value.compareTo(previous.a()) == 0 ? ClockReason.HOLD : ClockReason.ADVANCE;
        }
        return new ClockStep(
                new ClockState(
                        value,
                        Time.max(previous.highWater(), value),
                        frame.nowNs(),
                        selectedRate,
                        rateTick,
                        true,
                        frame.incidentId()),
                reason,
                elapsed,
                true);
    }

    /** Quantize a relative increment, avoiding conversion of an absolute long tick to double. */
    public static Time add(Time time, double delta) {
        if (!Double.isFinite(delta) || delta < 0 || delta >= 0x1p63)
            throw new IllegalArgumentException("Invalid clock increment");
        long whole = (long) Math.floor(delta);
        double fraction = (delta - whole) + time.alpha() / 65536.0;
        int alpha = (int) Math.floor(fraction * 65536);
        if (alpha >= 65536) {
            whole = Math.addExact(whole, 1);
            alpha -= 65536;
        }
        return new Time(Math.addExact(time.tick(), whole), alpha);
    }

    private static Time subtract(Time time, double delta) {
        if (!Double.isFinite(delta) || delta < 0 || delta >= 0x1p63)
            throw new IllegalArgumentException("Invalid clock offset");
        long whole = (long) Math.floor(delta);
        double fraction = time.alpha() / 65536.0 - (delta - whole);
        if (fraction < 0) {
            whole = Math.addExact(whole, 1);
            fraction += 1;
        }
        if (whole > time.tick()) return Time.whole(0);
        return new Time(time.tick() - whole, (int) Math.floor(fraction * 65536));
    }

    public record Endpoint(long tick, float partial) {}

    public static Endpoint endpoint(Time time) {
        return time.alpha() == 0
                ? new Endpoint(time.tick(), 1)
                : new Endpoint(Math.addExact(time.tick(), 1), time.fraction());
    }

    public record Probe(long id, long sentNs, long receivedNs, long completedTick) {
        public Probe {
            AnimationSchema.nonnegative(id, "probe id");
            AnimationSchema.nonnegative(completedTick, "probe tick");
            if (receivedNs < sentNs) throw new IllegalArgumentException("Invalid probe RTT");
        }

        public long rtt() {
            return Math.subtractExact(receivedNs, sentNs);
        }
    }

    public record FrozenDeadline(
            long broadcastNs,
            long networkDeadlineNs,
            long rttHi,
            long jitterHi,
            List<Probe> probes) {
        public FrozenDeadline {
            probes = List.copyOf(probes);
        }
    }

    public static Optional<FrozenDeadline> freeze(
            long broadcastNs, long serverTick, List<Probe> completed) {
        List<Probe> probes =
                completed.stream()
                        .filter(
                                p ->
                                        p.receivedNs() <= broadcastNs
                                                && p.completedTick() <= serverTick
                                                && serverTick - p.completedTick() <= 40)
                        .sorted(
                                Comparator.comparingLong(Probe::receivedNs)
                                        .thenComparingLong(Probe::id))
                        .toList();
        if (probes.isEmpty()) return Optional.empty();
        probes = probes.subList(Math.max(0, probes.size() - 32), probes.size());
        long hi = 0, jitter = 0, previous = 0;
        boolean first = true;
        Set<Long> ids = new HashSet<>();
        for (Probe probe : probes) {
            if (!ids.add(probe.id()))
                throw new IllegalArgumentException("Duplicate probe identity");
            long rtt = probe.rtt();
            hi = Math.max(hi, rtt);
            if (!first) jitter = Math.max(jitter, Math.abs(Math.subtractExact(rtt, previous)));
            first = false;
            previous = rtt;
        }
        long deadline = Math.addExact(broadcastNs, Math.addExact(hi, jitter));
        return Optional.of(new FrozenDeadline(broadcastNs, deadline, hi, jitter, probes));
    }

    public record Boundary(long tick, long nanoTime) {}

    /**
     * Recorded monotonic boundaries, including non-50ms ticks. Never extrapolates a future
     * boundary.
     */
    public static final class Boundaries {
        private final List<Boundary> values;

        public Boundaries(List<Boundary> values) {
            this.values = List.copyOf(values);
            for (int i = 0; i < values.size(); i++) {
                AnimationSchema.nonnegative(values.get(i).tick(), "boundary tick");
                if (i > 0
                        && (values.get(i).tick() != Math.addExact(values.get(i - 1).tick(), 1)
                                || values.get(i).nanoTime() <= values.get(i - 1).nanoTime()))
                    throw new IllegalArgumentException("Invalid boundary map");
            }
        }

        public Optional<Boundary> last() {
            return values.isEmpty() ? Optional.empty() : Optional.of(values.getLast());
        }

        public Optional<Time> at(long ns) {
            if (values.isEmpty()
                    || ns < values.getFirst().nanoTime()
                    || ns > values.getLast().nanoTime()) return Optional.empty();
            for (int i = 0; i < values.size(); i++) {
                Boundary b = values.get(i);
                if (ns == b.nanoTime()) return Optional.of(Time.whole(b.tick()));
                if (i + 1 < values.size() && ns < values.get(i + 1).nanoTime()) {
                    long elapsed = Math.subtractExact(ns, b.nanoTime()),
                            span = Math.subtractExact(values.get(i + 1).nanoTime(), b.nanoTime());
                    int alpha =
                            BigInteger.valueOf(elapsed)
                                    .shiftLeft(16)
                                    .divide(BigInteger.valueOf(span))
                                    .intValueExact();
                    return Optional.of(new Time(b.tick(), alpha));
                }
            }
            return Optional.empty();
        }
    }

    public record Mapping(long id, long clientOriginNs, long serverLowerNs, long serverUpperNs) {
        public Mapping {
            AnimationSchema.nonnegative(id, "mapping id");
            if (serverLowerNs > serverUpperNs)
                throw new IllegalArgumentException("Empty clock mapping");
        }

        public static Mapping fromProbe(
                long id,
                long serverSendNs,
                long serverReceiveNs,
                long clientReceiveNs,
                long clientSendNs) {
            long clientElapsed = Math.subtractExact(clientSendNs, clientReceiveNs);
            if (clientElapsed < 0) throw new IllegalArgumentException("Negative echo elapsed");
            return new Mapping(
                    id,
                    clientReceiveNs,
                    serverSendNs,
                    Math.subtractExact(serverReceiveNs, clientElapsed));
        }

        public Optional<Interval> at(
                long clientApplyNs, long acceptanceNs, long firstReportNs, Boundaries map) {
            long elapsed = Math.subtractExact(clientApplyNs, clientOriginNs);
            if (elapsed < 0) return Optional.empty();
            long low = Math.max(acceptanceNs, Math.addExact(serverLowerNs, elapsed));
            long high = Math.min(firstReportNs, Math.addExact(serverUpperNs, elapsed));
            if (low > high) return Optional.empty();
            Optional<Time> a = map.at(low), b = map.at(high);
            return a.isEmpty() || b.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new Interval(a.get(), b.get()));
        }
    }

    public record Interval(Time lower, Time upper) {
        public Interval {
            if (lower.compareTo(upper) > 0)
                throw new IllegalArgumentException("Empty causal interval");
        }

        public boolean contains(Time value) {
            return value.compareTo(lower) >= 0 && value.compareTo(upper) <= 0;
        }

        public Optional<Time> choose(Time acceptance) {
            Time result = Time.max(lower, acceptance);
            return result.compareTo(upper) <= 0 ? Optional.of(result) : Optional.empty();
        }
    }

    public record StartBound(
            Time acceptance, Time upper, long networkDeadlineNs, long firstReportNs) {
        public boolean contains(Time start, Interval mapping) {
            return start.compareTo(acceptance) >= 0
                    && start.compareTo(upper) <= 0
                    && mapping.contains(start);
        }
    }

    public static Optional<StartBound> bound(
            Time acceptance,
            FrozenDeadline deadline,
            long firstReportNs,
            Boundaries map,
            StartBound previous) {
        if (firstReportNs < deadline.broadcastNs()) return Optional.empty();
        Optional<Time> known = map.at(Math.min(deadline.networkDeadlineNs(), firstReportNs));
        if (known.isEmpty()) return Optional.empty();
        Time upper = Time.min(known.get(), acceptance.plusWhole(40));
        if (previous != null) {
            if (!previous.acceptance().equals(acceptance)
                    || previous.networkDeadlineNs() != deadline.networkDeadlineNs())
                throw new IllegalArgumentException("Changed immutable start bound");
            upper = Time.min(previous.upper(), upper);
            firstReportNs = Math.min(previous.firstReportNs(), firstReportNs);
        }
        if (upper.compareTo(acceptance) < 0) return Optional.empty();
        return Optional.of(
                new StartBound(acceptance, upper, deadline.networkDeadlineNs(), firstReportNs));
    }
}
