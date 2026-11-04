package com.lunares.blockfield.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationEvent.*;
import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationStartReport.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Vec3f;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * U4 packetless LR selection on the observer graph (ADR4 §3.2), executed in both production Knot
 * environments: the server's canonical decisions for every tick are projected over an observer
 * whose attack event landed two ticks later, against a second LrPose replay of that observer timing
 * (projection consistency, not an independent native-selector oracle). Covers CANONICAL_TICK,
 * first-eligible PROJECTED_SUCCESSOR, the layer-41 R policy, missing config (stopAll) and a no-path
 * config.
 */
public final class LrSelectorNativeCases {
    private LrSelectorNativeCases() {}

    private static final PaGraph.Sources NONE = PaGraphNativeCases.SOURCES;
    private static final List<String> PARTS =
            List.of("body", "head", "leftArm", "rightArm", "leftLeg", "rightLeg");
    private static final float[] FRACTIONS = {1f / 65536, .3f, .85f, 1 - 1f / 65536};
    static final int SERVER_ATTACK = 11, OBSERVER_ATTACK = 13, TICKS = 72;

    /** Inputs of one canonical tick: held display and movement/suppression flags. */
    private record Tick(String display, boolean ground, double y, double dx, Pose pose) {
        double horizontal() {
            // Vec3.horizontalDistanceSqr, as the observer recomputes it from the Placement.
            return dx * dx + 0.0 * 0.0;
        }
    }

    private static Tick input(int t) {
        if (t >= 58) return new Tick(null, true, 0, 0, Pose.STANDING); // not a melee weapon
        if (t >= 54) return new Tick("fixture:no_path", true, 0, 0, Pose.STANDING);
        if (t >= 50) return new Tick("fixture:bat", true, 0, 0, Pose.SLEEPING);
        if (t >= 20 && t < 45) return new Tick("fixture:bat", true, 0, .1, Pose.STANDING);
        return new Tick("fixture:bat", true, 0, 0, Pose.STANDING);
    }

    public static LrAssets assets(PaGraph.Catalog catalog) {
        var assets = new LrAssets(catalog);
        var clips = new LinkedHashMap<String, KeyframeAnimation>();
        for (String name : List.of("u_idle", "u_walk", "l_idle", "l_walk", "empty"))
            clips.put(name, PaGraphNativeCases.clip(name, true));
        clips.put("atk", PaGraphNativeCases.clip("atk", false));
        assets.register(
                "fixture:bat",
                true,
                "fixture:lr",
                Map.of("idle", List.of("u_idle"), "walk", List.of("u_walk"), "hit", List.of("atk")),
                Map.of("idle", List.of("l_idle"), "walk", List.of("l_walk")),
                clips);
        assets.register(
                "fixture:no_path",
                true,
                null,
                Map.of("idle", List.of("u_walk")),
                Map.of(),
                Map.of());
        return assets;
    }

