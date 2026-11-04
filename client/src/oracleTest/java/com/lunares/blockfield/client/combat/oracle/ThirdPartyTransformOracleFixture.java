package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.Test;

/**
 * Proves third-party production transformations are present and exercised in the transformed JVM
 * (ADR4 §9 proof fixture (b)): PlayerAnimator's animation stack on {@code AbstractClientPlayer}, a
 * ParCool hook on {@code Player} and a TaCZ hook on {@code HumanoidModel}.
 *
 * <p>Every probe asserts a member that exists only when the mod's mixin applied; on an
 * untransformed JVM each lookup throws and the oracle fails instead of passing silently.
 */
class ThirdPartyTransformOracleFixture {
    @Test
    void playerAnimatorStackIsPresentAndOperates() throws Exception {
        AnimSyncOracleRunner.run(
                "playerAnimator",
                "animation-stack-transformed",
                () -> {
                    var stackMethod = AbstractClientPlayer.class.getMethod("getAnimationStack");
                    var result = new LinkedHashMap<String, Object>();
                    result.put("stackAccessor", stackMethod.toString());
                    // The pinned PA stack machinery itself runs without a game: layers add and
                    // remove, and activity follows the layer's own active flag.
                    var player =
                            org.mockito.Mockito.mock(
                                    AbstractClientPlayer.class,
                                    org.mockito.Mockito.CALLS_REAL_METHODS);
                    var stack = new dev.kosmx.playerAnim.api.layered.AnimationStack();
                    var field =
                            java.util.Arrays.stream(Player.class.getDeclaredFields())
                                    .filter(
                                            candidate ->
                                                    candidate.getType()
                                                            == dev.kosmx
                                                                    .playerAnim
                                                                    .api
                                                                    .layered
                                                                    .AnimationStack
                                                                    .class)
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new AssertionError(
                                                            "PA player stack field missing"));
                    field.setAccessible(true);
                    field.set(player, stack);
                    org.junit.jupiter.api.Assertions.assertSame(stack, stackMethod.invoke(player));
                    assertEquals(false, stack.isActive());
                    var layer = activeFake();
                    stack.addAnimLayer(1000, layer);
                    assertEquals(true, stack.isActive());
                    assertEquals(true, stack.removeLayer(1000));
                    assertEquals(false, stack.isActive());
                    com.lunares.blockfield.oracle.OracleTransformAudit.requireConfig(
                            "playerAnimator-common.mixins.json");
                    result.put("layersRoundTrip", true);
                    return result;
                });
    }

    private static dev.kosmx.playerAnim.api.layered.IAnimation activeFake() {
        return new dev.kosmx.playerAnim.api.layered.IAnimation() {
            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public dev.kosmx.playerAnim.core.util.Vec3f get3DTransform(
                    String bone,
                    dev.kosmx.playerAnim.api.TransformType type,
                    float tickDelta,
                    dev.kosmx.playerAnim.core.util.Vec3f dest) {
                return dest;
            }

            @Override
            public void setupAnim(float tickDelta) {}
        };
    }

    @Test
    void parCoolHookIsPresent() {
        AnimSyncOracleRunner.run(
                "parcool",
                "player-hook-transformed",
                () -> {
                    var result = new LinkedHashMap<String, Object>();
                    var living =
                            org.mockito.Mockito.mock(
                                    LivingEntity.class, org.mockito.Mockito.CALLS_REAL_METHODS);
                    var customLadder =
                            LivingEntity.class.getMethod(
                                    "parCool$isLivingOnCustomLadder",
                                    net.minecraft.world.level.block.state.BlockState.class,
                                    net.minecraft.world.level.Level.class,
                                    net.minecraft.core.BlockPos.class,
                                    LivingEntity.class);
                    var state =
                            net.minecraft.world.level.block.Blocks.END_ROD
                                    .defaultBlockState()
                                    .setValue(
                                            net.minecraft.world.level.block.DirectionalBlock.FACING,
                                            net.minecraft.core.Direction.UP);
                    var level = org.mockito.Mockito.mock(net.minecraft.world.level.Level.class);
                    assertEquals(
                            true,
                            customLadder.invoke(
                                    living,
                                    state,
                                    level,
                                    net.minecraft.core.BlockPos.ZERO,
                                    living));
                    assertEquals(
                            false,
                            customLadder.invoke(
                                    living,
                                    state.setValue(
                                            net.minecraft.world.level.block.DirectionalBlock.FACING,
                                            net.minecraft.core.Direction.NORTH),
                                    level,
                                    net.minecraft.core.BlockPos.ZERO,
                                    living));
                    com.lunares.blockfield.oracle.OracleTransformAudit.requireConfig(
                            "parcool.mixins.json");
                    result.put("verticalEndRod", true);
                    result.put("horizontalEndRod", false);
                    result.put("hook", customLadder.toString());
                    return result;
                });
    }

    @Test
    void taczModelHookIsPresent() throws Exception {
        AnimSyncOracleRunner.run(
                "tacz",
                "humanoid-model-hook-transformed",
                () -> {
                    var method =
                            java.util.Arrays.stream(HumanoidModel.class.getDeclaredMethods())
                                    .filter(
                                            candidate ->
                                                    candidate
                                                            .getName()
                                                            .endsWith("$setRotationAnglesHead"))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new AssertionError(
                                                            "TaCZ model injection missing"));
                    var result = new LinkedHashMap<String, Object>();
                    result.put("injectedHandler", method.toString());
                    return result;
                });
    }

    @Test
    void requiredThirdPartyMixinsAreApplied() throws Exception {
        AnimSyncOracleRunner.run(
                "playerAnimator",
                "third-party-negative-guard",
                () -> {
                    assertNotNull(
                            AbstractClientPlayer.class.getMethod("getAnimationStack"),
                            "PA PlayerEntityMixin did not apply");
                    assertNotNull(
                            LivingEntity.class.getMethod(
                                    "parCool$isLivingOnCustomLadder",
                                    net.minecraft.world.level.block.state.BlockState.class,
                                    net.minecraft.world.level.Level.class,
                                    net.minecraft.core.BlockPos.class,
                                    LivingEntity.class),
                            "ParCool LivingEntityMixin did not apply");
                    assertTrue(
                            java.util.Arrays.stream(HumanoidModel.class.getDeclaredMethods())
                                    .anyMatch(
                                            method ->
                                                    method.getName()
                                                            .endsWith("$setRotationAnglesHead")),
                            "TaCZ HumanoidModelMixin did not apply");
                    var result = new LinkedHashMap<String, Object>();
                    result.put("guards", 3);
                    return result;
                });
    }
}
