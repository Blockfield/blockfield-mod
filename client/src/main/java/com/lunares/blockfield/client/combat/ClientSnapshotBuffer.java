package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.AnimationEvent;
import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import com.lunares.blockfield.network.packet.SnapshotRestartC2SPacket;
import com.lunares.blockfield.network.packet.SnapshotRestartC2SPacket.Reason;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/**
 * Ordered atomic decode. Decode state follows the applied predecessor chain; presentation follows
 * native lifetime, whose Add/Remove/SetPassengers ride another RakNet order channel and may arrive
 * on either side of a batch (p3 r8 §2.5(2)).
 */
public final class ClientSnapshotBuffer {
    public static final int HISTORY = 40;
    // One second at 20 tps caps retained payloads even with the maximum 2 s hold setting.
    public static final int MAX_HELD_BATCHES = 20;
    // shortcut: assumes server far-rate farEvery <= 4 (default 2); raise with farEvery.
    static final int MAX_RATE_SKIP = 4;

    private record Held(PlayerSnapshotS2CPacket batch, double deadlineMs) {}

    private final Map<Long, Held> held = new HashMap<>();
    private double nextRestartMs = Double.NEGATIVE_INFINITY;

    private static final class Track {
        /** Last decoded exact record for this observer/entity. */
        SnapshotTransform base;

        long lastTick = -1;
        final NavigableMap<Long, SnapshotTransform> frames = new TreeMap<>();

        /** A native entity of {@link #nativeKind} holds this id. */
        boolean live;

        int nativeKind = -1;

        /** Live, but waiting for a subscription start or seat full it may adopt. */
        boolean waiting;

        /**
         * Live run that began after the native pairing's own start: possibly a newer pairing whose
         * Remove/Add are still in flight on the other channel.
         */
        boolean unclaimed;

        boolean seatCredit;
        boolean seatWaiting;

        /** Not live; frames begin at a subscription start that the next native Add may adopt. */
        boolean adoptable;
    }

    /** One record's pairing view on the exact stream. */
    private record Entry(int entityId, int kind, int flags) {}

    private final Map<Integer, Track> tracks = new HashMap<>();
    private final com.lunares.blockfield.common.combat.AnimationResources.Store resources =
            new com.lunares.blockfield.common.combat.AnimationResources.Store();

    private final java.util.Set<Long> resourceGenerations = new HashSet<>();

    void installResources(
            java.util.List<com.lunares.blockfield.common.combat.AnimationResources.Definition>
                    definitions) {
        resources.installAll(definitions);
        for (var definition : definitions)
            resourceGenerations.add(definition.ref().resourceGeneration());
    }

    void retireResources(
            java.util.Set<com.lunares.blockfield.common.combat.AnimationResources.ResourceRef>
                    refs) {
        try (var lease = resources.pin(java.util.List.copyOf(refs))) {
            var closure = new HashSet<>(refs);
            var pending = new java.util.ArrayDeque<>(refs);
            while (!pending.isEmpty())
                for (var dependency : resources.get(pending.removeFirst()).dependencies())
                    if (closure.add(dependency)) pending.add(dependency);
            var live =
                    new HashSet<>(
                            closure.stream()
                                    .map(
                                            com.lunares.blockfield.common.combat.AnimationResources
                                                            .ResourceRef
                                                    ::resourceGeneration)
                                    .toList());
            resources.retireUnpinned();
            resourceGenerations.retainAll(live);
        } catch (IllegalArgumentException unavailable) {
            // A held batch may still be carrying a required definition: retain until it applies.
        }
    }

    public com.lunares.blockfield.common.combat.AnimationResources.Store resources() {
        return resources;
    }

    private long epoch = -1;
    private long connectionId = -1;
    private long sceneGeneration = -1;
    private long high = -1;
    private boolean broken;
    private long defects;
    private Reason defectReason;
    private long defectEpoch;
    // Epochs are opaque; later recovery must still ignore every earlier retired stream.
    private final java.util.Set<Long> retiredEpochs = new HashSet<>();
    private boolean restartPending;
    private long expiredPending;
    private long entitySnaps;
    private long kindMismatches;

    /**
     * @param nativePresent whether the client level holds any entity with that id; such ids keep
     *     their decode baseline while the server may still delta against it.
     */
    public boolean apply(PlayerSnapshotS2CPacket batch, IntPredicate nativePresent) {
        return apply(batch, nativePresent, ClientCombatScene.nowMs(), applied -> {});
    }