    public static Map<String, Object> projection() {
        int compared = 0, successors = 0, canonical = 0;
        for (var domain : PhaseDomain.values()) {
            var policy = PaGraphNativeCases.policy(domain);
            var catalog = new PaGraph.Catalog(1);
            var assets = assets(catalog);
            var server = PaGraph.Stack.create(catalog, policy, NONE, LayerTag.PA_96, 1);
            var journal = new HashMap<Long, List<Decision>>();
            var inputs = new HashMap<InputRef, AnimationState.Placement>();
            var serverSequence = new AtomicLong(1000);
            long decisionSequence = 0;
            for (int t = 0; t <= TICKS; t++) {
                var tick = input(t);
                var display = assets.display(tick.display());
                // INTER_TICK_ACCEPT before the tick's advance, then POST_NATIVE_TICK selection.
                if (t == SERVER_ATTACK)
                    LrPose.attack(server, display, "hit", 0, serverSequence::incrementAndGet);
                if (t > 0) server.tick();
                var movement =
                        LrPose.movement(tick.ground(), tick.y(), tick.horizontal(), false, false);
                boolean suppressed = LrPose.suppressed(tick.pose(), false, false);
                var steps =
                        LrPose.tick(
                                server,
                                display,
                                suppressed,
                                movement,
                                serverSequence::incrementAndGet);
                var input = new InputRef(1, 3, t, Digest.hash(new byte[] {(byte) t}));
                inputs.put(input, placement(tick.dx()));
                var lr = lr(display);
                var decisions = new ArrayList<Decision>();
                int ordinal = 0;
                for (var step : steps)
                    decisions.add(
                            new Decision(
                                    new EventId(
                                            t, ++decisionSequence, NativeStage.POST_NATIVE_TICK),
                                    step.layer(),
                                    step.kind(),
                                    step.outcome(),
                                    0,
                                    null,
                                    input,
                                    patch(step, lr, vanilla(tick)),
                                    ordinal++));
                journal.put((long) t, decisions);
                if (t == 28) assertEquals(Outcome.SELECTED, steps.getFirst().outcome());
                if (t == 27) assertEquals(Outcome.ACTIVE_ONCE_IGNORED, steps.getFirst().outcome());
            }

            // Observer: boundary 0 from an empty graph, then one bounded step per boundary; the
            // attack capsule lands in bucket 13 after that boundary's selections.
            var observer =
                    new PaProjection.Result(
                            PaGraph.Stack.create(catalog, policy, NONE, LayerTag.PA_96, 1)
                                    .freeze(AnimationGraphState.neutral()),
                            List.of());
            for (var decision : journal.get(0L))
                observer =
                        LrProjection.decision(
                                observer,
                                decision,
                                inputs.get(decision.input()),
                                assets,
                                policy,
                                NONE);
            var reference = PaGraph.Stack.create(catalog, policy, NONE, LayerTag.PA_96, 1);
            var referenceSequence = new AtomicLong(5000);
            var seen = new HashSet<StartKey>();
            for (int t = 0; t <= TICKS; t++) {
                var tick = input(t);
                var display = assets.display(tick.display());
                if (t > 0) {
                    observer = step(observer, t, journal, inputs, assets, policy);
                    reference.tick();
                }
                // The reference replays native order with the observer's own event timing.
                LrPose.tick(
                        reference,
                        display,
                        LrPose.suppressed(tick.pose(), false, false),
                        LrPose.movement(tick.ground(), tick.y(), tick.horizontal(), false, false),
                        referenceSequence::incrementAndGet);
                if (t == OBSERVER_ATTACK) {
                    var clip = display.clip("atk");
                    observer =
                            LrProjection.attack(
                                    observer, clip, apply(900, policy), catalog, policy, NONE);
                    LrPose.attack(reference, clip, referenceSequence::incrementAndGet);
                }
                compared += compare(observer, reference, catalog, policy, domain + "@" + t);
                for (var start : observer.starts()) {
                    if (!seen.add(new StartKey(start.eventSequence(), start.nodeId()))) continue;
                    if (start.anchorClass() == AnchorClass.PROJECTED_SUCCESSOR) {
                        // The observer attack (u0=0 in bucket 13, endTick 18) first releases the
                        // upper layer at boundary 31; the server's own attack released it at 28.
                        assertEquals(LayerTag.PA_40, start.layerTag());
                        assertEquals(OBSERVER_ATTACK + 18, t);
                        assertEquals(t, start.phaseAnchor());
                        assertEquals(t, ((ProjectedSuccessor) start.proof()).inputRef());
                        assertNotEquals(0, start.predecessorNodeId());
                        successors++;
                    } else if (start.anchorClass() == AnchorClass.CANONICAL_TICK) {
                        assertEquals(policy.domain(start.layerTag()), start.phaseDomain());
                        assertEquals(t, start.phaseAnchor());
                        canonical++;
                    }
                }
            }
        }
        assertEquals(2, successors, "one projected successor per policy");
        return Map.of(
                "referenceBoundary",
                "LrPose replay on native PA; native LR selector not executed",
                "rawBitComparisons",
                compared,
                "projectedSuccessors",
                successors,
                "canonicalStartsRetained",
                canonical,
                "boundaries",
                TICKS,
                "serverAttackTick",
                SERVER_ATTACK,
                "observerAttackBucket",
                OBSERVER_ATTACK);
    }

    private static PaProjection.Result step(
            PaProjection.Result observer,
            int n,
            Map<Long, List<Decision>> journal,
            Map<InputRef, AnimationState.Placement> inputs,
            LrAssets assets,
            AnimationPolicy policy) {
        // One integer per domain at the same boundary; their layers and decisions are disjoint.
        for (var domain : List.of(PhaseDomain.A, PhaseDomain.R))
            if (domain == PhaseDomain.A || policy.lowerBodyDomain() == PhaseDomain.R)
                observer =
                        LrProjection.project(
                                observer, domain, n - 1, n, journal, inputs, null, assets, policy,
                                NONE);
        return observer;
    }

    private static int compare(
            PaProjection.Result observer,
            PaGraph.Stack reference,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            String where) {
        int compared = 0;
        var frozen = reference.freeze(AnimationGraphState.neutral());
        for (float fraction : FRACTIONS) {
            var expected = PaGraph.Stack.materialize(frozen, catalog, policy, NONE);
            var actual = PaGraph.Stack.materialize(observer.graph(), catalog, policy, NONE);
            expected.sample(fraction, fraction);
            actual.sample(fraction, fraction);
            for (String part : PARTS)
                for (var type : TransformType.values()) {
                    var base = new Vec3f(.3f, -.2f, .5f);
                    try {
                        LrTransitionNativeCases.finiteBits(
                                expected.transform(part, type, base),
                                actual.transform(part, type, base));
                    } catch (AssertionError mismatch) {
                        throw new AssertionError(
                                where
                                        + " "
                                        + part
                                        + " "
                                        + type
                                        + " p="
                                        + fraction
                                        + "\nexpected "
                                        + dump(
                                                PaGraph.Stack.materialize(
                                                        frozen, catalog, policy, NONE))
                                        + "\nactual   "
                                        + dump(
                                                PaGraph.Stack.materialize(
                                                        observer.graph(), catalog, policy, NONE)),
                                mismatch);
                    }
                    compared++;
                }
        }
        return compared;
    }

