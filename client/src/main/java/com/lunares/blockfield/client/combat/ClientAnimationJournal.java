package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.AnimationEvent;
import com.lunares.blockfield.common.combat.AnimationEvent.ObserverSeed;
import com.lunares.blockfield.common.combat.AnimationResolver;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationStartReport;
import com.lunares.blockfield.common.combat.AnimationStartReport.Application;
import com.lunares.blockfield.common.combat.AnimationStartReport.ObserverApply;
import com.lunares.blockfield.common.combat.AnimationStartReport.Start;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTypes.AnchorClass;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain;
import com.lunares.blockfield.common.combat.AnimationTypes.StartKind;
import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.common.combat.ObserverReducer;
import com.lunares.blockfield.common.combat.PaGraph;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * ADR4 §§2.2/3.2–3.6 observer journal (U8): server seeds, causally ordered capsules, their
 * OBSERVER_APPLY applications and one running {@link ObserverReducer.Reduction} per subscribed
 * target life, plus the probe evidence that maps application times to server tick-time.
 *
 * <p>A capsule is applied in the main-thread task that receives it and stamped with the A of the
 * frame that first presents it (the active frame, or the next main-frame entry with zero client
 * ticks in between); it is never delayed until A or H reaches its event tick. The server replays
 * exactly these applications with the same reducer, so presentation and receipt rows come from one
 * value. Nothing here reads a live entity. Main-thread owned.
 */
public final class ClientAnimationJournal {
    /** Bound on held/orphan capsules per life: transport causality, never a truncated report. */
    static final int MAX_HELD = 64;

    /** ADR4 §3.5: the server keeps 40 sent ticks, so older seeds/applications cannot validate. */
    static final int WINDOW = 40;

    private static final byte[] UNWRAPPED = "native.unwrapped".getBytes(StandardCharsets.UTF_8);

    /** ParCool's native batch carrier and its fellow entries (dispatched once per delivery). */
    static final byte[] PARCOOL_BATCH = "native.parcool.batch".getBytes(StandardCharsets.UTF_8);

    private static final byte[] PARCOOL_BATCHED =
            "native.parcool.batched".getBytes(StandardCharsets.UTF_8);

    /** The frame/revision a capsule or seed is applied in, with its frozen clocks. */
    public record Stamp(long frameId, long revision, Time r, Time a, long h, long nowNs) {}

    /** The latest probe: the server send time of nonce {@code id} and when it was applied here. */
    private record Probe(long id, long serverSendNs, long clientNs) {}

    /** A capsule and, once released, its release mapping. */
    private record Pending(AnimationEvent event, long receivedNs, Received release) {}

    /** A mapping nonce and the lower mapped server instant under it (MIN_VALUE: unmapped). */
    private record Received(long mappingId, long serverNs) {}

    /**
     * An applied OBSERVER_APPLY proof whose sW still waits for the server boundary after {@code
     * received}; {@code provisional} (sW = A) is presented but never sent.
     */
    private record Unfinished(ObserverApply provisional, Received received, long acceptedNs) {}

    /** One subscribed target life (entity, target epoch, subscription incarnation). */
    static final class Life {
        final int entityId;
        final long targetEpoch;
        final long subscription;
        ObserverSeed seed, pendingSeed, pendingFold;
        // Causal receipt of the pending seed and fold: their activation sW, as release is a
        // capsule's. A only chooses the frame that presents them.
        Received pendingSeedTime, pendingFoldTime;
        Start activation;
        boolean activationFinal;
        // Proofs in application order awaiting exact conversion, and each finished provisional
        // proof's sent value (the reduction keeps presenting the provisional one).
        final ArrayDeque<Unfinished> unfinished = new ArrayDeque<>();
        final Map<ObserverApply, ObserverApply> finals = new HashMap<>();
        long reportedBucket = Long.MIN_VALUE;
        ObserverReducer.Reduction reduction;
        // Applications after the active seed's prefix, in application order; rows sort by sequence.
        final List<ObserverReducer.Applied> applied = new ArrayList<>();
        final List<Application> applications = new ArrayList<>();
        // Last applied capsule (its successor names it), or 0 right after a seed.
        long lastCapsule;
        // Highest prefix a row named: the server rejects a later lower one, and an ended canonical
        // start no longer raises it.
        long prefixHigh;
        final TreeMap<Long, Pending> held = new TreeMap<>();
        final ArrayDeque<Pending> ready = new ArrayDeque<>();
        // Presentation/report labels; a null value is available.
        String presentation = "seed_pending";
        String report;

        Life(int entityId, long targetEpoch, long subscription) {
            this.entityId = entityId;
            this.targetEpoch = targetEpoch;
            this.subscription = subscription;
        }

