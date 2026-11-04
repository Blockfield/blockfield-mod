package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationEvent.*;
import com.lunares.blockfield.common.combat.AnimationGraphState.*;
import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationStartReport.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.*;

/**
 * Pure family operations. U7/U8 own causal authorization, complete input prefixes and transport.
 */
public final class PaProjection {
    private PaProjection() {}

    /** A family result, not a wire record: both values are the frozen U1 types. */
    public record Result(AnimationGraphState graph, List<Start> starts) {
        public Result {
            starts = List.copyOf(starts);
        }
    }

    /**
     * Drop only starts no longer reachable, including starts shared by frozen/live fade sources.
     */
    public static Result retain(AnimationGraphState graph, List<Start> starts) {
        var keys = new HashSet<StartKey>();
        for (Node node : graph.nodes()) if (node.start() != null) keys.add(node.start());
        var retained = new ArrayList<Start>();
        for (Start start : starts) {
            AnimationStartReport.validateStart(start);
            if (keys.remove(new StartKey(start.eventSequence(), start.nodeId())))
                retained.add(start);
        }
        if (!keys.isEmpty()) throw new PaGraph.Unsupported("Missing contributing PA start");
        retained.sort(
                Comparator.comparingLong(Start::eventSequence).thenComparingInt(Start::nodeId));
        return new Result(graph, retained);
    }

    /** Rebase a server-issued continuation on seed application without restarting native nodes. */
    public static Result seedContinue(
            Result base, long sequence, ObserverApply activation, AnimationPolicy policy) {
        AnimationGraphValidator.validateComplete(base.graph(), policy);
        var remap = new HashMap<StartKey, StartKey>();
        var starts = new ArrayList<Start>();
        for (Start old : retain(base.graph(), base.starts()).starts()) {
            if (sequence <= old.eventSequence())
                throw new PaGraph.Unsupported("Seed activation sequence is not fresh");
            var age =
                    AnimationStartReport.age(old, activation.applyR(), activation.applyA(), policy);
            var key = new StartKey(sequence, old.nodeId());
            remap.put(new StartKey(old.eventSequence(), old.nodeId()), key);
            long bucket =
                    old.phaseDomain() == PhaseDomain.R
                            ? activation.applyR().tick()
                            : activation.applyA().tick();
            starts.add(
                    new Start(
                            sequence,
                            key.nodeId(),
                            old.layerTag(),
                            StartKind.SEED_CONTINUE,
                            AnchorClass.OBSERVER_APPLY,
                            old.phaseDomain(),
                            bucket,
                            Math.toIntExact(age.tick()),
                            0,
                            activation));
        }
        var nodes = new ArrayList<Node>();
        for (Node node : base.graph().nodes())
            nodes.add(
                    new Node(
                            node.id(),
                            node.domain(),
                            node.start() == null ? null : remap.get(node.start()),
                            node.data()));
        return retain(
                new AnimationGraphState(base.graph().roots(), nodes, base.graph().aliases()),
                starts);
    }

    /**
     * One step decision's deferred bookkeeping: the proof template for its newly allocated nodes
     * and the layer predecessor it replaced. The step freezes once into its final graph and {@link
     * #settle} binds every pending decision there; intermediate graphs never escape.
     */
    record Pending(Start proof, StartKey predecessor) {}

    /**
     * The anchor class of the predecessor's start: the exact pre-step start, else the same-step
     * pending whose sequence it carries (one decision binds one anchor class; sequences are unique
     * per step). Null when the layer had no animation.
     */
    static AnchorClass priorAnchor(List<Start> base, List<Pending> prior, StartKey predecessor) {
        if (predecessor == null) return null;
        for (Start start : base)
            if (start.eventSequence() == predecessor.eventSequence()
                    && start.nodeId() == predecessor.nodeId()) return start.anchorClass();
        for (Pending pending : prior)
            if (pending.proof().eventSequence() == predecessor.eventSequence())
                return pending.proof().anchorClass();
        return null;
    }

    /**
     * Binds every pending decision's newly allocated nodes from the step's final graph: a node
     * start whose sequence names a pending decision takes that decision's proof, exactly as {@link
     * #after} does per decision. Unknown sequences are refused, never guessed.
     */
    static Result settle(
            Result base,
            AnimationGraphState graph,
            List<Pending> pendings,
            AnimationPolicy policy) {
        var starts = new ArrayList<>(base.starts());
        var known = new HashSet<StartKey>();
        for (Start start : starts) known.add(new StartKey(start.eventSequence(), start.nodeId()));
        for (Node node : graph.nodes()) {
            StartKey key = node.start();
            if (key == null || !known.add(key)) continue;
            Pending pending = null;
            for (Pending candidate : pendings)
                if (candidate.proof().eventSequence() == key.eventSequence()) {
                    pending = candidate;
                    break;
                }
            if (pending == null) throw new PaGraph.Unsupported("Unproven PA node");
            var proof = pending.proof();
            starts.add(
                    new Start(
                            key.eventSequence(),
                            key.nodeId(),
                            proof.layerTag(),
                            proof.startKind(),
                            proof.anchorClass(),
                            policy.domain(proof.layerTag()),
                            proof.phaseAnchor(),
                            proof.u0(),
                            pending.predecessor() == null ? 0 : pending.predecessor().nodeId(),
                            proof.proof()));
        }
        return retain(graph, starts);
    }

