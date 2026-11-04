package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationEvent.*;
import com.lunares.blockfield.common.combat.AnimationGraphState.Node;
import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationStartReport.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.*;

/** Bounded family replay. U7/U8 authorize prefixes and retain the seed's R/A coordinates. */
public final class ParCoolProjection {
    private ParCoolProjection() {}

    /**
     * Operation result, not another wire layout. Input/transition anchors remain in the closure.
     */
    public record Result(ParCoolFrame frame, List<Start> starts) {
        public Result {
            Objects.requireNonNull(frame);
            starts =
                    starts.stream()
                            .sorted(
                                    Comparator.comparingLong(Start::eventSequence)
                                            .thenComparingInt(Start::nodeId))
                            .toList();
            AnimationSchema.count(starts.size(), AnimationSchema.MAX_NODES, "ParCool starts");
        }
    }

    /**
     * One ordered family stage over immutable values. Implementations must reject missing input;
     * neither a later checkpoint nor a live entity is an implicit input to an earlier boundary.
     */
    @FunctionalInterface
    public interface Stages {
        Result apply(Result base, long tick, NativeStage stage, List<Decision> decisions);
    }

    /** Canonical checkpoints replace derivation, without ticking their own bucket again. */
    public static Result canonical(
            Result base,
            long from,
            long to,
            Map<Long, Result> complete,
            Map<Long, List<Decision>> journal,
            AnimationPolicy policy,
            Stages stages) {
        return replay(base, from, to, complete, journal, policy, stages);
    }

    /**
     * An observer continuation never imports canonical tick checkpoints. Its bucket comes from the
     * retained application/recipe ledger, not checkpoint.serverTick (which may be above A).
     * Fractional rendering is performed only after this integer replay, in fresh native scratch.
     */
    public static Result observer(
            ObserverSeed seed,
            long from,
            long to,
            Map<Long, List<Decision>> journal,
            AnimationPolicy policy,
            Stages stages) {
        validateSeed(seed, policy);
        for (Start start : seed.starts())
            if (parcool(start))
                AnimationStartReport.age(start, Time.whole(from), Time.whole(from), policy);
        return replay(
                new Result(
                        new ParCoolFrame(seed.graph(), seed.checkpoint().parcool()), seed.starts()),
                from,
                to,
                Map.of(),
                journal,
                policy,
                stages);
    }

    private static Result replay(
            Result base,
            long from,
            long to,
            Map<Long, Result> complete,
            Map<Long, List<Decision>> journal,
            AnimationPolicy policy,
            Stages stages) {
        Objects.requireNonNull(stages);
        validate(base, policy);
        // Freeze the decision journal before the first callback can mutate its caller's lists.
        var steps = new HashMap<Long, List<Decision>>();
        journal.forEach((tick, decisions) -> steps.put(tick, List.copyOf(decisions)));
        var checkpoints = Map.copyOf(complete);
        return AnimationResolver.replay(
                from,
                base,
                to,
                checkpoints,
                Map.copyOf(steps),
                new AnimationResolver.Reducer<>() {
                    private Result current;

                    @Override
                    public void restore(Result value) {
                        validate(value, policy);
                        current = value;
                    }

                    @Override
                    public void stage(long tick, NativeStage stage, List<Decision> decisions) {
                        current =
                                ParCoolProjection.stage(
                                        stages, current, tick, stage, decisions, policy);
                    }

                    @Override
                    public Result freeze(long tick) {
                        return current;
                    }
                });
    }

    /**
     * One ordered family stage over a composed graph, validated: the result must be a complete
     * ParCool closure and leave every other family's nodes, roots, aliases and starts unchanged.
     */
    public static Result stage(
            Stages stages,
            Result before,
            long tick,
            NativeStage stage,
            List<Decision> decisions,
            AnimationPolicy policy) {
        var current = Objects.requireNonNull(stages.apply(before, tick, stage, decisions));
        validate(current, policy);
        // The same graph object has the same foreign nodes, roots and aliases.
        boolean sameGraph = before.frame().graph() == current.frame().graph();
        if (!sameGraph && !foreignNodes(before).equals(foreignNodes(current))
                || !sameGraph && !foreignRoots(before).equals(foreignRoots(current))
                || !foreignStarts(before).equals(foreignStarts(current))
                || !sameGraph
                        && !before.frame()
                                .graph()
                                .aliases()
                                .equals(current.frame().graph().aliases()))
            throw new IllegalArgumentException("ParCool stage changed another family");
        return current;
    }

