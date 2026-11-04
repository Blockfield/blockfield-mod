package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.StanceTransition;
import com.lunares.blockfield.common.combat.AnimationEvent.Decision;
import com.lunares.blockfield.common.combat.AnimationResources.ClipRef;
import com.lunares.blockfield.common.combat.AnimationResources.InputRef;
import com.lunares.blockfield.common.combat.AnimationStartReport.CanonicalTick;
import com.lunares.blockfield.common.combat.AnimationStartReport.ProjectedSuccessor;
import com.lunares.blockfield.common.combat.AnimationStartReport.Start;
import com.lunares.blockfield.common.combat.AnimationStartReport.StartKey;
import com.lunares.blockfield.common.combat.AnimationTypes.AnchorClass;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.NativeStage;
import com.lunares.blockfield.common.combat.AnimationTypes.Outcome;
import com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain;
import com.lunares.blockfield.common.combat.AnimationTypes.StartKind;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Integer-bucket LR operations over U3's graph and U1's start proofs. U7b/U8 authorize/dedupe
 * events, supply complete decision/input journals and own transport, timing and lifecycle.
 */
public final class LrProjection {
    private LrProjection() {}

    public static PaProjection.Result attack(
            PaProjection.Result base,
            ClipRef clip,
            Start proof,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            PaGraph.Sources sources) {
        requireLayer(proof, LayerTag.PA_40);
        requireNewActor(proof);
        return apply(
                base,
                proof,
                catalog,
                policy,
                sources,
                stack -> LrPose.attack(stack, clip, proof::eventSequence));
    }

    /** The native idle handler waits until the observer's upper attack releases its layer. */
    public static PaProjection.Result idle(
            PaProjection.Result base,
            ClipRef clip,
            String name,
            Start proof,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            PaGraph.Sources sources) {
        if (proof.layerTag() != LayerTag.PA_40 && proof.layerTag() != LayerTag.PA_41)
            throw new PaGraph.Unsupported("LR idle proof layer");
        requireNewActor(proof);
        return apply(
                base,
                proof,
                catalog,
                policy,
                sources,
                stack -> {
                    if (proof.layerTag() != LayerTag.PA_40 || !LrPose.attackActive(stack))
                        LrPose.idle(stack, proof.layerTag(), clip, name, proof::eventSequence);
                });
    }

    public static PaProjection.Result rotation(
            PaProjection.Result base,
            ClipRef clip,
            Start proof,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            PaGraph.Sources sources) {
        requireNewActor(proof);
        return apply(
                base,
                proof,
                catalog,
                policy,
                sources,
                stack -> {
                    requireLayer(proof, LrPose.rotationLayer(stack));
                    LrPose.rotation(stack, clip, proof::eventSequence);
                });
    }

    /** Call once per addressable LR root for native stopAll; no transition is invented if idle. */
    public static PaProjection.Result stop(
            PaProjection.Result base,
            Start proof,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            PaGraph.Sources sources) {
        if (proof.startKind() != StartKind.STOP_FADE)
            throw new PaGraph.Unsupported("LR stop requires a stop-fade proof");
        return apply(
                base,
                proof,
                catalog,
                policy,
                sources,
                stack -> LrPose.stop(stack, proof.layerTag(), proof::eventSequence));
    }

    /**
     * One packetless LR selection (native {@code onClientTick}, POST_NATIVE_TICK) for its layer at
     * integer boundary {@code decision.serverTick}, after that boundary's tick. Native eligibility
     * is reevaluated on the observer graph from the decision's authoritative Vanilla flags, the
     * Placement its InputRef names and the retained effective LR display. An upper idle that an
     * observer-anchored attack kept blocked starts as PROJECTED_SUCCESSOR at the first boundary the
     * attack is inactive; every other selection is CANONICAL_TICK. No client selector runs.
     */
    public static PaProjection.Result decision(
            PaProjection.Result base,
            Decision decision,
            AnimationState.Placement input,
            LrAssets assets,
            AnimationPolicy policy,
            PaGraph.Sources sources) {
        var scratch = PaGraph.Stack.materialize(base.graph(), assets.catalog(), policy, sources);
        var pending =
                decisionStep(scratch, base.starts(), List.of(), decision, input, assets, policy);
        return PaProjection.settle(base, scratch.freeze(base.graph()), List.of(pending), policy);
    }