        boolean names(AnimationState.Identity identity) {
            return identity.entityId() == entityId
                    && identity.targetEpoch() == targetEpoch
                    && identity.subscriptionGeneration() == subscription;
        }

        /** Highest event the presentation has folded: seed prefix, activation or a capsule. */
        long appliedPrefix() {
            long prefix = seed.eventPrefix();
            if (activation != null) prefix = Math.max(prefix, activation.eventSequence());
            for (Application application : applications)
                prefix = Math.max(prefix, application.eventSequence());
            return prefix;
        }
    }

    private final java.util.function.Predicate<AnimationState.Identity> presentable;
    private final java.util.function.Predicate<AnimationEvent> delivery;

    /** Pure journal fixtures supply their own native boundary; production supplies the pairing. */
    public ClientAnimationJournal() {
        this(identity -> true, event -> true);
    }

    ClientAnimationJournal(
            java.util.function.Predicate<AnimationState.Identity> presentable,
            java.util.function.Predicate<AnimationEvent> delivery) {
        this.presentable = presentable;
        this.delivery = delivery;
    }

    private final Map<Integer, Life> lives = new HashMap<>();
    private final TreeMap<Long, Long> boundaries = new TreeMap<>();
    private Probe probe;

    /**
     * One life's AnimationApplied content of a revision: its seed, that seed's activation (on
     * activation and every {@link #HEARTBEAT} buckets, so the server learns A and can fold) and the
     * applications stamped since the last report.
     */
    public record Report(
            AnimationState.Identity identity,
            long seedRef,
            Start activation,
            List<Application> applications) {}

    /** Buckets between activation heartbeats; the server folds seeds older than 20 ticks. */
    static final int HEARTBEAT = 10;

    // Stamped applications and activations awaiting the AnimationApplied report of the revision.
    private record ReportKey(AnimationState.Identity identity, long seedRef) {}

    private final Map<ReportKey, Report> unreported = new LinkedHashMap<>();
    private final Map<String, Long> counters = new TreeMap<>();

    // --- Timing evidence (ADR4 §3.2) ---

    /** Applies one probe: keeps its nonce mapping and the consecutive server tick boundaries. */
    public void probe(long id, long serverSendNs, long boundaryTick, long boundaryNs, long nowNs) {
        probe = new Probe(id, serverSendNs, nowNs);
        var last = boundaries.lastEntry();
        if (last != null
                && (boundaryTick != last.getKey() + 1 || boundaryNs <= last.getValue())
                && !(boundaryTick == last.getKey() && boundaryNs == last.getValue()))
            boundaries.clear();
        boundaries.put(boundaryTick, boundaryNs);
        while (boundaries.size() > 200) boundaries.pollFirstEntry();
    }

    /**
     * Server tick-time of a server monotonic instant: exact between two recorded boundaries (the
     * server's own map), else extrapolated past the last one at the fixed 20 ticks/s slope. Null
     * before the first boundary or before the recorded map.
     */
    Time tickTime(long serverNs) {
        Map.Entry<Long, Long> lower = null;
        for (var entry : boundaries.entrySet()) {
            if (entry.getValue() > serverNs) break;
            lower = entry;
        }
        if (lower == null) return null;
        var upper = boundaries.higherEntry(lower.getKey());
        long span = upper == null ? 50_000_000L : upper.getValue() - lower.getValue();
        long elapsed = serverNs - lower.getValue();
        long whole = elapsed / span;
        int alpha =
                BigInteger.valueOf(elapsed % span)
                        .shiftLeft(16)
                        .divide(BigInteger.valueOf(span))
                        .intValueExact();
        return new Time(Math.addExact(lower.getKey(), whole), alpha);
    }

    /**
     * ADR4 §3.2 causal coordinate of a lower mapped server instant: intersected with the event's
     * acceptance lower bound ({@code MIN_VALUE}: none), i.e. the lower endpoint of the admissible
     * interval. Exact only once {@link #coverage} of both instants is 1.
     */
    Time causal(long serverNs, long acceptedNs) {
        Time lower = tickTime(serverNs);
        return acceptedNs == Long.MIN_VALUE ? lower : Time.max(lower, tickTime(acceptedNs));
    }

    /**
     * 1: a recorded consecutive boundary pair contains {@code serverNs} (the server's own map); 0:
     * the boundary after it is not recorded yet (a 50 ms extrapolation would misplace it inside a
     * bound only a few ms wide); -1: the recorded history no longer reaches back to it.
     */
    private int coverage(long serverNs) {
        if (serverNs == Long.MIN_VALUE) return 1;
        if (boundaries.isEmpty() || boundaries.firstEntry().getValue() > serverNs) return -1;
        return boundaries.lastEntry().getValue() > serverNs ? 1 : 0;
    }

