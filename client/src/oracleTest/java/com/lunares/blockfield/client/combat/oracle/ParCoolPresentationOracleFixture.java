package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alrex.parcool.client.animation.PlayerModelRotator;
import com.alrex.parcool.common.attachment.client.Animation;
import com.lunares.blockfield.client.animation.RemoteParCoolPresentation;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.lunares.blockfield.oracle.ParCoolPresentationCases;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * U7c client half of the ParCool presentation differential: each typed frame installed on a driven
 * remote and rendered natively inside its invocation scope (the production ParCool root/model
 * stages, the transformed PlayerModel with every production mixin), at every FPS schedule. The
 * server half evaluates the same frames through its ports; the comparator requires identical raw
 * bits for the root, six parts and 48 corners.
 */
class ParCoolPresentationOracleFixture {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void drivenRemotesRenderTheTypedFrameAtEveryFpsSchedule() {
        AnimSyncOracleRunner.run(
                "parcool",
                "presentation-six-box-native",
                () -> {
                    try (var singleton = SceneTimingOracleFixture.minecraft();
                            var config = ParCoolConfigFixture.inMemory()) {
                        // The three geometry settings the connected policy forces on (ADR4 §6);
                        // native passive animation reads the global config, not scratch.
                        for (var forced :
                                new com.alrex.parcool.config.ParCoolConfig.Client.Booleans[] {
                                    com.alrex.parcool.config.ParCoolConfig.Client.Booleans
                                            .EnableAnimation,
                                    com.alrex.parcool.config.ParCoolConfig.Client.Booleans
                                            .EnableFallingAnimation,
                                    com.alrex.parcool.config.ParCoolConfig.Client.Booleans
                                            .EnableLeanAnimationOfFastRun
                                }) forced.set(true);
                        var player = remote();
                        var mesh = PlayerModel.createMesh(CubeDeformation.NONE, false);
                        var root = LayerDefinition.create(mesh, 64, 64).bakeRoot();
                        var measurements =
                                ParCoolPresentationCases.run(
                                        (value, fraction) -> sample(player, root, value, fraction));
                        assertFalse(RemoteParCoolPresentation.driven(player));
                        OracleTransformAudit.requireConfig("parcool.mixins.json");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        OracleTransformAudit.requireConfig("playerAnimator-common.mixins.json");
                        return measurements;
                    }
                });
    }

    /** One render pass: root stages on a fresh pose stack, then the transformed model setup. */
    private static int[] sample(
            AbstractClientPlayer player,
            ModelPart bakedRoot,
            ParCoolPresentationCases.Case value,
            float fraction) {
        RemoteParCoolPresentation.install(
                player,
                new RemoteParCoolPresentation.Frame(
                        value.frame(), ParCoolPresentationCases.POLICY, fraction, fraction));
        try {
            var stack = new PoseStack();
            var model = new PlayerModel<AbstractClientPlayer>(bakedRoot, false);
            float limb = ParCoolPresentationCases.limb(fraction);
            try (var scope = RemoteParCoolPresentation.scope(player)) {
                assertEquals(limb, RemoteParCoolPresentation.limb(player, fraction));
                var animation = Animation.get(player);
                var rotator =
                        new PlayerModelRotator(
                                stack, player, fraction, ParCoolPresentationCases.BODY_YAW);
                // PlayerRendererMixin: a cancelling pre stage skips the RETURN post stage.
                if (!animation.rotatePre(player, rotator)) animation.rotatePost(player, rotator);
                model.prepareMobModel(player, limb, ParCoolPresentationCases.WALK_SPEED, fraction);
                model.setupAnim(
                        player,
                        limb,
                        ParCoolPresentationCases.WALK_SPEED,
                        ParCoolPresentationCases.age(fraction),
                        ParCoolPresentationCases.HEAD_YAW,
                        ParCoolPresentationCases.HEAD_PITCH);
            }
            float[] params = new float[36];
            int k = 0;
            for (var part :
                    List.of(
                            model.head,
                            model.body,
                            model.leftArm,
                            model.rightArm,
                            model.leftLeg,
                            model.rightLeg)) {
                params[k++] = part.x;
                params[k++] = part.y;
                params[k++] = part.z;
                params[k++] = part.xRot;
                params[k++] = part.yRot;
                params[k++] = part.zRot;
            }
            return ParCoolPresentationCases.bits(stack.last().pose().get(new float[16]), params);
        } finally {
            RemoteParCoolPresentation.clear(player);
        }
    }

    /** A remote player at the shared entity inputs; its R walk is the shared walk triple. */
    private static AbstractClientPlayer remote() throws Exception {
        var player = SceneTimingOracleFixture.player(77);
        var walk = new WalkAnimationState();
        for (String name : new String[] {"speedOld", "speed", "position"}) {
            var field = OracleReflection.field(WalkAnimationState.class, name, "F");
            field.setAccessible(true);
            field.setFloat(
                    walk,
                    name.equals("position")
                            ? ParCoolPresentationCases.WALK_POSITION
                            : ParCoolPresentationCases.WALK_SPEED);
        }
        var walkField =
                OracleReflection.field(
                        LivingEntity.class,
                        "walkAnimation",
                        "Lnet/minecraft/world/entity/WalkAnimationState;");
        walkField.setAccessible(true);
        walkField.set(player, walk);
        // Driven facing is collapsed to its R sample (old = current), as ClientCombatScene writes.
        for (String name : new String[] {"yBodyRot", "yBodyRotO"}) {
            var field = OracleReflection.field(LivingEntity.class, name, "F");
            field.setAccessible(true);
            field.setFloat(player, ParCoolPresentationCases.BODY_YAW);
        }
        var yRotO = OracleReflection.field(net.minecraft.world.entity.Entity.class, "yRotO", "F");
        yRotO.setAccessible(true);
        yRotO.setFloat(player, ParCoolPresentationCases.BODY_YAW);
        // SBW's parachute check and ParCool read the level (as SceneTimingOracleFixture stubs it).
        var level = mock(net.minecraft.client.multiplayer.ClientLevel.class);
        doReturn(true).when(level).isClientSide();
        doReturn(level).when(player).level();
        doReturn(true).when(player).isAlive();
        doReturn(ParCoolPresentationCases.BODY_YAW).when(player).getYRot();
        doReturn(new Vec3(.3, -.2, .9)).when(player).getLookAngle();
        doReturn(new Vec3(.03, -.02, .09)).when(player).getDeltaMovement();
        doReturn(1.8f).when(player).getBbHeight();
        doReturn(.08).when(player).getAttributeValue(Attributes.GRAVITY);
        doReturn(new net.minecraft.world.entity.player.Abilities()).when(player).getAbilities();
        return player;
    }
}