    /**
     * Runs one LR decision on the step scratch without freezing, returning its deferred bookkeeping
     * for the step's single settle. Reads only the step's visible starts (pre-step plus earlier
     * pendings), never an intermediate graph.
     */
    static PaProjection.Pending decisionStep(
            PaGraph.Stack scratch,
            List<Start> base,
            List<PaProjection.Pending> prior,
            Decision decision,
            AnimationState.Placement input,
            LrAssets assets,
            AnimationPolicy policy) {
        var patch = decision.patch();
        var lr = patch.lr();
        var vanilla = patch.vanilla();
        if (lr == null || vanilla == null || input == null)
            throw new PaGraph.Unsupported("Missing LR decision input closure");
        if (decision.id().nativeStage() != NativeStage.POST_NATIVE_TICK)
            throw new PaGraph.Unsupported("LR selection stage");
        LrAssets.Display display = display(lr, assets);
        var flags = vanilla.flags();
        boolean suppressed = LrPose.suppressed(vanilla.pose(), flags.climbing(), flags.swimming());
        var delta = input.deltaMovement();
        String movement =
                LrPose.movement(
                        flags.onGround(),
                        delta.y(),
                        delta.x() * delta.x() + delta.z() * delta.z(),
                        flags.crouching(),
                        flags.sprinting());
        long sequence = decision.id().eventSequence();
        LayerTag layer = decision.layer();
        scratch.stableEventIds();
        StartKey predecessor = scratch.start(layer);
        boolean afterAttack =
                scratch.layer(layer).getAnimation() instanceof KeyframeAnimationPlayer actor
                        && Boolean.TRUE.equals(actor.getData().extraData.get(PaGraph.LR_ATTACK));
        var step =
                LrPose.select(
                        scratch,
                        display,
                        suppressed,
                        movement,
                        layer,
                        () -> sequence,
                        patch.stance() == null
                                ? null
                                : StanceTransition.snapshotOf(patch.stance(), null, null));
        // Only the upper idle waits on another actor's expiry (native attack guard).
        var anchor = PaProjection.priorAnchor(base, prior, predecessor);
        if (predecessor != null && anchor == null)
            throw new PaGraph.Unsupported("LR predecessor without a retained start");
        boolean projected =
                step.outcome() == Outcome.SELECTED
                        && layer == LayerTag.PA_40
                        && afterAttack
                        && anchor != null
                        && anchor != AnchorClass.CANONICAL_TICK;
        // A proof template; the real node ids replace it when starts are bound.
        var proof =
                new Start(
                        sequence,
                        predecessor != null && predecessor.nodeId() == 1 ? 2 : 1,
                        layer,
                        step.kind(),
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
        AnimationStartReport.validateStart(proof);
        return new PaProjection.Pending(proof, predecessor);
    }

    /**
     * Complete bounded mixed TaCZ/LR tail in one domain: per boundary one domain tick, then that
     * boundary's selections in journal order. Every boundary must exist (possibly empty); a missing
     * step or input never borrows a later H. Accepted events (attacks, TaCZ actions) are applied by
     * U8 at their own observer bucket with {@link #attack}/{@link PaProjection#apply}, after the
     * boundary's selections.
     */
    public static PaProjection.Result project(
            PaProjection.Result base,
            PhaseDomain domain,
            long from,
            long to,
            Map<Long, List<Decision>> journal,
            Map<InputRef, AnimationState.Placement> inputs,
            TaczAssets tacz,
            LrAssets lr,
            AnimationPolicy policy,
            PaGraph.Sources sources) {
        if (from < 0 || to < from || to - from > 40)
            throw new PaGraph.Unsupported("Missing bounded PA continuation seed");
        if (tacz != null && tacz.catalog() != lr.catalog())
            throw new PaGraph.Unsupported("Mixed families must share one PA catalog");
        PaProjection.Result result = base;
        for (long n = from; n < to; ) {
            n++;
            var decisions = journal.get(n);
            if (decisions == null)
                throw new PaGraph.Unsupported("Missing PA reducer boundary: " + n);
            result = PaProjection.tick(result, domain, lr.catalog(), policy, sources);
            long sequence = -1;
            for (var decision : decisions) {
                if (decision.id().serverTick() != n
                        || decision.id().eventSequence() <= sequence
                        || decision.id().nativeStage().compareTo(NativeStage.NATIVE_TICK) <= 0)
                    throw new PaGraph.Unsupported("PA decision stage/order mismatch");
                sequence = decision.id().eventSequence();
                if (policy.domain(decision.layer()) != domain) continue;
                var input = inputs.get(decision.input());
                if (decision.patch().lr() != null)
                    result = decision(result, decision, input, lr, policy, sources);
                // A TaCZ decision may carry a null Tacz (no gun held); taczDecision checks the
                // layer and the remaining closure.
                else if (tacz != null)
                    result =
                            PaProjection.taczDecision(
                                    result, decision, input, tacz, policy, sources);
                else throw new PaGraph.Unsupported("Decision without a PA family closure");
            }
        }
        return result;
    }

    /** Lr.display/config are null together (native getConfig null) and name retained assets. */
    private static LrAssets.Display display(AnimationState.Lr lr, LrAssets assets) {
        if ((lr.display() == null) != (lr.config() == null))
            throw new PaGraph.Unsupported("LR display/config presence mismatch");
        if (lr.display() == null) return null;
        var display = assets.require(lr.display());
        if (display.config() == null || !display.config().ref().equals(lr.config()))
            throw new PaGraph.Unsupported("LR effective config/checkpoint mismatch");
        return display;
    }

    private static void requireLayer(Start proof, LayerTag layer) {
        if (proof.layerTag() != layer) throw new PaGraph.Unsupported("LR operation/proof layer");
    }

    private static void requireNewActor(Start proof) {
        if (proof.startKind() != StartKind.NEW && proof.startKind() != StartKind.REPLACE)
            throw new PaGraph.Unsupported("LR selection requires a new/replacement proof");
    }

    private static PaProjection.Result apply(
            PaProjection.Result base,
            Start proof,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            PaGraph.Sources sources,
            Consumer<PaGraph.Stack> operation) {
        AnimationStartReport.validateStart(proof);
        if (proof.eventSequence() <= 0 || proof.u0() != 0)
            throw new PaGraph.Unsupported("LR transition requires a fresh zero-age start");
        if (proof.phaseDomain() != policy.domain(proof.layerTag()))
            throw new PaGraph.Unsupported("LR operation/proof domain");
        return run(
                base,
                proof.layerTag(),
                catalog,
                policy,
                sources,
                stack -> {
                    operation.accept(stack);
                    return null;
                },
                (ignored, predecessor) -> proof);
    }

    private interface ProofFor<T> {
        Start proof(T result, StartKey predecessor);
    }

    /**
     * Materializes fresh integer scratch, applies one operation without a tick, binds every newly
     * allocated start to the proof and keeps only reachable starts.
     */
    private static <T> PaProjection.Result run(
            PaProjection.Result base,
            LayerTag layer,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            PaGraph.Sources sources,
            Function<PaGraph.Stack, T> operation,
            ProofFor<T> proofFor) {
        var scratch = PaGraph.Stack.materialize(base.graph(), catalog, policy, sources);
        scratch.stableEventIds();
        StartKey predecessor = scratch.start(layer);
        T result = operation.apply(scratch);
        var graph = scratch.freeze(base.graph());
        var starts = new ArrayList<>(base.starts());
        var known = new HashSet<StartKey>();
        for (var start : starts) known.add(new StartKey(start.eventSequence(), start.nodeId()));
        Start proof = null;
        for (var node : graph.nodes()) {
            var key = node.start();
            if (key == null || !known.add(key)) continue;
            if (proof == null) {
                proof = proofFor.proof(result, predecessor);
                AnimationStartReport.validateStart(proof);
            }
            if (key.eventSequence() != proof.eventSequence())
                throw new PaGraph.Unsupported("Unproven LR node");
            starts.add(
                    new Start(
                            key.eventSequence(),
                            key.nodeId(),
                            proof.layerTag(),
                            proof.startKind(),
                            proof.anchorClass(),
                            proof.phaseDomain(),
                            proof.phaseAnchor(),
                            proof.u0(),
                            predecessor == null ? 0 : predecessor.nodeId(),
                            proof.proof()));
        }
        return PaProjection.retain(graph, starts);
    }
}