    /** The latest probe's nonce and lower mapped server instant of client time {@code nowNs}. */
    private Received received(long nowNs) {
        return probe == null || nowNs < probe.clientNs()
                ? new Received(0, Long.MIN_VALUE)
                : new Received(
                        probe.id(), Math.addExact(probe.serverSendNs(), nowNs - probe.clientNs()));
    }

    // --- Seeds and capsules ---

    /**
     * A server seed: from a FULL record (activates once A reaches its tick), or a fold naming the
     * active seed as predecessor (adopted when it reproduces the running presentation).
     */
    public void seed(ObserverSeed seed, long nowNs) {
        var received = received(nowNs);
        var identity = seed.identity();
        var life = lives.get(identity.entityId());
        if (seed.predecessor() != null) {
            if (life == null
                    || !life.names(identity)
                    || life.seed == null
                    || !seed.predecessor().equals(life.seed.ref())) {
                count("foldIgnored");
                return;
            }
            if (life.pendingFold == null
                    || life.pendingFold.ref().contentId() < seed.ref().contentId()) {
                life.pendingFold = seed;
                life.pendingFoldTime = received;
            }
            count("folds");
            return;
        }
        if (life == null || !life.names(identity)) {
            life =
                    new Life(
                            identity.entityId(),
                            identity.targetEpoch(),
                            identity.subscriptionGeneration());
            var previous = lives.put(identity.entityId(), life);
            if (previous != null) adoptOrphans(previous, life);
        }
        if (life.pendingSeed == null
                || life.pendingSeed.ref().contentId() < seed.ref().contentId()) {
            life.pendingSeed = seed;
            life.pendingSeedTime = received;
        }
        count("seeds");
    }

    /** A pairing ended (Remove/unsubscribe/new life): its presentation and pending state go. */
    public void forget(int entityId) {
        lives.remove(entityId);
        unreported.keySet().removeIf(key -> key.identity().entityId() == entityId);
    }

    /**
     * One native logical delivery's capsule, applied in this main-thread task. Returns false when
     * it waits (unknown life, missing predecessor or a newer seed still pending).
     */
    public boolean capsule(AnimationEvent event, long nowNs) {
        var identity = event.identity();
        var life = lives.get(identity.entityId());
        if (life == null || !life.names(identity)) {
            // The seed of this incarnation is still in flight: hold, bounded and labelled.
            if (life == null) {
                life =
                        new Life(
                                identity.entityId(),
                                identity.targetEpoch(),
                                identity.subscriptionGeneration());
                lives.put(identity.entityId(), life);
            } else if (identity.targetEpoch() < life.targetEpoch
                    || identity.targetEpoch() == life.targetEpoch
                            && identity.subscriptionGeneration() < life.subscription) {
                count("capsuleStale");
                return false;
            } else {
                var orphan =
                        new Life(
                                identity.entityId(),
                                identity.targetEpoch(),
                                identity.subscriptionGeneration());
                adoptOrphans(life, orphan);
                lives.put(identity.entityId(), orphan);
                life = orphan;
            }
        }
        if (!unwrapped(event)) {
            // Known natives: an unwrapped packet runs its side effects once on its own; ParCool's
            // batch and TaCZ/LR carried broadcasts were dispatched once by the delivery that
            // carried them. Anything else would need a dispatcher this client lacks.
            life.presentation = "native_payload_unavailable";
            count("capsulePayloadUnavailable");
            return false;
        }
        long sequence = event.id().eventSequence();
        if (life.held.containsKey(event.predecessorEventSequence())
                || sequence <= life.lastCapsule
                || life.ready.stream().anyMatch(p -> p.event().id().eventSequence() == sequence)
                || life.held.values().stream()
                        .anyMatch(p -> p.event().id().eventSequence() == sequence)) {
            count("capsuleDuplicate");
            return false;
        }
        if (life.held.size() >= MAX_HELD) {
            life.presentation = "capsule_capacity";
            count("capsuleCapacity");
            return false;
        }
        life.held.put(event.predecessorEventSequence(), new Pending(event, nowNs, null));
        release(life, nowNs);
        return true;
    }