    /** Decode only the contiguous predecessor chain; notify presentation in that same order. */
    public boolean apply(
            PlayerSnapshotS2CPacket batch,
            IntPredicate nativePresent,
            double nowMs,
            java.util.function.Consumer<PlayerSnapshotS2CPacket> applied) {
        if (retiredEpochs.contains(batch.observerStreamEpoch())) return false;
        if (expireHeld(nowMs)) return false;
        boolean changed = applyNext(batch, nativePresent, nowMs, applied);
        while (!broken) {
            var next = held.remove(high + 1);
            if (next == null) break;
            changed |= applyNext(next.batch(), nativePresent, nowMs, applied);
        }
        return changed;
    }

    private boolean applyNext(
            PlayerSnapshotS2CPacket batch,
            IntPredicate nativePresent,
            double nowMs,
            java.util.function.Consumer<PlayerSnapshotS2CPacket> applied) {
        if (retiredEpochs.contains(batch.observerStreamEpoch())) return false;
        boolean restarting = broken && restartPending;
        if (broken && !restarting) return false;
        Reason branch = Reason.STREAM_ORDER;
        try {
            if ((!restarting && epoch != -1 && batch.observerStreamEpoch() != epoch)
                    || batch.serverTick() <= high)
                throw new IllegalArgumentException("Unordered snapshot stream");
            branch = Reason.SETTINGS;
            if (batch.interpMin() < 1
                    || batch.interpMin() > 4
                    || batch.interpMax() < batch.interpMin()
                    || batch.interpMax() > 10
                    || batch.dilationPercent() < 5
                    || batch.dilationPercent() > 15
                    || batch.holdToSnapMs() < 200
                    || batch.holdToSnapMs() > 2000)
                throw new IllegalArgumentException("Unnormalized snapshot settings");
            branch = Reason.STREAM_ORDER;
            if (!held.isEmpty()
                    && held.values().iterator().next().batch().observerStreamEpoch()
                            != batch.observerStreamEpoch())
                throw new IllegalArgumentException("Conflicting held stream");
            long predecessor = batch.previousTickPlusOne();
            long expected = restarting ? 0 : high + 1;
            if (predecessor != expected) {
                if (predecessor < expected || predecessor > batch.serverTick())
                    throw new IllegalArgumentException("Invalid snapshot predecessor");
                if (held.containsKey(predecessor))
                    throw new IllegalArgumentException("Conflicting snapshot successor");
                if (held.size() >= MAX_HELD_BATCHES)
                    throw new IllegalArgumentException("Snapshot reorder capacity");
                held.put(predecessor, new Held(batch, nowMs + batch.holdToSnapMs()));
                return false;
            }
            var exact = batch.animation();
            branch = Reason.STREAM_ORDER;
            if (!restarting
                    && connectionId >= 0
                    && (exact.connectionId() != connectionId
                            || exact.sceneGeneration() != sceneGeneration))
                throw new IllegalArgumentException("Snapshot connection/scene changed");
            var entries = new ArrayList<Entry>();
            var decoded = new ArrayList<SnapshotTransform>();
            for (var record : exact.records()) {
                var identity = record.identity();
                var track = tracks.get(identity.entityId());
                var base = restarting || track == null ? null : track.base;
                branch = Reason.PAIRING_FULL;
                if (base == null
                        && (record.flags()
                                        & (AnimationRecipe.SnapshotRecord.FLAG_FULL
                                                | AnimationRecipe.SnapshotRecord.FLAG_RESET))
                                != (AnimationRecipe.SnapshotRecord.FLAG_FULL
                                        | AnimationRecipe.SnapshotRecord.FLAG_RESET))
                    throw new IllegalArgumentException("New pairing needs FULL|RESET");
                branch = Reason.DELTA_BASELINE;
                // Absolute lossless records; a continuing record still names its pairing.
                if ((record.flags() & AnimationRecipe.SnapshotRecord.FLAG_FULL) == 0
                        && base.kind() != identity.kind())
                    throw new IllegalArgumentException("Missing delta baseline");
                entries.add(new Entry(identity.entityId(), identity.kind(), record.flags()));
                decoded.add(
                        SnapshotTransform.exact(
                                batch.serverTick(),
                                identity,
                                record.flags(),
                                record.placement(),
                                record.checkpoint()));
            }
            long retainedTracks =
                    restarting
                            ? tracks.values().stream().filter(t -> t.live).count()
                            : tracks.size();
            long newTracks =
                    entries.stream()
                            .filter(
                                    r -> {
                                        var track = tracks.get(r.entityId());
                                        return track == null || restarting && !track.live;
                                    })
                            .count();
            branch = Reason.CAPACITY;
            if (retainedTracks + newTracks > PlayerSnapshotS2CPacket.MAX_RECORDS)
                throw new IllegalArgumentException("Snapshot pending capacity");
            if (exact != null) installResources(exact.definitions());
            if (restarting) {
                tracks.values().removeIf(track -> !track.live);
                for (var track : tracks.values()) {
                    track.base = null;
                    track.lastTick = -1;
                    track.frames.clear();
                    track.waiting = track.live;
                    track.unclaimed = false;
                    track.seatCredit = false;
                    track.seatWaiting = false;
                    track.adoptable = false;
                }
            }
            long previous = high;
            long tick = batch.serverTick();
            for (int i = 0; i < decoded.size(); i++) {
                var record = entries.get(i);
                var value = decoded.get(i);
                var track = tracks.computeIfAbsent(record.entityId(), id -> new Track());
                var old = track.base;
                long last = track.lastTick;
                // The server resends FULL after any gap but a pure far-rate skip: a DELTA closing
                // a short absence continues the run over held boundaries (server heldBoundary).
                boolean rateSkip =
                        old != null
                                && last >= 0
                                && last < previous
                                && tick - last <= MAX_RATE_SKIP
                                && (record.flags()
                                                & (AnimationRecipe.SnapshotRecord.FLAG_FULL
                                                        | AnimationRecipe.SnapshotRecord
                                                                .FLAG_RESET))
                                        == 0;
                // Every other absence ended the run.
                boolean start =
                        old == null
                                || last != previous && !rateSkip
                                || (record.flags() & AnimationRecipe.SnapshotRecord.FLAG_RESET)
                                        != 0;
                boolean boundary =
                        start
                                || old.epoch() != value.epoch()
                                || old.seat() != value.seat()
                                || old.teleport() != value.teleport()
                                || (record.flags()
                                                & (AnimationRecipe.SnapshotRecord.FLAG_SNAP
                                                        | AnimationRecipe.SnapshotRecord.FLAG_SEAT))
                                        != 0;
                track.base = value;
                track.lastTick = tick;
                if (!track.live) {
                    if (start) {
                        track.frames.clear();
                        track.adoptable = true;
                    }
                    if (track.adoptable) {
                        if (rateSkip && !boundary) holdSkipped(track, last, tick, value);
                        track.frames.put(tick, value);
                    }
                    continue;
                }
                if (value.kind() != track.nativeKind) {
                    track.frames.clear();
                    track.waiting = true;
                    kindMismatches++;
                    continue;
                }
                if (track.waiting) {
                    if (!boundary
                            && (record.flags() & AnimationRecipe.SnapshotRecord.FLAG_FULL) == 0)
                        continue;
                    track.seatCredit = start && value.seat() > 0 && !track.seatWaiting;
                    track.seatWaiting = false;
                    track.waiting = false;
                    track.unclaimed = false;
                    track.frames.clear();
                    entitySnaps++;
                } else if (boundary) {
                    track.frames.clear();
                    entitySnaps++;
                    if (start) track.unclaimed = true;
                    if ((record.flags() & AnimationRecipe.SnapshotRecord.FLAG_SEAT) != 0)
                        track.seatCredit = true;
                } else if (rateSkip) holdSkipped(track, last, tick, value);
                track.frames.put(tick, value);
            }
            epoch = batch.observerStreamEpoch();
            connectionId = exact.connectionId();
            sceneGeneration = exact.sceneGeneration();
            high = tick;
            broken = false;
            restartPending = false;
            if (restarting) nextRestartMs = Double.NEGATIVE_INFINITY;
            var iterator = tracks.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                var track = entry.getValue();
                // A short absence may be a far-rate skip the next DELTA certifies.
                if (track.live && tick - track.lastTick >= MAX_RATE_SKIP) track.frames.clear();
                boolean pending = !track.frames.isEmpty();
                track.frames.headMap(high - HISTORY + 1, false).clear();
                if (pending && track.frames.isEmpty()) expiredPending++;
                if (!track.live
                        && track.lastTick <= high - HISTORY
                        && !nativePresent.test(entry.getKey())) iterator.remove();
            }
            applied.accept(batch);
            return true;
        } catch (IllegalArgumentException | ArithmeticException ex) {
            fail(batch, branch, ex.getMessage());
            return false;
        }
    }

    private void fail(PlayerSnapshotS2CPacket batch, Reason branch, String detail) {
        fail(
                batch.observerStreamEpoch(),
                batch.serverTick(),
                batch.previousTickPlusOne(),
                batch.animation().records().size(),
                branch,
                detail);
    }

    private void fail(
            long stream, long tick, long previous, int records, Reason branch, String detail) {
        held.clear();
        broken = true;
        restartPending = false;
        defectReason = branch;
        defectEpoch = stream;
        if (defects++ == 0)
            LogUtils.getLogger()
                    .warn(
                            "Snapshot protocol defect: reason={} detail={} epoch={} batchTick={}"
                                    + " previousTickPlusOne={} high={} records={}",
                            branch,
                            detail,
                            defectEpoch,
                            tick,
                            previous,
                            high,
                            records);
    }

    /** A failed complete wire transaction enters the same rate-limited recovery as a bad batch. */
    public void wireDefect(long stream, String detail) {
        fail(stream, high, high + 1, 0, Reason.STREAM_ORDER, detail);
    }

    /** Polled independently of arrivals so an unfilled gap always gets a counted reason. */
    public boolean expireHeld(double nowMs) {
        for (var entry : held.values()) {
            if (nowMs > entry.deadlineMs()) {
                fail(entry.batch(), Reason.STREAM_ORDER, "Snapshot reorder timeout");
                return true;
            }
        }
        return false;
    }

    /** Native Add: adopt a pending subscription start of the audited kind, else wait for one. */
    public void add(int id, int kind) {
        add(id, kind, false);
    }

    void add(int id, int kind, boolean surviving) {
        var track = tracks.computeIfAbsent(id, key -> new Track());
        if (surviving && track.live && track.nativeKind == kind) return;
        var start = track.adoptable ? track.frames.firstEntry() : null;
        track.live = true;
        track.nativeKind = kind;
        track.adoptable = false;
        track.unclaimed = false;
        // The initial full may already contain a seat whose native linkage follows Add.
        track.seatCredit =
                start != null && start.getValue().kind() == kind && start.getValue().seat() > 0;
        track.seatWaiting = false;
        track.waiting = start == null || start.getValue().kind() != kind;
        if (track.waiting) track.frames.clear();
        else entitySnaps++;
    }

    /**
     * Native Remove of a live entity. Its current run is dropped unless it began after that
     * pairing's start: then the matching Add is still behind this Remove on the native channel.
     * Decode state stays; vanilla's own addEntity also issues a Remove for absent ids.
     */
    public void remove(int id) {
        var track = tracks.get(id);
        if (track == null || !track.live) return;
        track.adoptable = track.unclaimed && !track.frames.isEmpty();
        if (!track.adoptable) track.frames.clear();
        track.live = false;
        track.nativeKind = -1;
        track.waiting = false;
        track.unclaimed = false;
        track.seatCredit = false;
        track.seatWaiting = false;
    }

    /** Native seat linkage changed; a seat full that already arrived pays for it. */
    public void seatChanged(int id) {
        var track = tracks.get(id);
        if (track == null || !track.live) return;
        if (track.seatCredit) track.seatCredit = false;
        else {
            track.seatWaiting = true;
            track.waiting = true;
            track.frames.clear();
        }
    }

    /** Latest decoded generation presentable by the live native entity of the audited kind. */
    public boolean paired(com.lunares.blockfield.common.combat.AnimationState.Identity identity) {
        var track = tracks.get(identity.entityId());
        // A live RESET need not have a native Remove/Add; unclaimed only retains its newer run.
        return !broken
                && track != null
                && track.live
                && !track.waiting
                && track.base != null
                && track.base.identity().sameGeneration(identity);
    }

    java.util.Map<Integer, Object> debugPairing() {
        var out = new java.util.LinkedHashMap<Integer, Object>();
        tracks.forEach(
                (id, track) -> {
                    var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("live", track.live);
                    row.put("waiting", track.waiting);
                    row.put("unclaimed", track.unclaimed);
                    row.put("adoptable", track.adoptable);
                    row.put("nativeKind", track.nativeKind);
                    row.put("identity", track.base == null ? null : track.base.identity());
                    out.put(id, row);
                });
        return out;
    }

    boolean hasUnclaimedRun(int id) {
        var track = tracks.get(id);
        return track != null && track.unclaimed;
    }

    public boolean hasLiveSamples(int id) {
        var track = tracks.get(id);
        return !broken && track != null && track.live && !track.frames.isEmpty();
    }

    /**
     * Fills the certified far-rate skips {@code last+1 .. tick-1} with the last sent frame held at
     * each tick, as the server replays them: a true skip contributes no new input.
     */
    private static void holdSkipped(Track track, long last, long tick, SnapshotTransform value) {
        var cover = track.frames.get(last);
        if (cover == null) return;
        for (long n = last + 1; n < tick; n++)
            track.frames.put(
                    n,
                    SnapshotTransform.exact(
                            n,
                            value.identity(),
                            0,
                            cover.placement(),
                            cover.checkpoint() == null ? null : heldAt(cover.checkpoint(), n)));
    }

    /**
     * Mirror of the server's {@code AnimSyncStream.heldAt}: the cover's pose re-stamped at the
     * skipped tick, its decisions (and their input refs) re-stamped too, since the reducer requires
     * every decision of boundary n to name n. Must stay byte-identical to the server's replay.
     */
    static AnimationState heldAt(AnimationState cover, long skipTick) {
        var decisions = new ArrayList<AnimationEvent.Decision>(cover.decisions().size());
        for (var decision : cover.decisions()) {
            var id = decision.id();
            var input = decision.input();
            decisions.add(
                    new AnimationEvent.Decision(
                            new AnimationEvent.EventId(
                                    skipTick, id.eventSequence(), id.nativeStage()),
                            decision.layer(),
                            decision.startKind(),
                            decision.outcome(),
                            decision.predecessorEventSequence(),
                            decision.predecessor(),
                            new AnimationResources.InputRef(
                                    input.resourceGeneration(),
                                    input.targetEpoch(),
                                    skipTick,
                                    input.dependencyDigest()),
                            decision.patch(),
                            decision.transitionOrdinal()));
        }
        return new AnimationState(
                cover.identity(),
                skipTick,
                cover.sampleRevision(),
                cover.eventWatermark(),
                cover.placement(),
                cover.vanilla(),
                cover.stance(),
                cover.equipment(),
                cover.tacz(),
                cover.lr(),
                cover.graph(),
                cover.parcool(),
                cover.create(),
                cover.sbw(),
                decisions);
    }

    /** Live decoded frames of one entity by tick (read-only), or an empty map. */
    public java.util.NavigableMap<Long, SnapshotTransform> frames(int id) {
        var track = tracks.get(id);
        return track == null || !track.live || broken
                ? java.util.Collections.emptyNavigableMap()
                : java.util.Collections.unmodifiableNavigableMap(track.frames);
    }

    /** ADR4 connection identity of the applied stream; -1 before the first exact batch. */
    void references(
            java.util.Set<com.lunares.blockfield.common.combat.AnimationResources.ResourceRef> refs,
            java.util.Set<Long> generations) {
        for (var track : tracks.values()) {
            var retained = new ArrayList<>(track.frames.values());
            if (track.base != null) retained.add(track.base);
            for (var record : retained) {
                generations.add(record.identity().resourceGeneration());
                ClientAnimationAssets.references(record.checkpoint(), refs);
                ClientAnimationAssets.references(record.placement(), refs);
            }
        }
        for (var pending : held.values()) {
            var batch = pending.batch().animation();
            ClientAnimationAssets.references(batch, refs);
            for (var record : batch.records())
                generations.add(record.identity().resourceGeneration());
        }
    }

    public long connectionId() {
        return connectionId;
    }

    public long sceneGeneration() {
        return sceneGeneration;
    }

    /** Observer respawn/dimension: presentation restarts; exact native survivors keep guards. */
    public void sceneReset() {
        sceneReset(id -> false);
    }

    void sceneReset(IntPredicate surviving) {
        for (var entry : tracks.entrySet()) {
            var track = entry.getValue();
            track.frames.clear();
            // Preserve both a pending full requirement and credit for a full before native linkage.
            if (track.live && surviving.test(entry.getKey())) continue;
            track.live = false;
            track.nativeKind = -1;
            track.waiting = false;
            track.unclaimed = false;
            track.seatCredit = false;
            track.seatWaiting = false;
            track.adoptable = false;
        }
    }

    public long high() {
        return high;
    }

    public long epoch() {
        return epoch;
    }

    public boolean broken() {
        return broken;
    }

    public long defects() {
        return defects;
    }

    public String defectReason() {
        return defectReason == null ? "" : defectReason.name();
    }

    /** Retry rejected/lost requests slowly; a successful fresh prefix re-arms recovery. */
    public SnapshotRestartC2SPacket restartRequest() {
        return restartRequest(ClientCombatScene.nowMs());
    }

    public SnapshotRestartC2SPacket restartRequest(double nowMs) {
        if (!broken || nowMs < nextRestartMs) return null;
        nextRestartMs = nowMs + SnapshotRestartC2SPacket.MIN_RESTART_INTERVAL_MS;
        restartPending = true;
        retiredEpochs.add(defectEpoch);
        return new SnapshotRestartC2SPacket(defectEpoch, defectReason);
    }

    public long expiredPending() {
        return expiredPending;
    }

    public long entitySnaps() {
        return entitySnaps;
    }

    public long kindMismatches() {
        return kindMismatches;
    }

    public int size() {
        return tracks.size();
    }
}