    /**
     * Server-issued activation preserves every native scalar and whole age, including passive state
     * whose phase key is not an animator graph node. No constructor or integer tick runs.
     */
    public static Result seedContinue(
            Result base, long sequence, ObserverApply activation, AnimationPolicy policy) {
        validate(base, policy);
        var keys = new HashMap<StartKey, StartKey>();
        var starts = new ArrayList<Start>();
        for (Start old : base.starts()) {
            if (sequence <= old.eventSequence())
                throw new IllegalArgumentException("ParCool seed activation sequence is not fresh");
            if (!parcool(old)) {
                starts.add(old);
                keys.put(
                        new StartKey(old.eventSequence(), old.nodeId()),
                        new StartKey(old.eventSequence(), old.nodeId()));
                continue;
            }
            var age =
                    AnimationStartReport.age(old, activation.applyR(), activation.applyA(), policy);
            var key = new StartKey(sequence, old.nodeId());
            keys.put(new StartKey(old.eventSequence(), old.nodeId()), key);
            starts.add(
                    new Start(
                            sequence,
                            old.nodeId(),
                            old.layerTag(),
                            StartKind.SEED_CONTINUE,
                            AnchorClass.OBSERVER_APPLY,
                            old.phaseDomain(),
                            old.phaseDomain() == PhaseDomain.R
                                    ? activation.applyR().tick()
                                    : activation.applyA().tick(),
                            Math.toIntExact(age.tick()),
                            0,
                            activation));
        }
        var nodes = new ArrayList<Node>();
        for (Node node : base.frame().graph().nodes())
            nodes.add(
                    new Node(
                            node.id(),
                            node.domain(),
                            node.start() == null ? null : keys.get(node.start()),
                            node.data()));
        var graph =
                new AnimationGraphState(
                        base.frame().graph().roots(), nodes, base.frame().graph().aliases());
        var old = base.frame().state();
        var state =
                new AnimationState.ParCool(
                        old.animator(),
                        keys.getOrDefault(old.passiveStart(), old.passiveStart()),
                        old.option(),
                        old.passive(),
                        old.actions(),
                        old.hangGrid(),
                        old.diveGrid());
        var result = new Result(new ParCoolFrame(graph, state), starts);
        validate(result, policy);
        return result;
    }