    /** Moves causally ready capsules (each naming the last applied one) to the ready queue. */
    private void release(Life life, long nowNs) {
        if (life.seed == null || life.pendingSeed != null) return;
        while (true) {
            long last = life.lastCapsule;
            var next = life.held.get(last);
            long key = last;
            if (next == null && last <= life.seed.eventPrefix()) {
                var first = life.held.headMap(life.seed.eventPrefix(), true).firstEntry();
                if (first != null) {
                    key = first.getKey();
                    next = first.getValue();
                }
            }
            if (next == null) return;
            long sequence = next.event().id().eventSequence();
            if (!life.seed.identity().sameGeneration(next.event().identity())
                    || !presentable.test(next.event().identity())) {
                life.presentation = "not_presentable";
                return;
            }
            if (!delivery.test(next.event())) {
                life.presentation = "native_payload_unavailable";
                return;
            }
            life.held.remove(key);
            life.presentation = null;
            // A seed can absorb geometry before its native carrier becomes presentable.
            // Deliver its side effects once, but never replay an already-folded transition.
            if (sequence <= life.seed.eventPrefix()) continue;
            life.lastCapsule = sequence;
            // Receipt may predate a newer probe. Keep the actual release under the probe held
            // then; later probes and the first showing frame cannot remap this coordinate.
            life.ready.add(new Pending(next.event(), next.receivedNs(), received(nowNs)));
        }
    }

    private static void adoptOrphans(Life from, Life to) {
        for (var pending : from.held.values()) {
            var identity = pending.event().identity();
            if (to.names(identity))
                to.held.put(pending.event().predecessorEventSequence(), pending);
        }
    }

    private static boolean unwrapped(AnimationEvent event) {
        var type = payloadType(event);
        if (type == null) return false;
        // U8b: a TaCZ/LR native broadcast carried by this delivery, dispatched once on receipt.
        if (NativePayloads.known(event, type)) return true;
        if (java.util.Arrays.equals(type, PARCOOL_BATCH))
            return event.family()
                    == com.lunares.blockfield.common.combat.AnimationTypes.NativeFamily.PARCOOL;
        if (event.nativePayload().value().size() != 0) return false;
        return java.util.Arrays.equals(type, UNWRAPPED)
                || java.util.Arrays.equals(type, PARCOOL_BATCHED)
                        && event.family()
                                == com.lunares.blockfield.common.combat.AnimationTypes.NativeFamily
                                        .PARCOOL;
    }

    /** The bytes of the definition naming {@code event}'s native payload type, or null. */
    static byte[] payloadType(AnimationEvent event) {
        var payload = event.nativePayload();
        for (var dependency : event.dependencies())
            if (dependency.ref().equals(payload.payloadType())) return dependency.value().value();
        return null;
    }

    // --- Frames ---

    /**
     * One presentation revision: activate due seeds, advance every running reduction to floor(A)
     * with the received S[n], then apply the ready capsules at fraction zero of that bucket. {@code
     * frames} gives an entity's live decoded records by tick.
     */
    public void frame(Stamp stamp, IntFunction<NavigableMap<Long, SnapshotTransform>> frames) {
        for (var life : lives.values()) {
            try {
                step(life, stamp, frames.apply(life.entityId));
            } catch (RuntimeException e) {
                // The life holds its last complete presentation; rows say why (never guessed).
                life.presentation = "reducer:" + e.getMessage();
                count("reducerUnavailable");
            }
        }
    }

    private void step(Life life, Stamp stamp, NavigableMap<Long, SnapshotTransform> frames) {
        long bucket = stamp.a().tick();
        var pending = life.pendingSeed;
        var identity =
                pending == null
                        ? life.seed == null ? null : life.seed.identity()
                        : pending.identity();
        if (identity != null && !presentable.test(identity)) {
            life.presentation = "not_presentable";
            return;
        }
        if (pending != null && bucket >= pending.checkpoint().serverTick()) {
            var assets = ClientAnimationAssets.get(pending.identity());
            if (assets == null) {
                life.presentation = "resource_unavailable";
                return;
            }
            activate(life, pending, assets, stamp);
        }
        if (life.reduction == null) return;
        // Retry the current pairing; release may still refuse an undeliverable capsule.
        if ("not_presentable".equals(life.presentation)) life.presentation = null;
        release(life, stamp.nowNs());
        if ("native_payload_unavailable".equals(life.presentation)
                || "not_presentable".equals(life.presentation)) return;
        if (life.presentation != null && life.presentation.startsWith("reducer:")) return;
        var reduction = life.reduction;
        while (reduction.bucket() < bucket) {
            var record = frames.get(reduction.bucket() + 1);
            if (record == null || record.checkpoint() == null) {
                life.presentation = "missing_boundary";
                return;
            }
            reduction.step(record.checkpoint());
        }
        var assets = ClientAnimationAssets.get(life.seed.identity());
        if (assets == null) {
            life.presentation = "resource_unavailable";
            return;
        }
        while (!life.ready.isEmpty()) apply(life, life.ready.poll(), stamp, assets);
        life.presentation = null;
        finish(life, stamp, assets);
        var fold = life.pendingFold;
        // Never while a held drain could split this life's reports around the new seed's key.
        if (fold != null && bucket > fold.checkpoint().serverTick() && !unfinished()) {
            int covered =
                    life.pendingFoldTime.serverNs() == Long.MIN_VALUE
                            ? -1
                            : coverage(life.pendingFoldTime.serverNs());
            if (covered < 0) {
                // Its receipt can no longer be converted: never adopt it with a guessed proof.
                life.pendingFold = null;
                count("foldMappingUnavailable");
            } else if (covered > 0) {
                life.pendingFold = null;
                adopt(life, fold, life.pendingFoldTime, stamp, frames, assets);
            }
        }
        if (bucket - life.reportedBucket >= HEARTBEAT) report(life, stamp, null);
    }

