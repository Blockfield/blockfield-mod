package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import com.lunares.blockfield.oracle.LrTransitionNativeCases;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.lunares.blockfield.oracle.PaGraphNativeCases;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.*;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LrTransitionOracleFixture {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nativeLrTransitionContinuation() {
        AnimSyncOracleRunner.run(
                "lr",
                "lr-transition-continuation",
                () -> {
                    var result = LrTransitionNativeCases.run();
                    OracleTransformAudit.requireConfig("playerAnimator-common.mixins.json");
                    OracleTransformAudit.requireConfig("lrtactical.mixins.json");
                    return result;
                });
    }

    @Test
    void observerStartsAndContinuation() {
        AnimSyncOracleRunner.run(
                "lr",
                "lr-observer-continuation",
                () -> {
                    var result = LrTransitionNativeCases.observer();
                    OracleTransformAudit.requireConfig("lrtactical.mixins.json");
                    return result;
                });
    }

    @Test
    void nativeIntegrationLeaves() {
        AnimSyncOracleRunner.run(
                "lr",
                "lr-native-integration-leaves",
                () -> {
                    Class<?> integration =
                            Class.forName(
                                    "me.xjqsh.lrtactical.compat.player_animator.PlayerAnimatorIntegration");
                    Class<?> layerType = Class.forName(integration.getName() + "$AnimationLayer");
                    Object upper = layerType.getField("UPPER").get(null);
                    var upperId = (ResourceLocation) integration.getField("UPPER_LAYER").get(null);
                    var path = ResourceLocation.parse("fixture:lr");
                    var idleMethod =
                            integration.getMethod(
                                    "playIdleAnimation",
                                    AbstractClientPlayer.class,
                                    ResourceLocation.class,
                                    String.class,
                                    layerType,
                                    int.class);
                    var attackMethod =
                            integration.getMethod(
                                    "playAttackAnimation",
                                    AbstractClientPlayer.class,
                                    ResourceLocation.class,
                                    String.class,
                                    layerType,
                                    int.class);
                    var activeMethod =
                            integration.getMethod(
                                    "isAttackAnimationActive",
                                    AbstractClientPlayer.class,
                                    layerType);
                    Class<?> manager = Class.forName("me.xjqsh.lrtactical.util.IPAAssetManager");
                    var lookup =
                            manager.getMethod(
                                    "getAnimations", ResourceLocation.class, String.class);
                    int compared = 0;
                    try (var assets = mockStatic(manager)) {
                        var idle = PaGraphNativeCases.clip("idle", true);
                        var other = PaGraphNativeCases.clip("other", true);
                        var attack = PaGraphNativeCases.clip("attack", false);
                        for (var entry :
                                Map.of("idle", idle, "other", other, "attack", attack).entrySet())
                            assets.when(() -> lookup.invoke(null, path, entry.getKey()))
                                    .thenReturn(Optional.of(entry.getValue()));
                        assets.when(() -> lookup.invoke(null, path, "missing"))
                                .thenReturn(Optional.empty());
                        for (int boundary : new int[] {0, 2, 5, 7, 15, 24, 25, 32})
                            for (float fraction :
                                    new float[] {0, 1f / 65536, .30f, .85f, 1 - 1f / 65536}) {
                                var player = mock(AbstractClientPlayer.class);
                                var reference = new ModifierLayer<IAnimation>();
                                when(((IAnimatedPlayer) player)
                                                .playerAnimator_getAnimation(upperId))
                                        .thenReturn(reference);
                                var catalog = new PaGraph.Catalog(1);
                                var policy = PaGraphNativeCases.policy(PhaseDomain.A);
                                var graph =
                                        PaGraph.Stack.create(
                                                catalog,
                                                policy,
                                                PaGraphNativeCases.SOURCES,
                                                LayerTag.PA_96,
                                                1);
                                var idleRef = catalog.register(idle);
                                var otherRef = catalog.register(other);
                                var attackRef = catalog.register(attack);
                                var sequence = new java.util.concurrent.atomic.AtomicLong();
                                for (int tick = 0; tick <= boundary; tick++) {
                                    if (tick > 0) {
                                        if (reference.isActive()) reference.tick();
                                        graph.tick();
                                    }
                                    assertEquals(
                                            activeMethod.invoke(null, player, upper),
                                            LrPose.attackActive(graph));
                                    if (!(Boolean) activeMethod.invoke(null, player, upper)) {
                                        idleMethod.invoke(
                                                null,
                                                player,
                                                path,
                                                tick < 2 ? "idle" : "other",
                                                upper,
                                                4);
                                        LrPose.idle(
                                                graph,
                                                LayerTag.PA_40,
                                                tick < 2 ? idleRef : otherRef,
                                                tick < 2 ? "idle" : "other",
                                                sequence::incrementAndGet);
                                    }
                                    if (tick == 5 || tick == 7) {
                                        attackMethod.invoke(null, player, path, "attack", upper, 4);
                                        LrPose.attack(graph, attackRef, sequence::incrementAndGet);
                                    }
                                    if (tick == 9) {
                                        attackMethod.invoke(
                                                null, player, path, "missing", upper, 4);
                                        LrPose.attack(graph, null, sequence::incrementAndGet);
                                    }
                                }
                                var state = graph.freeze(AnimationGraphState.neutral());
                                var scratch =
                                        PaGraph.Stack.materialize(
                                                state, catalog, policy, PaGraphNativeCases.SOURCES);
                                reference.setupAnim(fraction);
                                scratch.sample(fraction);
                                for (String part :
                                        List.of(
                                                "body",
                                                "head",
                                                "leftArm",
                                                "rightArm",
                                                "leftLeg",
                                                "rightLeg"))
                                    for (var type : TransformType.values()) {
                                        var base = new Vec3f(.3f, -.2f, .5f);
                                        LrTransitionNativeCases.finiteBits(
                                                reference.get3DTransform(
                                                        part, type, fraction, base),
                                                scratch.transform(part, type, base));
                                        compared++;
                                    }
                            }
                    }
                    OracleTransformAudit.requireConfig("playerAnimator-common.mixins.json");
                    OracleTransformAudit.requireConfig("lrtactical.mixins.json");
                    return Map.of(
                            "nativePlayMethodsExecuted",
                            true,
                            "resourceBoundary",
                            "shared fixture clip instances (native metadata survives lookups)",
                            "rawBitComparisons",
                            compared,
                            "subsequentBoundary",
                            32);
                });
    }
}