    /**
     * Fold only a validated prefix. Caller leases dependencies and retains the projected bucket and
     * R/A recipe beside this U1 seed until activation/acknowledgement, including old seeds.
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
            Digest reducerDigest,
            AnimationPolicy policy) {
        validateSeed(previous, policy);
        validate(result, policy);
        if (!previous.identity().sameGeneration(context.identity())
                || next.contentId() == previous.ref().contentId()
                || next.resourceGeneration() != previous.ref().resourceGeneration()
                || next.targetEpoch() != previous.ref().targetEpoch()
                || inputPrefix < previous.inputPrefix()
                || eventPrefix < previous.eventPrefix()
                || decisionPrefix < previous.decisionPrefix()
                || result.starts().stream().anyMatch(start -> start.eventSequence() > eventPrefix)
                || !previous.reducerDigest().equals(reducerDigest))
            throw new IllegalArgumentException("Incompatible ParCool observer seed fold");
        var checkpoint = withFrame(context, result.frame());
        AnimationSchema.validateFinite(checkpoint);
        var folded =
                new ObserverSeed(
                        context.identity(),
                        next,
                        previous.ref(),
                        checkpoint,
                        result.frame().graph(),
                        inputPrefix,
                        eventPrefix,
                        decisionPrefix,
                        result.starts(),
                        dependencies,
                        reducerDigest);
        validateSeed(folded, policy);
        return folded;
    }

    public static AnimationState withFrame(AnimationState context, ParCoolFrame frame) {
        return new AnimationState(
                context.identity(),
                context.serverTick(),
                context.sampleRevision(),
                context.eventWatermark(),
                context.placement(),
                context.vanilla(),
                context.stance(),
                context.equipment(),
                context.tacz(),
                context.lr(),
                frame.graph(),
                frame.state(),
                context.create(),
                context.sbw(),
                context.decisions());
    }

    private static void validateSeed(ObserverSeed seed, AnimationPolicy policy) {
        AnimationSchema.nonnegative(seed.inputPrefix(), "ParCool seed input prefix");
        AnimationSchema.nonnegative(seed.decisionPrefix(), "ParCool seed decision prefix");
        if (!seed.identity().sameGeneration(seed.checkpoint().identity())
                || seed.identity().policyGeneration() != policy.generation()
                || seed.ref().targetEpoch() != seed.identity().targetEpoch()
                || seed.ref().resourceGeneration() != seed.identity().resourceGeneration())
            throw new IllegalArgumentException("ParCool seed identity mismatch");
        AnimationSchema.validateFinite(seed);
        var id = seed.identity();
        AnimationStartReport.validate(
                new AnimationStartReport(
                        List.of(
                                new Victim(
                                        id.entityId(),
                                        id.targetEpoch(),
                                        id.seatGeneration(),
                                        id.teleportGeneration(),
                                        id.subscriptionGeneration(),
                                        seed.ref().contentId(),
                                        seed.eventPrefix(),
                                        seed.reducerDigest(),
                                        seed.starts(),
                                        // A seed carries no observer application records.
                                        List.of(),
                                        seed.reducerDigest()))));
        validate(
                new Result(
                        new ParCoolFrame(seed.graph(), seed.checkpoint().parcool()), seed.starts()),
                policy);
    }

    /** Complete composed graph, ParCool closure and contributing passive start. */
    public static void validate(Result result, AnimationPolicy policy) {
        AnimationGraphValidator.validateComplete(result.frame().graph(), policy);
        var keys = new HashMap<StartKey, Start>();
        var ids = new HashSet<Integer>();
        for (Start start : result.starts()) {
            AnimationStartReport.validateStart(start);
            if (start.phaseDomain() != policy.domain(start.layerTag())
                    || !ids.add(start.nodeId())
                    || keys.put(new StartKey(start.eventSequence(), start.nodeId()), start) != null)
                throw new IllegalArgumentException("Invalid/duplicate ParCool closure start");
        }
        for (Node node : result.frame().graph().nodes()) {
            if (node.start() != null && !keys.containsKey(node.start()))
                throw new IllegalArgumentException("Missing contributing ParCool graph start");
            if (node.data() instanceof AnimationGraphState.Animator
                    && (node.start() == null
                            || keys.get(node.start()).layerTag() != LayerTag.PARCOOL_ACTIVE))
                throw new IllegalArgumentException("Missing/wrong ParCool animator phase");
        }
        Start passive = keys.get(result.frame().state().passiveStart());
        if (passive == null && passiveContributes(result.frame().state())
                || passive != null && passive.layerTag() != LayerTag.PARCOOL_PASSIVE)
            throw new IllegalArgumentException("Missing contributing ParCool passive start");
    }

    public static boolean passiveContributes(AnimationState.ParCool state) {
        // bf7 Animation.animatePost -> PassiveCustomAnimation.animate, with forced policy on.
        return state.animator() == null
                && !state.option().animationCanceled()
                && state.passive().fallingAnimationTick() >= 14
                && !ParCoolFrame.actions(state).get(ActionTag.DIVE.id()).doing();
    }

    private static boolean parcool(Start start) {
        return start.layerTag() == LayerTag.PARCOOL_ACTIVE
                || start.layerTag() == LayerTag.PARCOOL_PASSIVE;
    }

    private static List<Node> foreignNodes(Result result) {
        return result.frame().graph().nodes().stream()
                .filter(node -> !(node.data() instanceof AnimationGraphState.Animator))
                .toList();
    }

    private static List<AnimationGraphState.Root> foreignRoots(Result result) {
        return result.frame().graph().roots().stream()
                .filter(
                        root ->
                                root.layer() != LayerTag.PARCOOL_ACTIVE
                                        && root.layer() != LayerTag.PARCOOL_PASSIVE)
                .toList();
    }

    private static List<Start> foreignStarts(Result result) {
        return result.starts().stream().filter(start -> !parcool(start)).toList();
    }
}