    /**
     * Converts the unfinished proofs, in application order, whose boundary is now recorded: their
     * sent value replaces the provisional one in the rows and AnimationApplied of this life.
     */
    private void finish(Life life, Stamp stamp, ClientAnimationAssets.Generation assets) {
        while (!life.unfinished.isEmpty()) {
            var next = life.unfinished.peek();
            long ns = next.received().serverNs();
            int covered = Math.min(coverage(ns), coverage(next.acceptedNs()));
            if (covered == 0) return;
            if (covered < 0) {
                // Probe history restarted after it: unreportable, never a guessed sW.
                life.unfinished.clear();
                life.report = "mapping_unavailable";
                count("mappingLost");
                return;
            }
            life.unfinished.poll();
            var p = next.provisional();
            var proof =
                    new ObserverApply(
                            p.applyFrameId(),
                            p.applyRevision(),
                            p.mappingId(),
                            causal(ns, next.acceptedNs()),
                            p.applyR(),
                            p.applyA(),
                            p.applyH(),
                            p.applyEventPrefix());
            life.finals.put(p, proof);
            if (p.equals(life.activation.proof())) {
                life.activation = finished(life.activation, life.finals);
                life.activationFinal = true;
                report(life, stamp, null);
                continue;
            }
            for (int i = 0; i < life.applications.size(); i++) {
                var application = life.applications.get(i);
                if (!p.equals(application.proof())) continue;
                var event = life.applied.get(i).event();
                life.applied.set(
                        i,
                        new ObserverReducer.Applied(
                                event, ObserverReducer.template(event, proof, assets.policy())));
                var sent = new Application(application.eventSequence(), proof);
                life.applications.set(i, sent);
                report(life, stamp, sent);
            }
        }
    }

    /** {@code start} with its finished proof (the value the server replays), else unchanged. */
    private static Start finished(Start start, Map<ObserverApply, ObserverApply> finals) {
        if (!(start.proof() instanceof ObserverApply p) || !finals.containsKey(p)) return start;
        return new Start(
                start.eventSequence(),
                start.nodeId(),
                start.layerTag(),
                start.startKind(),
                start.anchorClass(),
                start.phaseDomain(),
                start.phaseAnchor(),
                start.u0(),
                start.predecessorNodeId(),
                finals.get(p));
    }

    private boolean unfinished() {
        for (var life : lives.values()) if (!life.unfinished.isEmpty()) return true;
        return false;
    }

    /**
     * The SEED_CONTINUE proof of applying {@code seed} now; its sW (the receipt under {@code
     * received}) is finished by {@link #finish}. Exact at once only when that boundary is known.
     */
    private Start activation(ObserverSeed seed, Stamp stamp, Received received) {
        long id = seed.ref().contentId();
        var exact = received.serverNs() != Long.MIN_VALUE && coverage(received.serverNs()) > 0;
        var apply =
                proof(
                        stamp,
                        id,
                        received.mappingId(),
                        exact ? tickTime(received.serverNs()) : stamp.a());
        return new Start(
                id,
                1,
                LayerTag.PA_41,
                StartKind.SEED_CONTINUE,
                AnchorClass.OBSERVER_APPLY,
                PhaseDomain.A,
                stamp.a().tick(),
                0,
                0,
                apply);
    }

    private void activate(
            Life life, ObserverSeed seed, ClientAnimationAssets.Generation assets, Stamp stamp) {
        var received = life.pendingSeedTime;
        var activation = activation(seed, stamp, received);
        life.reduction =
                new ObserverReducer.Reduction(
                        seed, activation, assets.tacz(), assets.lr(), assets.policy());
        life.seed = seed;
        life.pendingSeed = null;
        life.pendingSeedTime = null;
        life.activation = activation;
        life.applied.clear();
        life.applications.clear();
        life.lastCapsule = 0;
        life.report = null;
        life.presentation = null;
        life.pendingFold = null;
        life.unfinished.clear();
        life.finals.clear();
        life.activationFinal = false;
        // The seed presents now; only its sent proof waits for the boundary after its receipt.
        if (received.serverNs() == Long.MIN_VALUE) life.report = "mapping_unavailable";
        else
            life.unfinished.add(
                    new Unfinished((ObserverApply) activation.proof(), received, Long.MIN_VALUE));
        release(life, stamp.nowNs());
        count("seedActivations");
    }

