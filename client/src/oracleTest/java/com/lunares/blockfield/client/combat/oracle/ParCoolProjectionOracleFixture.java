package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alrex.parcool.client.animation.Animator;
import com.alrex.parcool.client.input.KeyBindings;
import com.alrex.parcool.common.attachment.common.Parkourability;
import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.lunares.blockfield.oracle.ParCoolProjectionCases;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ParCoolProjectionOracleFixture {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nativeCountersContinueThroughBoundedObserverSeeds() {
        AnimSyncOracleRunner.run(
                "parcool",
                "bounded-observer-seed-replay",
                () -> {
                    var result = ParCoolProjectionCases.run();
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    return result;
                });
    }

    @Test
    void neutralAndDiveSuppressedPassiveBranchesDoNotRequireAContributingStart() {
        AnimSyncOracleRunner.run(
                "parcool",
                "native-passive-start-eligibility",
                () -> {
                    var player = ParCoolCheckpointOracleFixture.player();
                    var fixture = ParCoolProjectionCases.initial(0);
                    var state = fixture.frame().state();
                    boolean previous =
                            com.lunares.blockfield.client.combat.AnimationPolicy.forcing();
                    {
                        com.lunares.blockfield.client.combat.AnimationPolicy.arm(
                                com.lunares.blockfield.oracle.ParCoolProjectionCases.POLICY);
                        com.lunares.blockfield.client.combat.AnimationPolicy.install();
                    }
                    try (var singleton = mockStatic(Minecraft.class)) {
                        singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
                        int[] neutral = null;
                        var rows = new ArrayList<Map<String, Object>>();
                        for (int falling : new int[] {0, 13, 14, 15}) {
                            var passive = new AnimationState.Passive(falling, 2, 3);
                            var checkpoint =
                                    new AnimationState.ParCool(
                                            null,
                                            state.passiveStart(),
                                            state.option(),
                                            passive,
                                            state.actions(),
                                            state.hangGrid(),
                                            state.diveGrid());
                            var nativePassive =
                                    (com.alrex.parcool.client.animation.PassiveCustomAnimation)
                                            ParCoolGraph.passive(
                                                    com.alrex.parcool.client.animation
                                                            .PassiveCustomAnimation.class,
                                                    passive);
                            var bits =
                                    ParCoolCheckpointOracleFixture.sample(
                                            ParCoolCheckpointOracleFixture.passiveAnimator(
                                                    nativePassive),
                                            player,
                                            new ParCoolFrame(
                                                            AnimationGraphState.neutral(),
                                                            checkpoint)
                                                    .scratchAttachment(),
                                            .5f,
                                            false,
                                            12.5f);
                            if (falling == 0) neutral = bits;
                            assertEquals(falling < 14, Arrays.equals(neutral, bits));
                            var missing =
                                    new ParCoolProjection.Result(
                                            new ParCoolFrame(
                                                    AnimationGraphState.neutral(), checkpoint),
                                            List.of());
                            if (falling < 14)
                                ParCoolProjection.canonical(
                                        missing,
                                        0,
                                        0,
                                        Map.of(),
                                        Map.of(),
                                        ParCoolProjectionCases.POLICY,
                                        (a, b, c, d) -> a);
                            else
                                assertThrows(
                                        IllegalArgumentException.class,
                                        () ->
                                                ParCoolProjection.canonical(
                                                        missing,
                                                        0,
                                                        0,
                                                        Map.of(),
                                                        Map.of(),
                                                        ParCoolProjectionCases.POLICY,
                                                        (a, b, c, d) -> a));
                            rows.add(
                                    Map.of(
                                            "fallingTick",
                                            falling,
                                            "nativeRootPartBits",
                                            Arrays.stream(bits).boxed().toList()));
                        }
                        var actions = new ArrayList<>(state.actions());
                        var oldDive = actions.get(ActionTag.DIVE.id());
                        actions.set(
                                ActionTag.DIVE.id(),
                                new AnimationState.ActionState(
                                        ActionTag.DIVE, true, 1, 0, 1, oldDive.inputs()));
                        var suppressed =
                                new AnimationState.ParCool(
                                        null,
                                        state.passiveStart(),
                                        state.option(),
                                        new AnimationState.Passive(15, 2, 3),
                                        actions,
                                        state.hangGrid(),
                                        state.diveGrid());
                        var nativePassive =
                                (com.alrex.parcool.client.animation.PassiveCustomAnimation)
                                        ParCoolGraph.passive(
                                                com.alrex.parcool.client.animation
                                                        .PassiveCustomAnimation.class,
                                                suppressed.passive());
                        assertArrayEquals(
                                neutral,
                                ParCoolCheckpointOracleFixture.sample(
                                        ParCoolCheckpointOracleFixture.passiveAnimator(
                                                nativePassive),
                                        player,
                                        new ParCoolFrame(AnimationGraphState.neutral(), suppressed)
                                                .scratchAttachment(),
                                        .5f,
                                        false,
                                        12.5f));
                        ParCoolProjection.canonical(
                                new ParCoolProjection.Result(
                                        new ParCoolFrame(AnimationGraphState.neutral(), suppressed),
                                        List.of()),
                                0,
                                0,
                                Map.of(),
                                Map.of(),
                                ParCoolProjectionCases.POLICY,
                                (a, b, c, d) -> a);
                        OracleTransformAudit.requireConfig("parcool.mixins.json");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return Map.of(
                                "cases",
                                rows,
                                "zeroStartNeutralRow",
                                true,
                                "diveSuppressesPassive",
                                true,
                                "unusedCreativeCounters",
                                List.of(2, 3));
                    } finally {
                        if (previous) {
                            com.lunares.blockfield.client.combat.AnimationPolicy.arm(
                                    com.lunares.blockfield.oracle.ParCoolProjectionCases.POLICY);
                            com.lunares.blockfield.client.combat.AnimationPolicy.install();
                        } else com.lunares.blockfield.client.combat.AnimationPolicy.restore();
                    }
                });
    }

    @Test
    void nativeAnimatorTickStagesContinueAcrossFoldWithoutRetickingTheSeed() {
        AnimSyncOracleRunner.run(
                "parcool",
                "native-animator-seed-schedule",
                () -> {
                    var adapter =
                            new ParCoolGraph(
                                    getClass().getClassLoader(),
                                    "com.alrex.parcool.client.animation.impl.");
                    var player = ParCoolCheckpointOracleFixture.player();
                    var fixture = ParCoolProjectionCases.initial(0);
                    var parkour = fixture.frame().scratchAttachment();
                    var rows = new ArrayList<Map<String, Object>>();
                    try (var attachment = mockStatic(Parkourability.class);
                            var keys = mockStatic(KeyBindings.class);
                            var singleton = mockStatic(Minecraft.class)) {
                        attachment.when(() -> Parkourability.get(player)).thenReturn(parkour);
                        singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
                        for (AnimatorTag tag : AnimatorTag.values()) {
                            for (Object constructor :
                                    ParCoolCheckpointOracleFixture.constructors(
                                            adapter.type(tag))) {
                                var original = (Animator) constructor;
                                var frame =
                                        ParCoolFrame.freeze(
                                                adapter,
                                                AnimationGraphState.neutral(),
                                                original,
                                                fixture.frame().state(),
                                                new AtomicInteger()::incrementAndGet,
                                                ignored -> new AnimationStartReport.StartKey(7, 1));
                                var initial = new ParCoolProjection.Result(frame, fixture.starts());
                                byte[] retainedGraph = AnimationSchema.encode(frame.graph());
                                var reference =
                                        ParCoolCheckpointOracleFixture.restore(
                                                adapter, frame.graph());
                                ParCoolProjection.Stages nativeStages =
                                        (base, tick, stage, decisions) -> {
                                            assertTrue(decisions.isEmpty());
                                            if (stage != NativeStage.NATIVE_TICK) return base;
                                            var actor =
                                                    ParCoolCheckpointOracleFixture.restore(
                                                            adapter, base.frame().graph());
                                            actor.tick(player);
                                            var key =
                                                    base.frame().graph().nodes().getFirst().start();
                                            return new ParCoolProjection.Result(
                                                    ParCoolFrame.freeze(
                                                            adapter,
                                                            base.frame().graph(),
                                                            actor,
                                                            base.frame().state(),
                                                            new AtomicInteger()::incrementAndGet,
                                                            ignored -> key),
                                                    base.starts());
                                        };
                                var projected =
                                        ParCoolProjection.observer(
                                                ParCoolProjectionCases.seed(initial),
                                                0,
                                                12,
                                                ParCoolProjectionCases.journal(0, 12),
                                                ParCoolProjectionCases.POLICY,
                                                nativeStages);
                                for (int i = 0; i < 12; i++) reference.tick(player);
                                var expected =
                                        ParCoolFrame.freeze(
                                                adapter,
                                                AnimationGraphState.neutral(),
                                                reference,
                                                fixture.frame().state(),
                                                new AtomicInteger()::incrementAndGet,
                                                ignored -> new AnimationStartReport.StartKey(7, 1));
                                assertEquals(
                                        expected,
                                        projected.frame(),
                                        tag + " scheduled integer state");
                                var seed = ParCoolProjectionCases.seed(initial);
                                seed =
                                        ParCoolProjection.fold(
                                                seed,
                                                new AnimationResources.SeedRef(
                                                        0, 0, 2, seed.reducerDigest()),
                                                ParCoolProjectionCases.context(projected),
                                                projected,
                                                12,
                                                7,
                                                7,
                                                List.of(),
                                                seed.reducerDigest(),
                                                ParCoolProjectionCases.POLICY);
                                seed = ParCoolProjectionCases.roundTrip(seed);
                                var folded =
                                        ParCoolProjection.observer(
                                                seed,
                                                12,
                                                24,
                                                ParCoolProjectionCases.journal(12, 24),
                                                ParCoolProjectionCases.POLICY,
                                                nativeStages);
                                var unfurled =
                                        ParCoolProjection.observer(
                                                ParCoolProjectionCases.seed(initial),
                                                0,
                                                24,
                                                ParCoolProjectionCases.journal(0, 24),
                                                ParCoolProjectionCases.POLICY,
                                                nativeStages);
                                assertEquals(unfurled, folded, tag + " fold continuation");
                                for (float fraction :
                                        new float[] {0, 1 / 65536f, .3f, .85f, 65535 / 65536f, 1}) {
                                    assertArrayEquals(
                                            ParCoolCheckpointOracleFixture.sample(
                                                    ParCoolCheckpointOracleFixture.restore(
                                                            adapter, expected.graph()),
                                                    player,
                                                    parkour,
                                                    fraction,
                                                    false,
                                                    12.5f),
                                            ParCoolCheckpointOracleFixture.sample(
                                                    ParCoolCheckpointOracleFixture.restore(
                                                            adapter, projected.frame().graph()),
                                                    player,
                                                    parkour,
                                                    fraction,
                                                    false,
                                                    12.5f),
                                            tag + " raw native root/model bits");
                                }
                                assertArrayEquals(
                                        retainedGraph, AnimationSchema.encode(frame.graph()));
                                rows.add(
                                        Map.of(
                                                "tag",
                                                tag.name(),
                                                "nativeTickCount",
                                                24,
                                                "integerStateU1Hex",
                                                HexFormat.of()
                                                        .formatHex(
                                                                AnimationSchema.encode(
                                                                        projected.frame().graph())),
                                                "rootModelSamples",
                                                6));
                            }
                        }
                    }
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    return Map.of(
                            "cases",
                            rows,
                            "registered",
                            26,
                            "children",
                            2,
                            "scope",
                            "native integer tick schedule and bounded seed machinery; uses native stage callback, not the pending deterministic geometry reducer or removal/selection");
                });
    }
}