    /** Failure context: each LR root's actor and modifier integer state. */
    private static String dump(PaGraph.Stack stack) {
        var out = new StringBuilder();
        for (var tag : List.of(LayerTag.PA_40, LayerTag.PA_41, LayerTag.PA_96)) {
            var layer = stack.layer(tag);
            out.append(tag).append('[').append(node(layer.getAnimation()));
            try {
                var field =
                        dev.kosmx.playerAnim.api.layered.ModifierLayer.class.getDeclaredField(
                                "modifiers");
                field.setAccessible(true);
                for (Object modifier : (List<?>) field.get(layer)) {
                    out.append(" ; ").append(modifier.getClass().getSimpleName());
                    if (modifier
                            instanceof
                            dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier fade) {
                        var owner =
                                dev.kosmx
                                        .playerAnim
                                        .api
                                        .layered
                                        .modifier
                                        .AbstractFadeModifier
                                        .class;
                        var time = owner.getDeclaredField("time");
                        var length = owner.getDeclaredField("length");
                        var begin = owner.getDeclaredField("beginAnimation");
                        time.setAccessible(true);
                        length.setAccessible(true);
                        begin.setAccessible(true);
                        out.append(" t=")
                                .append(time.get(fade))
                                .append('/')
                                .append(length.get(fade))
                                .append(" begin=")
                                .append(node(begin.get(fade)));
                    }
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            out.append("] ");
        }
        return out.toString();
    }

    private static String node(Object animation) {
        if (animation instanceof dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer actor)
            return actor.getData().extraData.get("name")
                    + "@"
                    + actor.getCurrentTick()
                    + (actor.isLoopStarted() ? "L" : "")
                    + (actor.isActive() ? "" : "x");
        if (animation == null) return "null";
        try {
            var source = animation.getClass().getDeclaredField("source");
            source.setAccessible(true);
            return "Frozen(" + node(source.get(animation)) + ")";
        } catch (ReflectiveOperationException e) {
            return animation.getClass().getSimpleName();
        }
    }

    private static Start apply(long sequence, AnimationPolicy policy) {
        return new Start(
                sequence,
                1,
                LayerTag.PA_40,
                StartKind.REPLACE,
                AnchorClass.OBSERVER_APPLY,
                PhaseDomain.A,
                OBSERVER_ATTACK,
                0,
                0,
                new ObserverApply(
                        sequence,
                        1,
                        1,
                        new Time(SERVER_ATTACK + 1, 3000),
                        new Time(OBSERVER_ATTACK - 1, 30000),
                        new Time(OBSERVER_ATTACK, 30000),
                        OBSERVER_ATTACK + 2,
                        sequence));
    }

    private static AnimationState.Lr lr(LrAssets.Display display) {
        boolean config = display != null && display.config() != null;
        return new AnimationState.Lr(
                config ? display.config().ref() : null,
                config ? display.ref() : null,
                null,
                null,
                null,
                0,
                null,
                false,
                false,
                LrPose.ROTATION_FADE,
                false,
                false,
                List.of());
    }

    private static Patch patch(
            LrPose.Step step, AnimationState.Lr lr, AnimationState.Vanilla vanilla) {
        return new Patch(
                step.layer(),
                step.kind(),
                step.outcome(),
                new AnimationGraphState(
                        List.of(
                                new AnimationGraphState.Root(
                                        AnimationGraphState.priority(step.layer()),
                                        step.layer(),
                                        null)),
                        List.of()),
                List.of(),
                null,
                null,
                null,
                lr,
                null,
                null,
                vanilla,
                null);
    }

    static AnimationState.Placement placement(double x) {
        var zero = new AnimationState.Vec3(0, 64, 0);
        return new AnimationState.Placement(
                zero,
                zero,
                new AnimationState.Angles(0, 0, 0, 0, 0, 0, 0, 0),
                new AnimationState.Walk(0, 0, 0),
                new AnimationState.Vec3(x, 0, 0),
                new AnimationState.Vec3(x, 0, 0),
                new AnimationState.VehicleAngles(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of());
    }

    private static AnimationState.Vanilla vanilla(Tick tick) {
        return new AnimationState.Vanilla(
                new AnimationState.Attack(0, 0, false, 0, null),
                0f,
                0f,
                0f,
                0f,
                new AnimationState.Vital(20, 0, 0, 0, 0, 0),
                tick.pose(),
                new AnimationState.Flags(
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        tick.ground(),
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false),
                0f,
                Arm.RIGHT,
                null,
                new AnimationState.Size(1, 1.8f, 0.6f, 1.62f, 1.62f),
                -0.08,
                new AnimationState.ItemUse(
                        false,
                        Hand.MAIN_HAND,
                        0,
                        0,
                        ArmPose.EMPTY,
                        ArmPose.EMPTY,
                        UseAnimation.NONE,
                        0,
                        0),
                false,
                null);
    }
}
