package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alrex.parcool.client.animation.PlayerModelRotator;
import com.alrex.parcool.client.animation.PlayerModelTransformer;
import com.alrex.parcool.client.animation.impl.ClimbUpAnimator;
import com.alrex.parcool.common.action.impl.Dive;
import com.alrex.parcool.common.attachment.Attachments;
import com.alrex.parcool.common.attachment.ClientAttachments;
import com.alrex.parcool.common.attachment.client.Animation;
import com.alrex.parcool.common.attachment.common.Parkourability;
import com.lunares.blockfield.client.animation.RemoteParCoolPresentation;
import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.lunares.blockfield.oracle.ParCoolNativeValues;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Pose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ParCoolAttachmentOracleFixture {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void actualNativeGettersResolveFreshScratchAndRestoreAfterEveryExit() {
        AnimSyncOracleRunner.run(
                "parcool",
                "separate-driven-attachment",
                () -> {
                    var player = player();
                    var other = player();
                    var live = Parkourability.get(player);
                    var nativeAnimation = Animation.get(player);
                    var adapter =
                            new ParCoolGraph(
                                    getClass().getClassLoader(),
                                    "com.alrex.parcool.client.animation.impl.");
                    var actor = new ClimbUpAnimator();
                    var tick =
                            com.alrex.parcool.client.animation.Animator.class.getDeclaredField(
                                    "tick");
                    tick.setAccessible(true);
                    tick.setInt(actor, 20);
                    var frozen =
                            ParCoolFrame.freeze(
                                    adapter,
                                    AnimationGraphState.neutral(),
                                    actor,
                                    ParCoolNativeValues.context(),
                                    new AtomicInteger()::incrementAndGet,
                                    ignored -> new AnimationStartReport.StartKey(1, 1));
                    var policy =
                            new AnimationPolicy(
                                    1,
                                    PhaseDomain.A,
                                    true,
                                    true,
                                    true,
                                    true,
                                    AnimationPolicy.registered());
                    var frame = new RemoteParCoolPresentation.Frame(frozen, policy, .3f, .85f);
                    var before = AnimationSchema.encode(frozen.state());
                    RemoteParCoolPresentation.install(player, frame);
                    RemoteParCoolPresentation.install(
                            other, new RemoteParCoolPresentation.Frame(frozen, policy, .6f, .1f));
                    try (var singleton = mockStatic(Minecraft.class)) {
                        singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
                        assertSame(nativeAnimation, Animation.get(player));
                        nativeAnimation.tick(player, live);
                        nativeAnimation.onRenderTick(null, player, live);
                        nativeAnimation.updateAnimationInfo(player);
                        nativeAnimation.setAnimator(new ClimbUpAnimator());
                        assertFalse(
                                nativeAnimation.hasAnimator(),
                                "native tick/selection must not install a driven actor");
                        assertThrows(
                                IllegalStateException.class,
                                () -> nativeAnimation.animatePre(player, null));
                        try (var outer = RemoteParCoolPresentation.scope(player)) {
                            var scratch = Parkourability.get(player);
                            var animation = Animation.get(player);
                            assertNotSame(live, scratch);
                            assertNotSame(nativeAnimation, animation);
                            assertEquals(27, scratch.getList().size());
                            assertEquals(.31, scratch.get(Dive.class).getPlayerYSpeed(0));
                            assertEquals(-.13, scratch.get(Dive.class).getPlayerYSpeed(1));
                            var model =
                                    new PlayerModel<AbstractClientPlayer>(
                                            LayerDefinition.create(
                                                            PlayerModel.createMesh(
                                                                    CubeDeformation.NONE, false),
                                                            64,
                                                            64)
                                                    .bakeRoot(),
                                            false);
                            var transformer =
                                    new PlayerModelTransformer(
                                            player, model, false, 0, 7, .4f, 0, 0);
                            var rotator = new PlayerModelRotator(new PoseStack(), player, .17f, 23);
                            assertEquals(.3f, transformer.getPartialTick());
                            assertEquals(.3f, rotator.getPartialTick());
                            assertEquals(
                                    .17f,
                                    new PlayerModelRotator(new PoseStack(), other, .17f, 23)
                                            .getPartialTick());
                            assertFalse(animation.animatePre(player, transformer));
                            assertFalse(animation.rotatePre(player, rotator));
                            animation.animatePost(player, transformer);
                            animation.rotatePost(player, rotator);
                            for (float value :
                                    rotator.getRawStack().last().pose().get(new float[16]))
                                assertTrue(Float.isFinite(value));
                            assertTrue(
                                    animation.hasAnimator(),
                                    "integer reducer owns removal even at native expiry");
                            assertThrows(
                                    IllegalStateException.class,
                                    () -> RemoteParCoolPresentation.clear(player));
                            scratch.get(Dive.class).tick();
                            try (var nested = RemoteParCoolPresentation.scope(player)) {
                                assertNotSame(scratch, Parkourability.get(player));
                                assertEquals(
                                        17,
                                        Parkourability.get(player)
                                                .get(Dive.class)
                                                .getNotDoingTick());
                                assertThrows(IllegalStateException.class, outer::close);
                            }
                            assertSame(scratch, Parkourability.get(player));
                            assertEquals(18, scratch.get(Dive.class).getNotDoingTick());
                            try (var nestedOther = RemoteParCoolPresentation.scope(other)) {
                                assertEquals(.6f, RemoteParCoolPresentation.fractionA(other));
                                assertNull(RemoteParCoolPresentation.fractionA(player));
                                assertThrows(
                                        IllegalStateException.class,
                                        () -> RemoteParCoolPresentation.clear(player));
                            }
                            assertEquals(.3f, RemoteParCoolPresentation.fractionA(player));
                        }
                        assertSame(live, Parkourability.get(player));
                        assertSame(nativeAnimation, Animation.get(player));
                        assertEquals(0, live.get(Dive.class).getNotDoingTick());
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> {
                                    try (var failed = RemoteParCoolPresentation.scope(player)) {
                                        throw new IllegalArgumentException("fixture");
                                    }
                                });
                        assertSame(live, Parkourability.get(player));
                        assertArrayEquals(before, AnimationSchema.encode(frozen.state()));
                    } finally {
                        RemoteParCoolPresentation.clearAll();
                    }
                    var local = player();
                    when(local.isLocalPlayer()).thenReturn(true);
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> RemoteParCoolPresentation.install(local, frame));
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    return Map.of(
                            "actionCount",
                            27,
                            "fractionABits",
                            Float.floatToRawIntBits(.3f),
                            "fractionRBits",
                            Float.floatToRawIntBits(.85f),
                            "nativeExpiredActorRetained",
                            true,
                            "physicsAttachmentUnchanged",
                            true,
                            "nestedAndExceptionCleanup",
                            true);
                });
    }

    private static AbstractClientPlayer player() {
        var player = mock(AbstractClientPlayer.class);
        when(player.getPose()).thenReturn(Pose.STANDING);
        when(player.getAttachedOrCreate(Attachments.PARKOURABILITY))
                .thenReturn(new Parkourability());
        when(player.getAttachedOrCreate(ClientAttachments.ANIMATION)).thenReturn(new Animation());
        return player;
    }
}
