package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationEvent.ObserverSeed;
import com.lunares.blockfield.common.combat.AnimationStartReport.ObserverApply;
import com.lunares.blockfield.common.combat.AnimationStartReport.Start;
import com.lunares.blockfield.common.combat.AnimationTypes.AnchorClass;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.NativeStage;
import com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain;
import com.lunares.blockfield.common.combat.AnimationTypes.StartKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The observer presentation reducer (ADR4 §§2.3–2.4/3.2/3.6), one implementation for the server
 * receipt validation (U7b) and the client presentation (U8): from a server-issued seed and its
 * observer activation, every integer A bucket runs the one domain tick, that boundary's canonical
 * decisions in journal order, then the capsules the observer applied in that bucket in application
 * order. A capsule is a patch at fraction zero of its bucket: no extra tick, no reselection.
 *
 * <p>Inputs are immutable decoded values; nothing here reads a live entity, a clock or a later H. A
 * missing boundary checkpoint, an unknown family or an unproven node throws {@link
 * PaGraph.Unsupported}: the caller reports unavailable, never a guessed graph.
 */
public final class ObserverReducer {
    private ObserverReducer() {}

    /**
     * Names this reducer and the family reducers it composes (U3 PA, U4 LR, server-clock Create, U5
     * ParCool stages in one composed graph). Seeds name the reducer they were issued for; any
     * change is a new value.
     */
    public static final AnimationResources.Digest DIGEST =
            AnimationResources.Digest.hash(
                    "blockfield.anim-sync.reducer.v2:pa=u3f;lr=lr.transitions.v5;create=u7b-server-tick;parcool=u5.stages.v4+u7c.compose.v1"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

    /**
     * One native scratch per {@link Reduction#step} for the tick and every PA decision instead of a
     * materialization per operation ({@link PaGraph.Stack#reuse}); off runs the per-operation path
     * that the equivalence test compares against.
     */
    private static volatile boolean shareStepScratch = true;

    /** Test switch: false runs every PA operation of a step on its own fresh scratch. */
    public static void shareStepScratch(boolean value) {
        shareStepScratch = value;
    }

    /**
     * The applied event prefix a victim row names: its seed, then every capsule the observer
     * applied after it, in application order (id, bucket, frame, revision).
     */
    public static AnimationResources.Digest prefixDigest(long seed, List<Applied> applied) {
        return AnimationResources.Digest.hash(
                AnimationSchema.encodeBounded(
                        b -> {
                            SnapshotWireMath.writeUvarLong(b, seed);
                            SnapshotWireMath.writeUvarInt(b, applied.size());
                            for (Applied event : applied) {
                                var proof = (ObserverApply) event.proof().proof();
                                SnapshotWireMath.writeUvarLong(
                                        b, event.event().id().eventSequence());
                                SnapshotWireMath.writeUvarLong(b, event.proof().phaseAnchor());
                                SnapshotWireMath.writeUvarLong(b, proof.applyFrameId());
                                SnapshotWireMath.writeUvarLong(b, proof.applyRevision());
                            }
                        }));
    }

    /**
     * Application order of applied capsules: bucket, then application A, frame, revision and id.
     * Both evaluators order with this one comparator.
     */
    public static List<Applied> ordered(java.util.Collection<Applied> applied) {
        var list = new ArrayList<>(applied);
        list.sort(
                Comparator.<Applied>comparingLong(a -> a.proof().phaseAnchor())
                        .thenComparing(a -> ((ObserverApply) a.proof().proof()).applyA())
                        .thenComparingLong(a -> ((ObserverApply) a.proof().proof()).applyFrameId())
                        .thenComparingLong(a -> ((ObserverApply) a.proof().proof()).applyRevision())
                        .thenComparingLong(a -> a.event().id().eventSequence()));
        return List.copyOf(list);
    }

    /** One capsule the observer applied, with the OBSERVER_APPLY proof it reported for it. */
    public record Applied(AnimationEvent event, Start proof) {
        public Applied {
            if (proof.anchorClass() != AnchorClass.OBSERVER_APPLY
                    || proof.eventSequence() != event.id().eventSequence())
                throw new PaGraph.Unsupported("Applied capsule needs its own observer proof");
        }
    }

    /**
     * The reduced presentation at bucket {@code tick}: the composed graph (PA and ParCool nodes)
     * with its node starts, the Create membership start while hanging, the observer ParCool family
     * value and its non-node starts (the contributing passive phase key). Only the graph and starts
     * are hashed into the presentation digest; the ParCool value is the same deterministic function
     * of that closure on both evaluators.
     */
    public record Result(
            PaProjection.Result pa,
            long tick,
            Start create,
            AnimationState.ParCool parcool,
            List<Start> parcoolStarts) {
        public Result {
            parcoolStarts = List.copyOf(parcoolStarts);
        }

        /** The ParCool frame of this presentation (the composed graph and the family value). */
        public ParCoolFrame parcoolFrame() {
            return new ParCoolFrame(pa.graph(), parcool);
        }

        /** Every contributing start in report order (event sequence, then node id). */
        public List<Start> starts() {
            var all = new ArrayList<>(pa.starts());
            if (create != null) all.add(create);
            all.addAll(parcoolStarts);
            all.sort(
                    Comparator.comparingLong(Start::eventSequence).thenComparingInt(Start::nodeId));
            return List.copyOf(all);
        }
    }

    /**
     * The replay template of one applied capsule: its patch's layer and kind, the policy domain,
     * the whole application bucket and zero u0. Native allocation supplies node ids; both
     * evaluators derive it from the delivered capsule and the reported application, never from a
     * client start.
     */
    public static Start template(
            AnimationEvent event, ObserverApply apply, AnimationPolicy policy) {
        var layer = event.patch().layer();
        var domain = policy.domain(layer);
        return new Start(
                event.id().eventSequence(),
                1,
                layer,
                event.patch().startKind(),
                AnchorClass.OBSERVER_APPLY,
                domain,
                (domain == PhaseDomain.A ? apply.applyA() : apply.applyR()).tick(),
                0,
                0,
                apply);
    }

    /**
     * Reduces {@code seed} to integer bucket {@code to}. {@code activation} is the observer's
     * SEED_CONTINUE proof of this seed; {@code checkpoints} are the received S[n] (each boundary
     * after the seed tick must be present); {@code applied} are the capsules the observer applied
     * after activation, in application order.
     */
    public static Result reduce(
            ObserverSeed seed,
            Start activation,
            long to,
            Map<Long, AnimationState> checkpoints,
            List<Applied> applied,
            TaczAssets tacz,
            LrAssets lr,
            AnimationPolicy policy) {
        return reduce(seed, activation, to, checkpoints, applied, tacz, lr, policy, null);
    }

    /**
     * {@link #reduce}, continuing from {@code memo}'s retained state when it consumed exactly this
     * request's inputs up to its bucket (null: always replay the seed).
     */
    public static Result reduce(
            ObserverSeed seed,
            Start activation,
            long to,
            Map<Long, AnimationState> checkpoints,
            List<Applied> applied,
            TaczAssets tacz,
            LrAssets lr,
            AnimationPolicy policy,
            Memo memo) {
        long first = seed.checkpoint().serverTick();
        var pending = new ArrayList<Applied>();
        for (Applied event : applied)
            if (event.event().id().eventSequence() > seed.eventPrefix()) pending.add(event);
        Reduction resumed =
                memo == null
                        ? null
                        : memo.resume(seed, activation, to, checkpoints, pending, tacz, lr, policy);
        // The constructor's refusals come first, as in a replay; a resumed state was built from
        // the same seed and activation, so its construction succeeded.
        var reduction =
                resumed != null ? resumed : new Reduction(seed, activation, tacz, lr, policy);
        if (activation != null
                && activation.proof() instanceof ObserverApply apply
                && to < apply.applyA().tick())
            throw new PaGraph.Unsupported("Seed activation outside its bounded window");
        if (to < first || to - first > 40)
            throw new PaGraph.Unsupported("Missing bounded continuation seed");
        int next = resumed != null ? memo.consumed.size() : 0;
        for (long n = resumed != null ? resumed.bucket() + 1 : first; n <= to; n++) {
            if (n > reduction.bucket()) {
                var checkpoint = checkpoints.get(n);
                if (checkpoint == null)
                    throw new PaGraph.Unsupported("Missing PA reducer boundary: " + n);
                reduction.step(checkpoint);
            }
            while (next < pending.size() && pending.get(next).proof().phaseAnchor() == n)
                reduction.apply(pending.get(next++));
            if (next < pending.size() && pending.get(next).proof().phaseAnchor() < n)
                throw new PaGraph.Unsupported("Applied capsule out of bucket order");
        }
        if (next != pending.size())
            throw new PaGraph.Unsupported("Applied capsule beyond the reduced bucket");
        var result = reduction.result();
        if (memo != null) {
            if (resumed != null && verifyMemo) {
                var replayed =
                        reduce(seed, activation, to, checkpoints, applied, tacz, lr, policy, null);
                if (!replayed.equals(result))
                    throw new IllegalStateException("Reduction memo diverged at bucket " + to);
            }
            memo.retain(seed, activation, reduction, pending, checkpoints, tacz, lr, policy);
        }
        return result;
    }

    private static volatile boolean verifyMemo;

    /** Test/benchmark switch: every resumed reduction is also replayed and must be equal. */
    public static void verifyMemo(boolean value) {
        verifyMemo = value;
    }

    /**
     * The last reduction of one (observer, target life) and exactly the inputs it consumed: the
     * seed and activation, the family assets and policy, the applied capsules (all at or below its
     * bucket) and the boundary checkpoints it stepped. A request with those inputs continues from
     * that state; the reducer is a deterministic function of these immutable values, so the result
     * equals a replay from the seed. Not thread-safe.
     */
    public static final class Memo {
        private ObserverSeed seed;
        private Start activation;
        private TaczAssets tacz;
        private LrAssets lr;
        private AnimationPolicy policy;
        private Reduction state;
        private List<Applied> consumed = List.of();
        private AnimationState[] used = new AnimationState[0];
        private long resumes;

        /**
         * Copy while the source is confined or published read-only; never race this with a
         * reduction that mutates the source. Immutable snapshots may be copied concurrently by
         * workers. Copies share immutable values and carry no mutable native scratch; each mutable
         * instance must remain confined to its owning worker.
         */
        public Memo copy() {
            var copy = new Memo();
            copy.seed = seed;
            copy.activation = activation;
            copy.tacz = tacz;
            copy.lr = lr;
            copy.policy = policy;
            copy.state = state == null ? null : new Reduction(state);
            copy.consumed = consumed;
            copy.used = used.clone();
            copy.resumes = resumes;
            return copy;
        }

        /** Requests answered by continuing the retained state (tests and diagnostics). */
        public long resumes() {
            return resumes;
        }

        /** A private copy of the retained state when the request extends its inputs, else null. */
        private Reduction resume(
                ObserverSeed seed,
                Start activation,
                long to,
                Map<Long, AnimationState> checkpoints,
                List<Applied> pending,
                TaczAssets tacz,
                LrAssets lr,
                AnimationPolicy policy) {
            if (state == null
                    || seed != this.seed
                    || tacz != this.tacz
                    || lr != this.lr
                    || policy != this.policy
                    || !java.util.Objects.equals(activation, this.activation)
                    || state.bucket() > to
                    || pending.size() < consumed.size()) return null;
            for (int i = 0; i < consumed.size(); i++) {
                var a = consumed.get(i);
                var b = pending.get(i);
                if (a.event() != b.event() && !a.event().equals(b.event())
                        || !a.proof().equals(b.proof())) return null;
            }
            // A further capsule at or below the retained bucket would change what it applied.
            if (pending.size() > consumed.size()
                    && pending.get(consumed.size()).proof().phaseAnchor() <= state.bucket())
                return null;
            long first = seed.checkpoint().serverTick();
            for (int i = 0; i < used.length; i++)
                if (checkpoints.get(first + 1 + i) != used[i]) return null;
            resumes++;
            return new Reduction(state);
        }

        private void retain(
                ObserverSeed seed,
                Start activation,
                Reduction reduction,
                List<Applied> pending,
                Map<Long, AnimationState> checkpoints,
                TaczAssets tacz,
                LrAssets lr,
                AnimationPolicy policy) {
            this.seed = seed;
            this.activation = activation;
            this.tacz = tacz;
            this.lr = lr;
            this.policy = policy;
            state = reduction;
            consumed = List.copyOf(pending);
            long first = seed.checkpoint().serverTick();
            used = new AnimationState[Math.toIntExact(reduction.bucket() - first)];
            for (int i = 0; i < used.length; i++) used[i] = checkpoints.get(first + 1 + i);
        }
    }

    /**
     * The same reducer advanced in place: the client presents every frame from one running
     * reduction (one step per new integer A bucket, one patch per applied capsule) instead of
     * replaying the seed, and {@link #reduce} is this loop. Not thread-safe; values stay immutable.
     */
    public static final class Reduction {
        private final ObserverSeed seed;
        private final TaczAssets tacz;
        private final LrAssets lr;
        private final AnimationPolicy policy;
        private final PaGraph.Catalog catalog;
        private PaProjection.Result result;
        private Start create;
        private long bucket;
        private long applied;
        // ParCool: the observer family value, its non-node starts and the bucket's checkpoint
        // (the sample its boundary and capsule options read).
        private AnimationState.ParCool parcool;
        private List<Start> parcoolStarts;
        private AnimationState sample;
        // The step scratch whose last freeze may be this reduction's graph; never shared by copies.
        private PaGraph.Stack carried;

        /** A copy at the same bucket; every field is an immutable value. */
        private Reduction(Reduction other) {
            seed = other.seed;
            tacz = other.tacz;
            lr = other.lr;
            policy = other.policy;
            catalog = other.catalog;
            result = other.result;
            create = other.create;
            bucket = other.bucket;
            applied = other.applied;
            parcool = other.parcool;
            parcoolStarts = other.parcoolStarts;
            sample = other.sample;
        }

        public Reduction(
                ObserverSeed seed,
                Start activation,
                TaczAssets tacz,
                LrAssets lr,
                AnimationPolicy policy) {
            if (policy.lowerBodyDomain() != PhaseDomain.A)
                throw new PaGraph.Unsupported("R lower-body reducer pending (owner A/B variant)");
            if (!seed.reducerDigest().equals(DIGEST))
                throw new PaGraph.Unsupported("Seed names another reducer");
            this.seed = seed;
            this.tacz = tacz;
            this.lr = lr;
            this.policy = policy;
            bucket = seed.checkpoint().serverTick();
            applied = seed.eventPrefix();
            // A zero-start (neutral) seed needs no activation proof: nothing rebases.
            ObserverApply apply = null;
            if (activation != null) {
                if (activation.startKind() != StartKind.SEED_CONTINUE
                        || !(activation.proof() instanceof ObserverApply proof)
                        || activation.eventSequence() != seed.ref().contentId())
                    throw new PaGraph.Unsupported("Seed activation proof");
                apply = proof;
                if (apply.applyA().tick() < bucket)
                    throw new PaGraph.Unsupported("Seed activation outside its bounded window");
            } else if (!seed.starts().isEmpty())
                throw new PaGraph.Unsupported("Seed with starts needs its activation proof");
            catalog = lr.catalog();
            if (tacz != null && tacz.catalog() != catalog)
                throw new PaGraph.Unsupported("Mixed families must share one PA catalog");
            sample = seed.checkpoint();
            parcool = seed.checkpoint().parcool();
            var nodeKeys = new HashSet<AnimationStartReport.StartKey>();
            for (var node : seed.graph().nodes())
                if (node.start() != null) nodeKeys.add(node.start());
            var paStarts = new ArrayList<Start>();
            Start passive = null;
            for (Start start : seed.starts()) {
                var key = new AnimationStartReport.StartKey(start.eventSequence(), start.nodeId());
                if (start.layerTag() == LayerTag.CREATE_HANGING)
                    create = rebase(start, activation, policy);
                else if (nodeKeys.contains(key)) paStarts.add(start);
                else if (start.layerTag() == LayerTag.PARCOOL_PASSIVE
                        && key.equals(parcool.passiveStart())
                        && passive == null) passive = start;
                else throw new PaGraph.Unsupported("Seed start without its node or phase key");
            }
            result = PaProjection.retain(seed.graph(), paStarts);
            if (apply != null && !paStarts.isEmpty())
                result =
                        PaProjection.seedContinue(
                                result, activation.eventSequence(), apply, policy);
            if (apply != null && passive != null) {
                passive = rebase(passive, activation, policy);
                parcool = withPassiveKey(parcool, passive);
            }
            parcoolStarts = passive == null ? List.of() : List.of(passive);
            ParCoolProjection.validate(parcoolResult(), policy);
        }

        /** The current integer bucket: the seed tick, then one more per {@link #step}. */
        public long bucket() {
            return bucket;
        }

        /** Highest applied event sequence folded into this reduction (seed prefix at start). */
        public long applied() {
            return applied;
        }

        public ObserverSeed seed() {
            return seed;
        }

        /**
         * Enters bucket {@code n + 1}: one A-domain PA tick, then S[n + 1]'s decisions in journal
         * order, its ParCool boundary marker running the family's NATIVE_TICK boundary in place.
         */
        public void step(AnimationState checkpoint) {
            try {
                advance(checkpoint);
            } catch (RuntimeException | Error refused) {
                // A refused step may leave natives past their last freeze: never carry them.
                carried = null;
                throw refused;
            }
        }

        private void advance(AnimationState checkpoint) {
            long n = Math.addExact(bucket, 1);
            if (checkpoint.serverTick() != n)
                throw new PaGraph.Unsupported("Missing PA reducer boundary: " + n);
            if (!shareStepScratch) {
                reference(checkpoint, n);
                return;
            }
            // A6 STEP: one scratch and one freeze per step. The tick and every PA decision run
            // on the scratch; each decision's start bookkeeping is derived from the final graph
            // and the decision's event sequence when the step settles. Intermediate graphs are
            // not part of any result. The ParCool boundary still runs at its journal position
            // through its validated stage: it touches only ParCool state, so the scratch stays
            // valid across it and the freeze takes its composed graph as the base.
            PaGraph.Stack scratch;
            if (carried != null && carried.frozeInto(result.graph())) {
                carried.reuse();
                scratch = carried;
            } else
                scratch =
                        carried =
                                PaGraph.Stack.materialize(
                                        result.graph(), catalog, policy, NO_SOURCES);
            scratch.tick(PhaseDomain.A);
            var stepBase = result;
            var prior = new ArrayList<PaProjection.Pending>();
            long sequence = -1;
            boolean boundary = false;
            // Every decision of this boundary names the same placement: one digest per step.
            AnimationResources.Digest placement = null;
            for (var decision : checkpoint.decisions()) {
                if (decision.id().serverTick() != n
                        || decision.id().eventSequence() <= sequence
                        || decision.id().nativeStage().compareTo(NativeStage.NATIVE_TICK) <= 0)
                    throw new PaGraph.Unsupported("Decision stage/order mismatch");
                sequence = decision.id().eventSequence();
                if (policy.domain(decision.layer()) != PhaseDomain.A) continue;
                if (decision.input().contentId() != n)
                    throw new PaGraph.Unsupported("Decision input is not its boundary placement");
                if (placement == null)
                    placement =
                            AnimationResources.Digest.hash(
                                    AnimationSchema.encode(checkpoint.placement()));
                if (!decision.input().dependencyDigest().equals(placement))
                    throw new PaGraph.Unsupported("Decision input is not its boundary placement");
                if (ParCoolCheckpoints.family(decision.layer())) {
                    if (boundary) throw new PaGraph.Unsupported("Second ParCool boundary marker");
                    boundary = true;
                    var stages =
                            new ParCoolStages(
                                    ParCoolStages.Mode.OBSERVER,
                                    Map.of(decision.input(), checkpoint),
                                    Map.of(),
                                    policy);
                    parcool(
                            stages,
                            n,
                            NativeStage.NATIVE_TICK,
                            ParCoolCheckpoints.boundary(decision));
                } else if (decision.patch().lr() != null)
                    prior.add(
                            LrProjection.decisionStep(
                                    scratch,
                                    stepBase.starts(),
                                    prior,
                                    decision,
                                    checkpoint.placement(),
                                    lr,
                                    policy));
                else if (tacz != null)
                    prior.add(
                            PaProjection.taczStep(
                                    scratch,
                                    stepBase.starts(),
                                    prior,
                                    decision,
                                    checkpoint.placement(),
                                    tacz,
                                    policy));
                else throw new PaGraph.Unsupported("Decision without a PA family closure");
            }
            if (!boundary) throw new PaGraph.Unsupported("Missing ParCool boundary: " + n);
            // The known starts are the step's current ones: the boundary may have bound new
            // ParCool node starts, which are accounted for, not pending PA decisions.
            result = PaProjection.settle(result, scratch.freeze(result.graph()), prior, policy);
            sample = checkpoint;
            bucket = n;
        }

        /**
         * The UOPT reference path ({@link ObserverReducer#shareStepScratch(boolean)
         * shareStepScratch(false)}): fresh scratch and one freeze per operation. Byte-identical to
         * the shared path above.
         */
        private void reference(AnimationState checkpoint, long n) {
            result = PaProjection.tick(result, PhaseDomain.A, catalog, policy, NO_SOURCES);
            long sequence = -1;
            boolean boundary = false;
            AnimationResources.Digest placement = null;
            for (var decision : checkpoint.decisions()) {
                if (decision.id().serverTick() != n
                        || decision.id().eventSequence() <= sequence
                        || decision.id().nativeStage().compareTo(NativeStage.NATIVE_TICK) <= 0)
                    throw new PaGraph.Unsupported("Decision stage/order mismatch");
                sequence = decision.id().eventSequence();
                if (policy.domain(decision.layer()) != PhaseDomain.A) continue;
                if (decision.input().contentId() != n)
                    throw new PaGraph.Unsupported("Decision input is not its boundary placement");
                if (placement == null)
                    placement =
                            AnimationResources.Digest.hash(
                                    AnimationSchema.encode(checkpoint.placement()));
                if (!decision.input().dependencyDigest().equals(placement))
                    throw new PaGraph.Unsupported("Decision input is not its boundary placement");
                if (ParCoolCheckpoints.family(decision.layer())) {
                    if (boundary) throw new PaGraph.Unsupported("Second ParCool boundary marker");
                    boundary = true;
                    var stages =
                            new ParCoolStages(
                                    ParCoolStages.Mode.OBSERVER,
                                    Map.of(decision.input(), checkpoint),
                                    Map.of(),
                                    policy);
                    parcool(
                            stages,
                            n,
                            NativeStage.NATIVE_TICK,
                            ParCoolCheckpoints.boundary(decision));
                } else if (decision.patch().lr() != null)
                    result =
                            LrProjection.decision(
                                    result,
                                    decision,
                                    checkpoint.placement(),
                                    lr,
                                    policy,
                                    NO_SOURCES);
                else if (tacz != null)
                    result =
                            PaProjection.taczDecision(
                                    result,
                                    decision,
                                    checkpoint.placement(),
                                    tacz,
                                    policy,
                                    NO_SOURCES);
                else throw new PaGraph.Unsupported("Decision without a PA family closure");
            }
            if (!boundary) throw new PaGraph.Unsupported("Missing ParCool boundary: " + n);
            sample = checkpoint;
            bucket = n;
        }

        /** One capsule at fraction zero of the current bucket, in application order. */
        public void apply(Applied event) {
            if (event.proof().phaseAnchor() != bucket)
                throw new PaGraph.Unsupported("Applied capsule out of bucket order");
            long sequence = event.event().id().eventSequence();
            if (sequence <= seed.eventPrefix()) return;
            switch (event.event().family()) {
                case CREATE -> create = membership(event, create);
                case PARCOOL -> parcoolEvent(event);
                default -> result = install(result, event, catalog, policy, NO_SOURCES);
            }
            applied = Math.max(applied, sequence);
        }

        /**
         * An accepted ParCool start/stop at fraction zero of this bucket (bf7 other-client
         * callbacks). Its options read the bucket's checkpoint; a selected fragment binds the
         * observer proof to each of its nodes; an action-only stop is reported by its application
         * row and adds no start.
         */
        private void parcoolEvent(Applied event) {
            var patch = event.event().patch();
            var input = ParCoolCheckpoints.input(sample);
            var decision =
                    new AnimationEvent.Decision(
                            new AnimationEvent.EventId(
                                    bucket,
                                    event.event().id().eventSequence(),
                                    NativeStage.END_COMMIT),
                            patch.layer(),
                            patch.startKind(),
                            patch.outcome(),
                            0,
                            null,
                            input,
                            patch,
                            0);
            var templates =
                    patch.outcome() == AnimationTypes.Outcome.SELECTED
                            ? Collections.nCopies(patch.replacement().nodes().size(), event.proof())
                            : List.<Start>of();
            var stages =
                    new ParCoolStages(
                            ParCoolStages.Mode.OBSERVER,
                            Map.of(input, sample),
                            Map.of(event.event().id().eventSequence(), templates),
                            policy);
            parcool(stages, bucket, NativeStage.END_COMMIT, decision);
        }

        /** One validated ParCool stage over the composed graph; other families stay unchanged. */
        private void parcool(
                ParCoolStages stages,
                long tick,
                NativeStage stage,
                AnimationEvent.Decision record) {
            var after =
                    ParCoolProjection.stage(
                            stages, parcoolResult(), tick, stage, List.of(record), policy);
            var nodeKeys = new HashSet<AnimationStartReport.StartKey>();
            for (var node : after.frame().graph().nodes())
                if (node.start() != null) nodeKeys.add(node.start());
            var nodeStarts = new ArrayList<Start>();
            var other = new ArrayList<Start>();
            for (Start start : after.starts())
                if (nodeKeys.contains(
                        new AnimationStartReport.StartKey(start.eventSequence(), start.nodeId())))
                    nodeStarts.add(start);
                else if (start.layerTag() == LayerTag.PARCOOL_PASSIVE
                        || start.layerTag() == LayerTag.PARCOOL_ACTIVE) other.add(start);
                else throw new PaGraph.Unsupported("ParCool stage left a foreign phase key");
            result = PaProjection.retain(after.frame().graph(), nodeStarts);
            parcool = after.frame().state();
            parcoolStarts = List.copyOf(other);
        }

        private ParCoolProjection.Result parcoolResult() {
            var all = new ArrayList<>(result.starts());
            all.addAll(parcoolStarts);
            return new ParCoolProjection.Result(new ParCoolFrame(result.graph(), parcool), all);
        }

        public Result result() {
            return new Result(result, bucket, create, parcool, parcoolStarts);
        }
    }

    /** The family value with its passive phase key renamed to {@code passive}'s key. */
    private static AnimationState.ParCool withPassiveKey(
            AnimationState.ParCool old, Start passive) {
        return new AnimationState.ParCool(
                old.animator(),
                new AnimationStartReport.StartKey(passive.eventSequence(), passive.nodeId()),
                old.option(),
                old.passive(),
                old.actions(),
                old.hangGrid(),
                old.diveGrid());
    }

    private static final PaGraph.Sources NO_SOURCES = id -> part -> Optional.empty();

    /** One TaCZ/LR capsule at fraction zero of its bucket with the observer's proof. */
    private static PaProjection.Result install(
            PaProjection.Result base,
            Applied applied,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            PaGraph.Sources sources) {
        var event = applied.event();
        var patch = event.patch();
        var proof = applied.proof();
        return switch (event.family()) {
            case TACZ -> PaProjection.apply(base, patch, proof, catalog, policy, sources);
            case LR -> {
                if (patch.outcome() != AnimationTypes.Outcome.SELECTED) yield base;
                yield LrProjection.attack(base, clip(patch), proof, catalog, policy, sources);
            }
            default -> throw new PaGraph.Unsupported("No observer reducer for " + event.family());
        };
    }

    /** The attack clip of an LR capsule: its fragment actor's resource ref. */
    private static AnimationResources.ClipRef clip(AnimationEvent.Patch patch) {
        for (var node : patch.replacement().nodes())
            if (node.data() instanceof AnimationGraphState.KeyframePlayer actor)
                return actor.data();
        throw new PaGraph.Unsupported("LR attack capsule without an actor");
    }

    /** Create presentation: an enter starts phase at its applied bucket, an exit ends it. */
    private static Start membership(Applied applied, Start incumbent) {
        var create = applied.event().patch().create();
        if (create == null) throw new PaGraph.Unsupported("Create capsule without membership");
        if (!create.hanging()) return null;
        var proof = applied.proof();
        if (proof.layerTag() != LayerTag.CREATE_HANGING || proof.u0() != 0)
            throw new PaGraph.Unsupported("Create enter proof");
        return proof;
    }

    /**
     * A seed's non-node start (Create membership, ParCool passive key) as SEED_CONTINUE at the
     * activation, preserving its whole age.
     */
    private static Start rebase(Start start, Start activation, AnimationPolicy policy) {
        var apply = (ObserverApply) activation.proof();
        var age = AnimationStartReport.age(start, apply.applyR(), apply.applyA(), policy);
        return new Start(
                activation.eventSequence(),
                start.nodeId(),
                start.layerTag(),
                StartKind.SEED_CONTINUE,
                AnchorClass.OBSERVER_APPLY,
                start.phaseDomain(),
                apply.applyA().tick(),
                Math.toIntExact(age.tick()),
                0,
                apply);
    }
}
