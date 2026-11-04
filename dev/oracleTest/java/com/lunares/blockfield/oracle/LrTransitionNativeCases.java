package com.lunares.blockfield.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationStartReport.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.*;
import dev.kosmx.playerAnim.api.layered.modifier.*;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** LR core differential cases executed independently in both production Knot environments. */
public final class LrTransitionNativeCases {
    private LrTransitionNativeCases() {}

    public static Map<String, Object> observer() {
        int compared = 0;
        for (var domain : PhaseDomain.values()) {
            var catalog = new PaGraph.Catalog(1);
            var policy = PaGraphNativeCases.policy(domain);
            var idle = catalog.register(PaGraphNativeCases.clip("idle", true));
            var attack = catalog.register(PaGraphNativeCases.clip("attack", false));
            var base =
                    new PaProjection.Result(
                            PaGraph.Stack.create(
                                            catalog,
                                            policy,
                                            PaGraphNativeCases.SOURCES,
                                            LayerTag.PA_96,
                                            1)
                                    .freeze(AnimationGraphState.neutral()),
                            List.of());
            var upper = start(1, LayerTag.PA_40, policy);
            base =
                    LrProjection.idle(
                            base, idle, "idle", upper, catalog, policy, PaGraphNativeCases.SOURCES);
            base =
                    LrProjection.attack(
                            base,
                            attack,
                            start(2, LayerTag.PA_40, policy),
                            catalog,
                            policy,
                            PaGraphNativeCases.SOURCES);
            var before = base;
            base =
                    LrProjection.idle(
                            base,
                            idle,
                            "idle",
                            start(3, LayerTag.PA_40, policy),
                            catalog,
                            policy,
                            PaGraphNativeCases.SOURCES);
            assertEquals(before, base, "idle cannot replace an observer-relative attack early");
            base =
                    LrProjection.idle(
                            base,
                            idle,
                            "idle",
                            start(4, LayerTag.PA_41, policy),
                            catalog,
                            policy,
                            PaGraphNativeCases.SOURCES);
            var continued =
                    PaProjection.seedContinue(
                            base,
                            5,
                            new ObserverApply(
                                    5,
                                    1,
                                    1,
                                    new Time(105, 30000),
                                    new Time(98, 30000),
                                    new Time(100, 30000),
                                    105,
                                    5),
                            policy);
            for (int tick = 0; tick < 30; tick++) {
                if (tick > 0) {
                    for (var step : PhaseDomain.values()) {
                        base =
                                PaProjection.tick(
                                        base, step, catalog, policy, PaGraphNativeCases.SOURCES);
                        continued =
                                PaProjection.tick(
                                        continued,
                                        step,
                                        catalog,
                                        policy,
                                        PaGraphNativeCases.SOURCES);
                    }
                }
                // No observed frame history enters either retained path.
                for (float fraction : new float[] {0, .30f, .85f, 1 - 1f / 65536}) {
                    var first =
                            PaGraph.Stack.materialize(
                                    base.graph(), catalog, policy, PaGraphNativeCases.SOURCES);
                    var second =
                            PaGraph.Stack.materialize(
                                    continued.graph(), catalog, policy, PaGraphNativeCases.SOURCES);
                    first.sample(fraction, .2f);
                    second.sample(fraction, .2f);
                    for (var type : TransformType.values()) {
                        finiteBits(
                                first.transform("body", type, Vec3f.ZERO),
                                second.transform("body", type, Vec3f.ZERO));
                        compared++;
                    }
                }
                assertEquals(
                        base.starts(), PaProjection.retain(base.graph(), base.starts()).starts());
            }
            assertThrows(
                    PaGraph.Unsupported.class,
                    () ->
                            LrProjection.rotation(
                                    before,
                                    idle,
                                    start(6, LayerTag.PA_42, policy),
                                    catalog,
                                    policy,
                                    PaGraphNativeCases.SOURCES));
            var stopTemplate = start(6, LayerTag.PA_41, policy);
            var stop =
                    new Start(
                            6,
                            1,
                            LayerTag.PA_41,
                            StartKind.STOP_FADE,
                            stopTemplate.anchorClass(),
                            stopTemplate.phaseDomain(),
                            stopTemplate.phaseAnchor(),
                            0,
                            0,
                            stopTemplate.proof());
            var stopped =
                    LrProjection.stop(base, stop, catalog, policy, PaGraphNativeCases.SOURCES);
            assertTrue(stopped.starts().stream().anyMatch(s -> s.eventSequence() == 6));
            assertTrue(
                    stopped.starts().stream().anyMatch(s -> s.eventSequence() == 4),
                    "stop fade keeps the live lower predecessor's start");
            assertThrows(
                    PaGraph.Unsupported.class,
                    () ->
                            LrProjection.stop(
                                    before, upper, catalog, policy, PaGraphNativeCases.SOURCES));
            var badAge =
                    new Start(
                            7,
                            1,
                            LayerTag.PA_40,
                            upper.startKind(),
                            upper.anchorClass(),
                            upper.phaseDomain(),
                            upper.phaseAnchor(),
                            1,
                            0,
                            start(7, LayerTag.PA_40, policy).proof());
            assertThrows(
                    PaGraph.Unsupported.class,
                    () ->
                            LrProjection.attack(
                                    before,
                                    attack,
                                    badAge,
                                    catalog,
                                    policy,
                                    PaGraphNativeCases.SOURCES));
        }
        return Map.of(
                "sameBucketImmediateApplications",
                true,
                "applicationCausalTimeAboveH",
                true,
                "seedContinuationRawBitComparisons",
                compared,
                "subsequentBoundaries",
                30,
                "idleBlockedByObserverAttack",
                true,
                "wrongRotationProofFailsClosed",
                true);
    }