    private void apply(
            Life life, Pending pending, Stamp stamp, ClientAnimationAssets.Generation assets) {
        var event = pending.event();
        long sequence = event.id().eventSequence();
        var release = pending.release();
        // Installed in this frame; the sent sW is finished once its boundary is recorded.
        var proof = proof(stamp, sequence, release.mappingId(), stamp.a());
        var applied =
                new ObserverReducer.Applied(
                        event, ObserverReducer.template(event, proof, assets.policy()));
        life.reduction.apply(applied);
        life.applied.add(applied);
        life.applications.add(new Application(sequence, proof));
        if (release.serverNs() == Long.MIN_VALUE) life.report = "mapping_unavailable";
        else life.unfinished.add(new Unfinished(proof, release, event.acceptedNs()));
        count("applications");
    }

    /**
     * Adopts a server fold of the active seed: the fold activated now, stepped to the current
     * bucket with the received S[n] and the applications after its prefix re-applied at their own
     * buckets. It must reproduce the running presentation node for node (start keys aside, which
     * the activation rebases); otherwise the running seed stays and the fold is counted.
     */
    private void adopt(
            Life life,
            ObserverSeed fold,
            Received received,
            Stamp stamp,
            NavigableMap<Long, SnapshotTransform> frames,
            ClientAnimationAssets.Generation assets) {
        try {
            var activation = activation(fold, stamp, received);
            var rebuilt =
                    new ObserverReducer.Reduction(
                            fold, activation, assets.tacz(), assets.lr(), assets.policy());
            var applied = new ArrayList<ObserverReducer.Applied>();
            var applications = new ArrayList<Application>();
            for (int i = 0; i < life.applied.size(); i++)
                if (life.applied.get(i).event().id().eventSequence() > fold.eventPrefix()) {
                    applied.add(life.applied.get(i));
                    applications.add(life.applications.get(i));
                }
            int next = 0;
            while (true) {
                while (next < applied.size()
                        && applied.get(next).proof().phaseAnchor() == rebuilt.bucket())
                    rebuilt.apply(applied.get(next++));
                if (rebuilt.bucket() >= life.reduction.bucket()) break;
                var record = frames.get(rebuilt.bucket() + 1);
                if (record == null || record.checkpoint() == null)
                    throw new PaGraph.Unsupported("missing_boundary");
                rebuilt.step(record.checkpoint());
            }
            if (next != applied.size()
                    || !sameNodes(rebuilt.result(), life.reduction.result(), stamp)) {
                count("foldMismatch");
                return;
            }
            life.seed = fold;
            life.activation = activation;
            life.activationFinal = true;
            life.reduction = rebuilt;
            life.applied.clear();
            life.applied.addAll(applied);
            life.applications.clear();
            life.applications.addAll(applications);
            report(life, stamp, null);
            count("foldsAdopted");
        } catch (IllegalArgumentException | ArithmeticException refused) {
            count("foldUnavailable");
        }
    }

    /** Equal presentations: same roots/aliases and node data; start keys may differ. */
    private static boolean sameNodes(
            ObserverReducer.Result a, ObserverReducer.Result b, Stamp stamp) {
        var x = a.pa().graph();
        var y = b.pa().graph();
        if (!x.roots().equals(y.roots())
                || !x.aliases().equals(y.aliases())
                || x.nodes().size() != y.nodes().size()) return false;
        for (int i = 0; i < x.nodes().size(); i++) {
            var p = x.nodes().get(i);
            var q = y.nodes().get(i);
            if (p.id() != q.id()
                    || p.domain() != q.domain()
                    || !p.data().equals(q.data())
                    || (p.start() == null) != (q.start() == null)) return false;
        }
        if (!sameParCool(a.parcool(), b.parcool())) return false;
        if ((a.create() == null) != (b.create() == null)) return false;
        return a.create() == null
                || a.create().u0() + (stamp.a().tick() - a.create().phaseAnchor())
                        == b.create().u0() + (stamp.a().tick() - b.create().phaseAnchor());
    }

    private static boolean sameParCool(AnimationState.ParCool x, AnimationState.ParCool y) {
        return java.util.Objects.equals(x.animator(), y.animator())
                && x.option().equals(y.option())
                && x.passive().equals(y.passive())
                && x.actions().equals(y.actions())
                && x.hangGrid().equals(y.hangGrid())
                && x.diveGrid().equals(y.diveGrid());
    }