    /** One integer domain step, never a retained fractional setup or an entity/client tick. */
    public static Result tick(
            Result base,
            PhaseDomain domain,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            PaGraph.Sources sources) {
        var scratch = PaGraph.Stack.materialize(base.graph(), catalog, policy, sources);
        scratch.tick(domain);
        return retain(scratch.freeze(base.graph()), base.starts());
    }

    /**
     * Install a capsule in its current integer bucket. Does not tick or dispatch its native
     * payload. The supplied start is an authorized proof template; native allocation supplies its
     * node id.
     */
    public static Result apply(
            Result base,
            Patch patch,
            Start proof,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            PaGraph.Sources sources) {
        if (proof.layerTag() != patch.layer()
                || proof.phaseDomain() != policy.domain(patch.layer()))
            throw new PaGraph.Unsupported("PA patch/proof domain mismatch");
        AnimationStartReport.validateStart(proof);
        var scratch = PaGraph.Stack.materialize(base.graph(), catalog, policy, sources);
        scratch.stableEventIds();
        var predecessor = scratch.start(patch.layer());
        scratch.apply(patch, () -> proof.eventSequence());
        return after(base, scratch, proof, predecessor, policy);
    }

    private static Result after(
            Result base,
            PaGraph.Stack scratch,
            Start proof,
            StartKey predecessor,
            AnimationPolicy policy) {
        return settle(
                base,
                scratch.freeze(base.graph()),
                List.of(new Pending(proof, predecessor)),
                policy);
    }

    /**
     * Packetless TaCZ decisions use their authoritative input/resource values, reevaluating native
     * eligibility on the observer graph in native {@code setRotationAnglesHead} order: the stop
     * gate (no gun, sleeping, climbing, swimming, fall-flying) stops any of the four layers
     * whatever the evaluator; otherwise a display without PA keeps its layers (vanilla formulas)
     * and a PA display selects its loops and rotation. An observer predecessor owns its layer until
     * native expiry. A null {@code Tacz} is the no-gun state.
     */
    public static Result taczDecision(
            Result base,
            Decision decision,
            AnimationState.Placement input,
            TaczAssets assets,
            AnimationPolicy policy,
            PaGraph.Sources sources) {
        var scratch = PaGraph.Stack.materialize(base.graph(), assets.catalog(), policy, sources);
        var pending = taczStep(scratch, base.starts(), List.of(), decision, input, assets, policy);
        return settle(base, scratch.freeze(base.graph()), List.of(pending), policy);
    }

