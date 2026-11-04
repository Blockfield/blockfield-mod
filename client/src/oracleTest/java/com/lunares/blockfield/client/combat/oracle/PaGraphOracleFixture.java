package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.ExhaustedReloadAnimation;
import com.lunares.blockfield.common.combat.PaAdjustments;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.lunares.blockfield.oracle.PaGraphNativeCases;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import dev.kosmx.playerAnim.api.layered.*;
import dev.kosmx.playerAnim.api.layered.modifier.*;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PaGraphOracleFixture {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nativeCheckpointAndContinuation() {
        AnimSyncOracleRunner.run(
                "playerAnimator",
                "graph-checkpoint-continuation",
                () -> {
                    var result = PaGraphNativeCases.checkpoint();
                    OracleTransformAudit.requireConfig("playerAnimator-common.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    return result;
                });
    }

    @Test
    void nativeFiniteStopsAndMissingClips() {
        AnimSyncOracleRunner.run(
                "playerAnimator", "finite-stop-missing-clip", PaGraphNativeCases::stopsAndMissing);
    }

    @Test
    void nativeZeroAgeSingularityIsNotClamped() {
        AnimSyncOracleRunner.run(
                "playerAnimator",
                "native-zero-age-singularity",
                PaGraphNativeCases::nativeZeroAgeSingularity);
    }

    @Test
    void nativeSpeedBoundaries() {
        AnimSyncOracleRunner.run(
                "playerAnimator", "speed-strict-boundaries", PaGraphNativeCases::speedBoundaries);
    }

    @Test
    void nativeFpsHistoriesAndCanonicalScratch() {
        AnimSyncOracleRunner.run(
                "playerAnimator", "speed-fps-histories", PaGraphNativeCases::history);
    }

    @Test
    void lowerDomainAndPrivateAssets() {
        AnimSyncOracleRunner.run(
                "playerAnimator",
                "lower-domain-private-assets",
                PaGraphNativeCases::lowerDomainAndAssets);
    }

    @Test
    void nativeLrFrozenForcedSource() {
        AnimSyncOracleRunner.run(
                "lr",
                "graph-frozen-forced-source",
                () -> {
                    var result = PaGraphNativeCases.frozenForced();
                    OracleTransformAudit.requireConfig("lrtactical.mixins.json");
                    return result;
                });
    }

    @Test
    void nativeEaseAndUnsupportedModifiers() {
        AnimSyncOracleRunner.run(
                "playerAnimator",
                "native-ease-and-rejections",
                PaGraphNativeCases::easeAndRefusals);
    }

    @Test
    void exhaustedWrapperLinksAndRunsAtTheNativeTwoThirdsRate() {
        AnimSyncOracleRunner.run(
                "tacz",
                "exhausted-wrapper-linked-regression",
                () -> {
                    var clip = PaGraphNativeCases.clip("reload", false);
                    var oldActor = new KeyframeAnimationPlayer(clip);
                    var old = new ModifierLayer<IAnimation>(oldActor, new SpeedModifier(2f / 3));
                    var oldSpeed = (SpeedModifier) PaGraphNativeCases.modifiers(old).getFirst();
                    assertNull(
                            PaGraphNativeCases.field(AbstractModifier.class, "host").get(oldSpeed));
                    assertNull(oldSpeed.getAnim());
                    assertFalse(
                            old.isActive(), "regression witness: old constructor loses the pose");
                    int compared = 0;
                    for (int t = 0; t <= 24; t++)
                        for (float p : new float[] {0, 1f / 65536, 0.3f, 0.85f, 1}) {
                            var layer = new ModifierLayer<IAnimation>();
                            var actor = new KeyframeAnimationPlayer(clip);
                            ExhaustedReloadAnimation.slowPose = true;
                            try {
                                layer.replaceAnimationWithFade(
                                        AbstractFadeModifier.standardFadeIn(
                                                8, dev.kosmx.playerAnim.core.util.Ease.INOUTSINE),
                                        actor);
                            } finally {
                                ExhaustedReloadAnimation.slowPose = false;
                            }
                            var wrapper =
                                    assertInstanceOf(ModifierLayer.class, layer.getAnimation());
                            var speed =
                                    (SpeedModifier)
                                            PaGraphNativeCases.modifiers(wrapper).getFirst();
                            assertSame(
                                    wrapper,
                                    PaGraphNativeCases.field(AbstractModifier.class, "host")
                                            .get(speed));
                            assertSame(actor, speed.getAnim());
                            assertTrue(wrapper.isActive());
                            assertEquals(
                                    Float.floatToRawIntBits(2f / 3),
                                    Float.floatToRawIntBits(speed.speed));
                            // Preserve PA's float steps and strict >1 boundary, not a double age
                            // product.
                            float shifted = 0;
                            int childTicks = 0;
                            for (int i = 0; i < t; i++) {
                                wrapper.tick();
                                shifted += 2f / 3;
                                while (shifted > 1) {
                                    shifted -= 1;
                                    childTicks++;
                                }
                            }
                            wrapper.setupAnim(p);
                            shifted += p * (2f / 3);
                            while (shifted > 1) {
                                shifted -= 1;
                                childTicks++;
                            }
                            assertEquals(
                                    childTicks,
                                    PaGraphNativeCases.field(
                                                    KeyframeAnimationPlayer.class, "currentTick")
                                            .getInt(actor));
                            assertEquals(
                                    Float.floatToRawIntBits(shifted),
                                    Float.floatToRawIntBits(
                                            PaGraphNativeCases.field(
                                                            SpeedModifier.class, "shiftedDelta")
                                                    .getFloat(speed)));
                            assertEquals(
                                    (t + (double) p) * (2f / 3),
                                    childTicks + (double) shifted,
                                    0.000002);
                            var unwrapped = new KeyframeAnimationPlayer(clip);
                            for (int i = 0; i < childTicks; i++) unwrapped.tick();
                            unwrapped.setupAnim(shifted);
                            for (String part :
                                    java.util.List.of(
                                            "body",
                                            "head",
                                            "leftArm",
                                            "rightArm",
                                            "leftLeg",
                                            "rightLeg"))
                                for (var type :
                                        java.util.List.of(
                                                dev.kosmx.playerAnim.api.TransformType.POSITION,
                                                dev.kosmx.playerAnim.api.TransformType.ROTATION)) {
                                    var base =
                                            new dev.kosmx.playerAnim.core.util.Vec3f(
                                                    .5f, -.25f, 1f);
                                    var expected =
                                            unwrapped.get3DTransform(part, type, shifted, base);
                                    var actual = wrapper.get3DTransform(part, type, p, base);
                                    assertEquals(
                                            Float.floatToRawIntBits(expected.getX()),
                                            Float.floatToRawIntBits(actual.getX()));
                                    assertEquals(
                                            Float.floatToRawIntBits(expected.getY()),
                                            Float.floatToRawIntBits(actual.getY()));
                                    assertEquals(
                                            Float.floatToRawIntBits(expected.getZ()),
                                            Float.floatToRawIntBits(actual.getZ()));
                                    compared++;
                                }
                        }
                    OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                    return Map.of(
                            "productionMixinExecuted",
                            true,
                            "oldWrapperActive",
                            false,
                            "fixedWrapperHostedAndActive",
                            true,
                            "integerTicks",
                            24,
                            "rawBitTransformComparisons",
                            compared,
                            "speedBits",
                            Float.floatToRawIntBits(2f / 3));
                });
    }

    @Test
    void adjustmentPortsMatchNativeFunctions() {
        AnimSyncOracleRunner.run(
                "tacz",
                "adjustments-native-differential",
                () -> {
                    int cases = 0;
                    try (var singleton = mockStatic(Minecraft.class);
                            var displayApi = mockStatic(TimelessAPI.class)) {
                        var minecraft = mock(Minecraft.class);
                        var timer = mock(DeltaTracker.class);
                        when(minecraft.getTimer()).thenReturn(timer);
                        singleton.when(Minecraft::getInstance).thenReturn(minecraft);
                        var display = mock(GunDisplayInstance.class);
                        displayApi
                                .when(() -> TimelessAPI.getGunDisplay(any(ItemStack.class)))
                                .thenReturn(Optional.of(display));
                        for (float fraction : new float[] {0, 1f / 65536, 0.85f, 1})
                            for (float yaw :
                                    new float[] {-180, -85.00001f, -85, -0f, 85, 85.00001f, 180})
                                for (boolean prone : new boolean[] {false, true})
                                    for (boolean riding : new boolean[] {false, true})
                                        for (boolean fixedHand : new boolean[] {false, true}) {
                                            var player = mock(AbstractClientPlayer.class);
                                            // R-facing is resolved before this scope; old and
                                            // current both collapse.
                                            set(player, "yBodyRotO", 179);
                                            set(player, "yBodyRot", 179);
                                            set(player, "yHeadRotO", 179 + yaw);
                                            set(player, "yHeadRot", 179 + yaw);
                                            set(player, "xRotO", yaw);
                                            when(player.getXRot()).thenReturn(yaw);
                                            when(player.getMainHandItem())
                                                    .thenReturn(ItemStack.EMPTY);
                                            when(player.getPose())
                                                    .thenReturn(
                                                            prone ? Pose.SWIMMING : Pose.STANDING);
                                            when(player.isSwimming()).thenReturn(false);
                                            when(player.getVehicle())
                                                    .thenReturn(riding ? mock(Entity.class) : null);
                                            when(timer.getGameTimeDeltaPartialTick(false))
                                                    .thenReturn(fraction);
                                            when(display.is3rdFixedHand()).thenReturn(fixedHand);
                                            var inputs =
                                                    new PaAdjustments.Inputs(
                                                            fraction, 179, 179, 179 + yaw,
                                                            179 + yaw, yaw, yaw, riding, false,
                                                            prone, fixedHand, false);
                                            compareAdjustment(
                                                    "com.tacz.guns.compat.playeranimator.animation.AdjustmentYRotModifier",
                                                    player,
                                                    PaAdjustments.tacz(inputs));
                                            compareAdjustment(
                                                    "me.xjqsh.lrtactical.compat.player_animator.AdjustmentYRotModifier",
                                                    player,
                                                    PaAdjustments.lr(inputs));
                                            cases += 2;
                                        }
                    }
                    OracleTransformAudit.requireConfig("tacz.mixins.json");
                    OracleTransformAudit.requireConfig("lrtactical.mixins.json");
                    return Map.of(
                            "nativePortComparisons",
                            cases,
                            "rFacingCollapsed",
                            true,
                            "aimInput",
                            "raw-synced-step; no interpolation");
                });
    }

    @SuppressWarnings("unchecked")
    private static void compareAdjustment(
            String nativeClass,
            Player player,
            Function<String, Optional<AdjustmentModifier.PartModifier>> port)
            throws Exception {
        var constructor = Class.forName(nativeClass).getDeclaredConstructor(Player.class);
        constructor.setAccessible(true);
        var nativeFunction =
                (Function<String, Optional<AdjustmentModifier.PartModifier>>)
                        constructor.newInstance(player);
        for (String part : java.util.List.of("body", "head", "leftArm", "rightArm", "leftLeg")) {
            var expected = nativeFunction.apply(part);
            var actual = port.apply(part);
            assertEquals(expected.isPresent(), actual.isPresent());
            if (expected.isPresent()) {
                PaGraphNativeCases.bits(
                        expected.orElseThrow().rotation(), actual.orElseThrow().rotation());
                PaGraphNativeCases.bits(
                        expected.orElseThrow().scale(), actual.orElseThrow().scale());
                PaGraphNativeCases.bits(
                        expected.orElseThrow().offset(), actual.orElseThrow().offset());
            }
        }
    }

    private static void set(Player player, String name, float value) throws Exception {
        var field =
                OracleReflection.field(
                        name.equals("xRotO") ? Entity.class : LivingEntity.class, name, "F");
        field.setAccessible(true);
        field.setFloat(player, value);
    }
}