    /** Queues one life's AnimationApplied content for this revision. */
    private void report(Life life, Stamp stamp, Application application) {
        if (life.seed == null || !life.activationFinal) return;
        // A fold changes the seed, not the proofs still awaiting their first send.
        var key = new ReportKey(life.seed.identity(), life.seed.ref().contentId());
        var previous = unreported.get(key);
        var applications = new ArrayList<Application>();
        if (previous != null) applications.addAll(previous.applications());
        if (application != null) applications.add(application);
        unreported.put(
                key,
                new Report(
                        life.seed.identity(),
                        life.seed.ref().contentId(),
                        life.activation,
                        List.copyOf(applications)));
        life.reportedBucket = stamp.a().tick();
    }

    /** OBSERVER_APPLY fields of one application in the frame of {@code stamp}. */
    private ObserverApply proof(Stamp stamp, long prefix, long mappingId, Time sW) {
        return new ObserverApply(
                stamp.frameId(),
                stamp.revision(),
                mappingId,
                sW,
                stamp.r(),
                stamp.a(),
                stamp.h(),
                prefix);
    }

    // --- Presentation and rows ---

    /** The reduced presentation of a life at its current bucket, or null with a label. */
    public ObserverReducer.Result presentation(int entityId) {
        var life = lives.get(entityId);
        return life == null || life.reduction == null || life.presentation != null
                ? null
                : life.reduction.result();
    }

    /** Why an entity has no presentation (null when it has one). */
    public String unavailable(int entityId) {
        var life = lives.get(entityId);
        if (life == null) return "seed_pending";
        if (life.reduction == null)
            return life.presentation == null ? "seed_pending" : life.presentation;
        return life.presentation;
    }

    /** Result of building one victim row. */
    public record Row(AnimationStartReport.Victim victim, String unavailable) {}

    java.util.Map<Integer, Object> debugLives(Stamp stamp) {
        var out = new java.util.LinkedHashMap<Integer, Object>();
        lives.forEach(
                (id, life) -> {
                    var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("presentation", life.presentation);
                    row.put("report", life.report);
                    row.put("seedIdentity", life.seed == null ? null : life.seed.identity());
                    row.put(
                            "seedTick",
                            life.seed == null ? -1 : life.seed.checkpoint().serverTick());
                    row.put(
                            "seedPresentable",
                            life.seed != null && presentable.test(life.seed.identity()));
                    row.put(
                            "pendingIdentity",
                            life.pendingSeed == null ? null : life.pendingSeed.identity());
                    row.put(
                            "pendingPresentable",
                            life.pendingSeed != null
                                    && presentable.test(life.pendingSeed.identity()));
                    row.put("bucket", life.reduction == null ? -1 : life.reduction.bucket());
                    row.put("oldestH", stamp == null ? -1 : stamp.h() - WINDOW + 1);
                    row.put(
                            "activationApplyH",
                            life.activation == null
                                    ? -1
                                    : ((ObserverApply) life.activation.proof()).applyH());
                    row.put(
                            "applicationHs",
                            life.applications.stream().map(a -> a.proof().applyH()).toList());
                    row.put("held", life.held.size());
                    row.put("ready", life.ready.size());
                    out.put(id, row);
                });
        return out;
    }