    private static Start start(long sequence, LayerTag layer, AnimationPolicy policy) {
        return new Start(
                sequence,
                1,
                layer,
                StartKind.REPLACE,
                AnchorClass.OBSERVER_APPLY,
                policy.domain(layer),
                policy.domain(layer) == PhaseDomain.R ? 98 : 100,
                0,
                0,
                new ObserverApply(
                        sequence,
                        1,
                        1,
                        new Time(103, 30000),
                        new Time(98, 30000),
                        new Time(100, 30000),
                        102,
                        sequence));
    }

    public static Map<String, Object> run() throws Exception {
        int comparisons = 0;
        for (var domain : PhaseDomain.values())
            for (var winner : List.of(LayerTag.PA_42, LayerTag.PA_96)) {
                var policy = PaGraphNativeCases.policy(domain);
                var catalog = new PaGraph.Catalog(1);
                var idle = PaGraphNativeCases.clip("idle", true);
                var attack = PaGraphNativeCases.clip("attack", false);
                var second = PaGraphNativeCases.clip("second", true);
                var idleRef = catalog.register(idle);
                var attackRef = catalog.register(attack);
                var secondRef = catalog.register(second);
                var canonical =
                        PaGraph.Stack.create(
                                catalog, policy, PaGraphNativeCases.SOURCES, winner, 1);
                canonical.stableEventIds();
                var sequence = new AtomicLong();
                for (int tick = 0; tick <= 36; tick++) {
                    if (tick > 0) canonical.tick();
                    LrPose.idle(
                            canonical, LayerTag.PA_41, idleRef, "idle", sequence::incrementAndGet);
                    if (!LrPose.attackActive(canonical))
                        LrPose.idle(
                                canonical,
                                LayerTag.PA_40,
                                tick < 3 ? idleRef : secondRef,
                                tick < 3 ? "idle" : "second",
                                sequence::incrementAndGet);
                    if (tick == 5 || tick == 7)
                        LrPose.attack(canonical, attackRef, sequence::incrementAndGet);
                    if (tick == 9) {
                        var incumbent = canonical.layer(LayerTag.PA_40).getAnimation();
                        long before = sequence.get();
                        assertEquals(
                                Outcome.MISSING_CLIP,
                                LrPose.attack(canonical, null, sequence::incrementAndGet));
                        assertSame(incumbent, canonical.layer(LayerTag.PA_40).getAnimation());
                        assertEquals(before, sequence.get());
                    }
                    if (tick >= 29) {
                        LrPose.stop(canonical, LayerTag.PA_40, sequence::incrementAndGet);
                        LrPose.stop(canonical, LayerTag.PA_41, sequence::incrementAndGet);
                    }
                    var graph = wire(canonical.freeze(AnimationGraphState.neutral()));
                    assertEquals(
                            graph,
                            PaGraph.Stack.materialize(
                                            graph, catalog, policy, PaGraphNativeCases.SOURCES)
                                    .freeze(graph));
                    var before = AnimationResources.Digest.hash(AnimationSchema.encode(graph));
                    for (float p : new float[] {0, 1f / 65536, .30f, .85f, 1 - 1f / 65536}) {
                        var expectedUpper = reference(idle, attack, second, tick, true);
                        var expectedLower = reference(idle, attack, second, tick, false);
                        float lowerP = domain == PhaseDomain.R ? .2f : p;
                        expectedUpper.setupAnim(p);
                        expectedLower.setupAnim(lowerP);
                        var scratch =
                                PaGraph.Stack.materialize(
                                        graph, catalog, policy, PaGraphNativeCases.SOURCES);
                        scratch.sample(p, .2f);
                        for (String part :
                                List.of(
                                        "body",
                                        "head",
                                        "leftArm",
                                        "rightArm",
                                        "leftLeg",
                                        "rightLeg"))
                            for (var type : TransformType.values()) {
                                var base =
                                        type == TransformType.SCALE
                                                ? new Vec3f(1, 1, 1)
                                                : new Vec3f(.3f, -.2f, .5f);
                                var expected =
                                        expectedLower.get3DTransform(
                                                part,
                                                type,
                                                lowerP,
                                                expectedUpper.get3DTransform(part, type, p, base));
                                finiteBits(expected, scratch.transform(part, type, base));
                                comparisons++;
                            }
                        assertEquals(
                                before,
                                AnimationResources.Digest.hash(AnimationSchema.encode(graph)));
                    }
                    assertFalse(catalog.clip(idleRef).extraData.containsKey("lr_attack_action"));
                    assertFalse(catalog.clip(attackRef).extraData.containsKey("lr_attack_action"));
                    assertEquals(
                            graph,
                            canonical.freeze(AnimationGraphState.neutral()),
                            "fractional scratch must not change the canonical native graph");
                }
                assertEquals(winner, LrPose.rotationLayer(canonical));
                assertEquals(
                        Outcome.SELECTED,
                        LrPose.rotation(canonical, idleRef, sequence::incrementAndGet));
                var latched = canonical.associated(PaGraph.ROTATION_KEY).getAnimation();
                ((KeyframeAnimationPlayer) latched).stop();
                assertEquals(
                        Outcome.NO_CHANGE,
                        LrPose.rotation(canonical, secondRef, sequence::incrementAndGet));
                assertSame(latched, canonical.associated(PaGraph.ROTATION_KEY).getAnimation());
            }
        return Map.of(
                "rawBitComparisons",
                comparisons,
                "integerBoundaries",
                37,
                "lowerPolicies",
                List.of("A", "R"),
                "rotationWinners",
                List.of(42, 96),
                "missingClipKeepsIncumbent",
                true,
                "retainedScratchImmutable",
                true,
                "nativeFrozenForcedClasses",
                true);
    }

