package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.combat.ClientCombatScene;
import com.lunares.blockfield.client.render.BodyPartHitboxRenderer;
import com.lunares.blockfield.client.render.RenderCapture;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Executes production callbacks merged into GameRenderer without a window or GL calls. */
class FrameEntryOracleFixture {
    @BeforeAll
    static void bootstrap() throws Exception {
        SceneTimingOracleFixture.bootstrap();
    }

    @Test
    void renderHookUsesTheArrivalNanosecondClock() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "fx-client-frame-clock",
                () -> {
                    ClientCombatScene.disconnect();
                    var world = new SceneTimingOracleFixture.World();
                    try {
                        var renderer = mock(GameRenderer.class);
                        long now = System.nanoTime();
                        for (int n = 0; n < 12; n++) {
                            long tick = 100 + n;
                            ClientCombatScene.accept(
                                    PlayerSnapshotS2CPacket.animation(
                                            17,
                                            n == 0 ? 0 : tick,
                                            tick,
                                            2,
                                            6,
                                            10,
                                            500,
                                            new com.lunares.blockfield.common.combat.AnimationRecipe
                                                    .Batch(0, 0, List.of(), List.of(), List.of())),
                                    (now - (11 - n) * 50_000_000L) / 1_000_000.0);
                        }
                        hook(renderer, "blockfield$sampleScene", true);
                        assertNotNull(
                                ClientCombatScene.receipt(),
                                "the real render callback must warm R");
                        assertNotNull(ClientCombatScene.nextTicket(ClientCombatScene.nowMs()));
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return Map.of(
                                "arrivalClock",
                                "nanoTime",
                                "frameClock",
                                "nanoTime",
                                "receivedHigh",
                                ClientCombatScene.receipt().high());
                    } finally {
                        world.close();
                        ClientCombatScene.disconnect();
                    }
                });
    }

    @Test
    void remotePositionAndWalkAdvanceOnFramesWithoutClientTicks() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "remote-locomotion-per-frame",
                () -> {
                    var samples = new java.util.LinkedHashMap<Integer, Integer>();
                    for (int fps : List.of(30, 60, 144, 240)) {
                        ClientCombatScene.disconnect();
                        var world =
                                new SceneTimingOracleFixture.World(
                                        catalog -> null,
                                        (tick, state) -> {
                                            var old = state.placement();
                                            var placement =
                                                    new AnimationState.Placement(
                                                            new AnimationState.Vec3(
                                                                    (tick - 1) * .25, 64, 0),
                                                            new AnimationState.Vec3(
                                                                    tick * .25, 64, 0),
                                                            old.angles(),
                                                            new AnimationState.Walk(
                                                                    tick * .5f, .5f, .5f),
                                                            old.deltaMovementOnPreviousTick(),
                                                            old.deltaMovement(),
                                                            old.vehicleAngles(),
                                                            old.reads());
                                            return new AnimationState(
                                                    state.identity(),
                                                    state.serverTick(),
                                                    state.sampleRevision(),
                                                    state.eventWatermark(),
                                                    placement,
                                                    state.vanilla(),
                                                    state.stance(),
                                                    state.equipment(),
                                                    state.tacz(),
                                                    state.lr(),
                                                    state.graph(),
                                                    state.parcool(),
                                                    state.create(),
                                                    state.sbw(),
                                                    state.decisions());
                                        },
                                        java.util.function.UnaryOperator.identity());
                        try {
                            long now = System.nanoTime();
                            ExactSceneOracleFixture.prime(world, now);
                            double previous = world.driven.getX();
                            int frames = fps / 10;
                            for (int frame = 1; frame <= frames; frame++) {
                                ClientCombatScene.frameEntry(now + frame * 1_000_000_000L / fps);
                                var receipt = ClientCombatScene.receipt();
                                double r = receipt.time();
                                assertEquals(r * .25, world.driven.getX(), 1e-12);
                                assertTrue(
                                        world.driven.getX() > previous,
                                        "position must advance every frame at " + fps);
                                for (float ambient : new float[] {0, .37f, 1}) {
                                    assertEquals(
                                            world.driven.position(),
                                            world.driven.getPosition(ambient));
                                    assertEquals(
                                            r * .5,
                                            world.driven.walkAnimation.position(ambient),
                                            1e-5);
                                    assertEquals(.5f, world.driven.walkAnimation.speed(ambient), 0);
                                }
                                previous = world.driven.getX();
                                ClientCombatScene.frameEnd();
                            }
                            samples.put(fps, frames);
                        } finally {
                            world.close();
                            ClientCombatScene.disconnect();
                        }
                    }
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    return Map.of("framesWithoutClientTicks", samples);
                });
    }

    @Test
    void bothCaptureApisExpireAfterCullingAndAFrameWithoutLevelRendering() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "fx-client-frame-capture",
                () -> {
                    var world = new SceneTimingOracleFixture.World();
                    try {
                        var renderer = mock(GameRenderer.class);
                        var player = mock(Player.class);
                        when(player.getId()).thenReturn(990);
                        when(player.level()).thenReturn(mock(Level.class));
                        when(player.getBoundingBox())
                                .thenReturn(new AABB(-.3, 0, -.3, .3, 1.8, .3));
                        when(player.getViewVector(.5f)).thenReturn(new Vec3(0, 0, 1));
                        var root =
                                LayerDefinition.create(
                                                PlayerModel.createMesh(CubeDeformation.NONE, false),
                                                64,
                                                64)
                                        .bakeRoot();
                        var model = new PlayerModel<Player>(root, false);
                        BodyPartHitboxRenderer.evidence(true);
                        for (boolean level : List.of(true, false)) {
                            hook(renderer, "blockfield$captureFrame", true);
                            hook(renderer, "blockfield$captureLevelStart", true);
                            BodyPartHitboxRenderer.capture(
                                    player,
                                    model,
                                    new Matrix4f(),
                                    new Matrix4f(),
                                    RenderCapture.Pass.MAIN,
                                    BodyPartHitboxRenderer.nextInvocation(),
                                    Vec3.ZERO,
                                    Vec3.ZERO,
                                    Vec3.ZERO);
                            BodyPartHitboxRenderer.handle(
                                    player,
                                    new PoseStack(),
                                    mock(VertexConsumer.class, RETURNS_SELF),
                                    .5f);
                            assertEquals(
                                    true, BodyPartHitboxRenderer.debugSnapshot(990).get("drawn"));
                            hook(renderer, "blockfield$captureLevelEnd", true);
                            hook(renderer, "blockfield$captureFrame", level);
                            if (level) hook(renderer, "blockfield$captureLevelStart", true);
                            // Culled MAIN, or render(false): neither invokes the entity renderer.
                            assertNull(BodyPartHitboxRenderer.debugSnapshot(990));
                            assertEquals(
                                    BodyPartHitboxRenderer.NOT_RENDERED,
                                    BodyPartHitboxRenderer.captureSnapshot(990).get("status"));
                            if (level) hook(renderer, "blockfield$captureLevelEnd", true);
                            assertEquals(RenderCapture.Pass.OTHER, BodyPartHitboxRenderer.pass());
                        }
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return Map.of(
                                "culledMain",
                                "not_rendered",
                                "noLevelFrame",
                                "not_rendered",
                                "legacyDebugSnapshot",
                                "null");
                    } finally {
                        world.close();
                        BodyPartHitboxRenderer.levelPass(false);
                        BodyPartHitboxRenderer.onDisconnect();
                    }
                });
    }

    private static void hook(GameRenderer renderer, String name, boolean level) throws Exception {
        var method =
                Arrays.stream(GameRenderer.class.getDeclaredMethods())
                        .filter(m -> m.getName().endsWith(name))
                        .findFirst()
                        .orElseThrow();
        method.setAccessible(true);
        method.invoke(renderer, mock(DeltaTracker.class), level, new CallbackInfo("render", false));
    }
}