    /**
     * The complete victim row of a driven player named by the H batch {@code record}: the active
     * seed, every contributing start of the reduced presentation, every application after the seed
     * prefix and both digests. Unavailable (labelled) when the presentation, its identity, mapping
     * evidence or the server's 40-tick sent window cannot support an exact replay.
     */
    public Row row(SnapshotTransform record, Stamp stamp) {
        var identity = record.identity();
        var life = lives.get(identity.entityId());
        if (life == null || !life.names(identity)) return new Row(null, "seed_pending");
        if (life.reduction == null || life.presentation != null)
            return new Row(null, unavailable(identity.entityId()));
        if (life.report != null) return new Row(null, life.report);
        // A committed later view would make the server refuse an older unsent proof.
        if (unfinished()) return new Row(null, "application_pending");
        if (!life.seed.identity().sameGeneration(identity)) return new Row(null, "seed_generation");
        var reduction = life.reduction;
        if (reduction.bucket() != stamp.a().tick()) return new Row(null, "bucket");
        // The server retains sent ticks above H - 40 and reduces at most 40 buckets per seed.
        long oldest = stamp.h() - WINDOW + 1;
        long seedTick = life.seed.checkpoint().serverTick();
        if (seedTick + 1 < oldest
                || reduction.bucket() - seedTick > WINDOW
                || ((ObserverApply) life.activation.proof()).applyH() < oldest
                || life.applications.stream().anyMatch(a -> a.proof().applyH() < oldest))
            return new Row(null, "seed_window");
        var result = reduction.result();
        var ordered = ObserverReducer.ordered(life.applied);
        var applications = new ArrayList<>(life.applications);
        applications.sort(Comparator.comparingLong(Application::eventSequence));
        long seedRef = life.seed.ref().contentId();
        // Canonical decisions after the seed name their own (later) journal ids; the server's
        // reconstruction raises the prefix over the reduced starts the same way.
        long prefix = Math.max(life.prefixHigh, life.appliedPrefix());
        var starts = new ArrayList<Start>();
        for (Start start : result.starts()) {
            prefix = Math.max(prefix, start.eventSequence());
            starts.add(finished(start, life.finals));
        }
        life.prefixHigh = prefix;
        var provisional =
                new AnimationStartReport.Victim(
                        identity.entityId(),
                        identity.targetEpoch(),
                        identity.seatGeneration(),
                        identity.teleportGeneration(),
                        identity.subscriptionGeneration(),
                        seedRef,
                        prefix,
                        ObserverReducer.prefixDigest(seedRef, ordered),
                        starts,
                        applications,
                        new AnimationResources.Digest(0, 0, 0, 0));
        var victim =
                new AnimationStartReport.Victim(
                        provisional.entityId(),
                        provisional.targetEpoch(),
                        provisional.seatGeneration(),
                        provisional.teleportGeneration(),
                        provisional.subscriptionGeneration(),
                        provisional.seedRef(),
                        provisional.appliedEventSequence(),
                        provisional.eventPrefixDigest(),
                        provisional.starts(),
                        provisional.applications(),
                        AnimationResolver.presentationDigest(
                                life.seed, result.pa().graph(), provisional));
        return new Row(victim, null);
    }

    /** The common complete two-time decision used for both client install and server rewind. */
    public AnimationResolver.Result resolve(
            SnapshotTransform record,
            Stamp stamp,
            NavigableMap<Long, SnapshotTransform> frames,
            AnimationResources.Store resources) {
        var life = lives.get(record.identity().entityId());
        var row = row(record, stamp);
        if (row.victim() == null)
            return new AnimationResolver.Result(
                    AnimationResolver.Status.REPORT_UNAVAILABLE, null, row.unavailable());
        var assets = ClientAnimationAssets.get(record.identity());
        if (assets == null)
            return new AnimationResolver.Result(
                    AnimationResolver.Status.RESOURCE_UNAVAILABLE, null, "resource_unavailable");
        // Generation-scoped native assets are preloaded by READY, not repeated in snapshots.
        resources.installAll(assets.lr().definitions());
        return AnimationResolver.resolve(
                new AnimationResolver.Request(
                        record.identity(),
                        stamp.h(),
                        stamp.r(),
                        stamp.a(),
                        life.seed,
                        row.victim(),
                        life.reduction.result().pa().graph()),
                frames.values().stream()
                        .map(SnapshotTransform::checkpoint)
                        .filter(java.util.Objects::nonNull)
                        .toList(),
                resources,
                assets.policy());
    }

    /**
     * This revision's AnimationApplied content in seed adoption order: activations/heartbeats and
     * the applications stamped since the last call (first-report times can only tighten bounds).
     */
    public List<Report> drainUnreported() {
        // Held, in order, until every proof is exact: first-report order is application order.
        if (unfinished()) return List.of();
        var drained = List.copyOf(unreported.values());
        unreported.clear();
        return drained;
    }

    void references(
            java.util.Set<AnimationResources.ResourceRef> refs, java.util.Set<Long> generations) {
        for (var life : lives.values()) {
            for (var seed : new ObserverSeed[] {life.seed, life.pendingSeed, life.pendingFold}) {
                if (seed == null) continue;
                generations.add(seed.identity().resourceGeneration());
                ClientAnimationAssets.references(seed, refs);
            }
            for (var applied : life.applied)
                ClientAnimationAssets.references(applied.event(), refs);
            for (var pending : life.held.values())
                ClientAnimationAssets.references(pending.event(), refs);
            for (var pending : life.ready) ClientAnimationAssets.references(pending.event(), refs);
            if (life.reduction != null) {
                var result = life.reduction.result();
                ClientAnimationAssets.references(result.pa().graph(), refs);
                ClientAnimationAssets.references(result.parcool(), refs);
                ClientAnimationAssets.references(result.create(), refs);
            }
        }
        for (var report : unreported.values())
            generations.add(report.identity().resourceGeneration());
    }

    public void clear() {
        lives.clear();
        boundaries.clear();
        probe = null;
        unreported.clear();
    }

    private void count(String key) {
        counters.merge(key, 1L, Long::sum);
    }

    public Map<String, Long> counters() {
        return Map.copyOf(counters);
    }

    int lives() {
        return lives.size();
    }
}