    private static AnimationGraphState wire(AnimationGraphState graph) {
        var bytes = new net.minecraft.network.FriendlyByteBuf(Unpooled.buffer());
        try {
            AnimationSchema.write(bytes, graph);
            var decoded = AnimationSchema.readComplete(bytes, AnimationGraphState.class);
            assertEquals(graph, decoded);
            return decoded;
        } finally {
            bytes.release();
        }
    }

    private static ModifierLayer<IAnimation> reference(
            KeyframeAnimation idle,
            KeyframeAnimation attack,
            KeyframeAnimation second,
            int ticks,
            boolean upper)
            throws Exception {
        var layer = new ModifierLayer<IAnimation>();
        for (int tick = 0; tick <= ticks; tick++) {
            if (tick > 0 && layer.isActive()) layer.tick();
            if (!upper || !active(layer))
                idle(
                        layer,
                        (!upper || tick < 3 ? idle : second).copy(),
                        !upper || tick < 3 ? "idle" : "second",
                        upper ? 4 : 8);
            if (upper && (tick == 5 || tick == 7)) attack(layer, attack.copy());
            if (tick >= 29) layer.replaceAnimationWithFade(fade(8), null, false);
        }
        return layer;
    }

    private static boolean active(ModifierLayer<IAnimation> layer) {
        return layer.getAnimation() instanceof KeyframeAnimationPlayer actor
                && Boolean.TRUE.equals(actor.getData().extraData.get("lr_attack_action"))
                && actor.getCurrentTick() < actor.getData().endTick;
    }

    private static void attack(ModifierLayer<IAnimation> layer, KeyframeAnimation clip) {
        clip.extraData.put("lr_attack_action", true);
        var actor =
                new KeyframeAnimationPlayer(clip, 0).setFirstPersonMode(FirstPersonMode.DISABLED);
        layer.replaceAnimationWithFade(fade(4), actor, false);
    }

    private static void idle(
            ModifierLayer<IAnimation> layer, KeyframeAnimation clip, String name, int length)
            throws Exception {
        var current = layer.getAnimation();
        if (current instanceof KeyframeAnimationPlayer actor
                && actor.isActive()
                && name.equals(actor.getData().extraData.get("name"))) return;
        var actor =
                new KeyframeAnimationPlayer(clip, 0).setFirstPersonMode(FirstPersonMode.DISABLED);
        actor.getData().extraData.put("name", name);
        IAnimation begin = current;
        if (current instanceof KeyframeAnimationPlayer source) {
            var ctor =
                    Class.forName(
                                    "me.xjqsh.lrtactical.compat.player_animator.PlayerAnimatorIntegration$FrozenAnimationSnapshot")
                            .getDeclaredConstructor(KeyframeAnimationPlayer.class);
            ctor.setAccessible(true);
            begin = (IAnimation) ctor.newInstance(source);
        }
        var ctor =
                Class.forName(
                                "me.xjqsh.lrtactical.compat.player_animator.PlayerAnimatorIntegration$ForcedFadeModifier")
                        .getDeclaredConstructor(int.class, Ease.class);
        ctor.setAccessible(true);
        var fade = (AbstractFadeModifier) ctor.newInstance(length, Ease.INOUTSINE);
        fade.setBeginAnimation(begin);
        layer.addModifierLast(fade);
        layer.setAnimation(actor);
    }

    private static AbstractFadeModifier fade(int length) {
        return AbstractFadeModifier.standardFadeIn(length, Ease.INOUTSINE);
    }

    public static void finiteBits(Vec3f expected, Vec3f actual) {
        for (var value : List.of(expected, actual))
            assertTrue(
                    Float.isFinite(value.getX())
                            && Float.isFinite(value.getY())
                            && Float.isFinite(value.getZ()),
                    "nonfinite native output is unavailable, never equality");
        PaGraphNativeCases.bits(expected, actual);
    }
}