    /**
     * Runs one TaCZ decision on the step scratch without freezing. A decision that re-selects the
     * incumbent installs nothing (the native same-name/active-once guards), so it also copies no
     * clip; the pending is then bookkeeping-only and the settle binds nothing for it. {@code base}
     * is the step's pre-step starts and {@code prior} the earlier pendings, in order, which the
     * projected-successor read needs.
     */
    static Pending taczStep(
            PaGraph.Stack scratch,
            List<Start> base,
            List<Pending> prior,
            Decision decision,
            AnimationState.Placement input,
            TaczAssets assets,
            AnimationPolicy policy) {
        var patch = decision.patch();
        var tacz = patch.tacz();
        var vanilla = patch.vanilla();
        if (vanilla == null || input == null)
            throw new PaGraph.Unsupported("Missing TaCZ decision input closure");
        var display = tacz == null ? null : assets.require(tacz);
        scratch.stableEventIds();
        LayerTag layer = decision.layer();
        var predecessor = scratch.start(layer);
        var tag =
                scratch.associated(PaGraph.ROTATION_KEY) == scratch.layer(LayerTag.PA_42)
                        ? LayerTag.PA_42
                        : LayerTag.PA_96;
        if (layer != LayerTag.PA_93
                && layer != LayerTag.PA_94
                && layer != LayerTag.PA_95
                && layer != tag) throw new PaGraph.Unsupported("Not a TaCZ layer: " + layer);
        Outcome outcome;
        var nativeLayer = scratch.layer(layer);
        boolean replacement = nativeLayer.isActive();
        long sequence = decision.id().eventSequence();
        if (TaczPose.stopAll(tacz, vanilla))
            outcome = scratch.stop(layer, PaGraph.TACZ_FADE, sequence);
        else if (layer == LayerTag.PA_95)
            throw new PaGraph.Unsupported("The once layer has no packetless selection");
        else if (!display.playerAnimator()) outcome = Outcome.NO_CHANGE;
        else {
            var flags = vanilla.flags();
            var selected =
                    TaczPose.select(
                            vanilla.pose(),
                            flags.swimming(),
                            flags.passenger(),
                            flags.onGround(),
                            flags.creativeFlying(),
                            flags.sprinting(),
                            input.walk().speed(),
                            tacz.aimProgress());
            String name =
                    layer == LayerTag.PA_93
                            ? selected.lower()
                            : layer == LayerTag.PA_94 ? selected.upper() : "empty";
            if (name == null) outcome = Outcome.NO_CHANGE;
            else if (layer == tag)
                outcome = scratch.rotation(layer, display.clips().get(name), sequence);
            else outcome = scratch.loop(layer, display.loop(name), name, sequence);
        }
        var anchor = priorAnchor(base, prior, predecessor);
        boolean projected =
                anchor != null
                        && anchor != AnchorClass.CANONICAL_TICK
                        && !replacement
                        && layer != tag;
        var proof =
                new Start(
                        sequence,
                        1,
                        layer,
                        outcome == Outcome.STOPPED
                                ? StartKind.STOP_FADE
                                : replacement ? StartKind.REPLACE : StartKind.NEW,
                        projected ? AnchorClass.PROJECTED_SUCCESSOR : AnchorClass.CANONICAL_TICK,
                        policy.domain(layer),
                        decision.id().serverTick(),
                        0,
                        predecessor == null ? 0 : predecessor.nodeId(),
                        projected
                                ? new ProjectedSuccessor(
                                        decision.id().serverTick(),
                                        decision.id().nativeStage(),
                                        decision.input().contentId(),
                                        decision.transitionOrdinal())
                                : new CanonicalTick(
                                        decision.id().serverTick(), decision.id().nativeStage()));
        return new Pending(proof, predecessor);
    }

    /**
     * Complete bounded tail in one domain. Every boundary has an explicit journal entry, including
     * an empty one; missing input/step never borrows a later H. U4 composes LR boundary operations
     * with tick/apply/taczDecision when projecting a mixed family schedule.
     */
    public static Result project(
            Result base,
            PhaseDomain domain,
            long from,
            long to,
            Map<Long, List<Decision>> journal,
            Map<InputRef, AnimationState.Placement> inputs,
            TaczAssets assets,
            AnimationPolicy policy,
            PaGraph.Sources sources) {
        if (from < 0 || to < from || to - from > 40)
            throw new PaGraph.Unsupported("Missing bounded PA continuation seed");
        Result result = base;
        for (long n = from; n < to; ) {
            n++;
            var decisions = journal.get(n);
            if (decisions == null)
                throw new PaGraph.Unsupported("Missing PA reducer boundary: " + n);
            result = tick(result, domain, assets.catalog(), policy, sources);
            long sequence = -1;
            for (var decision : decisions) {
                if (decision.id().serverTick() != n
                        || decision.id().eventSequence() <= sequence
                        || decision.id().nativeStage().compareTo(NativeStage.NATIVE_TICK) <= 0)
                    throw new PaGraph.Unsupported("TaCZ decision stage/order mismatch");
                sequence = decision.id().eventSequence();
                if (policy.domain(decision.layer()) == domain)
                    result =
                            taczDecision(
                                    result,
                                    decision,
                                    inputs.get(decision.input()),
                                    assets,
                                    policy,
                                    sources);
            }
        }
        return result;
    }

    /**
     * Family half of server-derived seed folding. Caller validates the prefix and retains the
     * fold's immutable R/A recipe coordinates; no clock is inferred from canonical serverTick.
     * Resource leases and old-seed acknowledgement remain U7/U8 responsibilities.
     */
    public static ObserverSeed fold(
            ObserverSeed previous,
            SeedRef next,
            AnimationState context,
            Result result,
            long inputPrefix,
            long eventPrefix,
            long decisionPrefix,
            List<Definition> dependencies,
            Digest reducerDigest) {
        result = retain(result.graph(), result.starts());
        if (!previous.identity().sameGeneration(context.identity())
                || next.resourceGeneration() != previous.ref().resourceGeneration()
                || next.targetEpoch() != previous.ref().targetEpoch()
                || inputPrefix < previous.inputPrefix()
                || eventPrefix < previous.eventPrefix()
                || decisionPrefix < previous.decisionPrefix()
                || result.starts().stream().anyMatch(s -> s.eventSequence() > eventPrefix)
                || !previous.reducerDigest().equals(reducerDigest))
            throw new PaGraph.Unsupported("Incompatible observer seed fold");
        return new ObserverSeed(
                context.identity(),
                next,
                previous.ref(),
                context,
                result.graph(),
                inputPrefix,
                eventPrefix,
                decisionPrefix,
                result.starts(),
                dependencies,
                reducerDigest);
    }
}
