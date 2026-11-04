package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.LinkedHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * First real pinned-native differential fixture: the actual vanilla {@link HumanoidModel#setupAnim}
 * bytecode executes on the production mod-list classpath (no port, no stub of the math).
 *
 * <p>Differential in two senses: identical inputs must produce bit-identical part transforms
 * (determinism, the property every section-5 replacement must preserve), and a different attack age
 * must move the arm (sensitivity, proving the native code really ran).
 */
class VanillaSetupAnimOracleFixture {
    private static final float LIMB_SWING = 0.5f;
    private static final float LIMB_SWING_AMOUNT = 0.8f;
    private static final float AGE = 100.0f;
    private static final float HEAD_YAW = 15.0f;
    private static final float HEAD_PITCH = -10.0f;
    private static final float PARTIAL = 1.0f;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nativeSetupAnimIsBitDeterministic() {
        var measurements =
                AnimSyncOracleRunner.run(
                        "vanilla",
                        "humanoid-setup-anim-determinism",
                        () -> {
                            var first = runNative(0.0f);
                            var second = runNative(0.0f);
                            assertArrayEquals(first, second, "same inputs must pose identically");
                            var result = new LinkedHashMap<String, Object>();
                            result.put("partFloats", first.length);
                            result.put(
                                    "rightArmX",
                                    Float.intBitsToFloat(first[partIndex("rightArm")]));
                            return result;
                        });
        assertEquals(36, measurements.get("partFloats"));
    }

    @Test
    void nativeSetupAnimRespondsToAttackAge() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "humanoid-setup-anim-attack-delta",
                () -> {
                    int rest = runNative(0.0f)[partIndex("rightArm")];
                    int raised = runNative(0.6f)[partIndex("rightArm")];
                    assertNotEquals(
                            Float.intBitsToFloat(rest),
                            Float.intBitsToFloat(raised),
                            "attack age must move the arm, else the native code did not run");
                    var result = new LinkedHashMap<String, Object>();
                    result.put("attack0.rightArmX", Float.intBitsToFloat(rest));
                    result.put("attack06.rightArmX", Float.intBitsToFloat(raised));
                    return result;
                });
    }

    /** Fresh parts per run so no retained model state leaks between samples. */
    private static LayerDefinition mesh() {
        return LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64);
    }

    private static int[] runNative(float attackAnim) {
        // Accessories' constructor hook queries the singleton's model loader, not a GL service.
        // Supply a null model loader as during resource-loader startup; never construct Minecraft.
        try (var singleton = org.mockito.Mockito.mockStatic(net.minecraft.client.Minecraft.class)) {
            singleton
                    .when(net.minecraft.client.Minecraft::getInstance)
                    .thenReturn(mock(net.minecraft.client.Minecraft.class));
            var model = new HumanoidModel<LivingEntity>(mesh().bakeRoot());
            var entity = nativeEntity(attackAnim);
            model.prepareMobModel(entity, LIMB_SWING, LIMB_SWING_AMOUNT, PARTIAL);
            model.attackTime = entity.getAttackAnim(PARTIAL);
            model.setupAnim(entity, LIMB_SWING, LIMB_SWING_AMOUNT, AGE, HEAD_YAW, HEAD_PITCH);
            return snapshot(model);
        }
    }

    private static LivingEntity nativeEntity(float attackAnim) {
        var entity = mock(LivingEntity.class);
        when(entity.getMainHandItem()).thenReturn(net.minecraft.world.item.ItemStack.EMPTY);
        when(entity.getOffhandItem()).thenReturn(net.minecraft.world.item.ItemStack.EMPTY);
        when(entity.getAttackAnim(PARTIAL)).thenReturn(attackAnim);
        when(entity.getMainArm()).thenReturn(HumanoidArm.RIGHT);
        when(entity.getSwimAmount(PARTIAL)).thenReturn(0.0f);
        when(entity.getDeltaMovement()).thenReturn(Vec3.ZERO);
        when(entity.getFallFlyingTicks()).thenReturn(0);
        when(entity.isVisuallySwimming()).thenReturn(false);
        when(entity.isFallFlying()).thenReturn(false);
        when(entity.isCrouching()).thenReturn(false);
        when(entity.isUsingItem()).thenReturn(false);
        return entity;
    }

    private static int partIndex(String part) {
        return switch (part) {
            case "rightArm" -> 3 * 6;
            default -> throw new IllegalArgumentException(part);
        };
    }

    private static int[] snapshot(HumanoidModel<LivingEntity> model) {
        var parts =
                new ModelPart[] {
                    model.head,
                    model.body,
                    model.leftArm,
                    model.rightArm,
                    model.leftLeg,
                    model.rightLeg
                };
        var bits = new int[parts.length * 6];
        int i = 0;
        for (var part : parts) {
            bits[i++] = Float.floatToIntBits(part.x);
            bits[i++] = Float.floatToIntBits(part.y);
            bits[i++] = Float.floatToIntBits(part.z);
            bits[i++] = Float.floatToIntBits(part.xRot);
            bits[i++] = Float.floatToIntBits(part.yRot);
            bits[i++] = Float.floatToIntBits(part.zRot);
        }
        return bits;
    }
}
