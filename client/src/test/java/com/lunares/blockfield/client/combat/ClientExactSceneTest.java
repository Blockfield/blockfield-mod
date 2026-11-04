package com.lunares.blockfield.client.combat;

import static com.lunares.blockfield.client.combat.ExactFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.network.packet.AnimationProbeS2CPacket;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The exact-stream scene: batches carry seeds to the journal, each frame freezes R/A/H with the
 * complete victim report, and a revision without a complete report yields no ticket.
 */
class ClientExactSceneTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void reset() {
        ClientCombatScene.disconnect();
        ClientAnimationAssets.clear();
    }

    private static PlayerSnapshotS2CPacket batch(long previous, long tick, boolean full) {
        var identity =
                new AnimationState.Identity(
                        CONNECTION,
                        STREAM,
                        SCENE,
                        ENTITY,
                        0,
                        SUBSCRIPTION,
                        TARGET,
                        0,
                        0,
                        0,
                        0,
                        AnimationSchema.layoutDigest(),
                        ZERO);
        var checkpoint = checkpoint(identity, tick, tick * .25);
        int flags =
                full
                        ? AnimationRecipe.SnapshotRecord.FLAG_FULL
                                | AnimationRecipe.SnapshotRecord.FLAG_RESET
                        : 0;
        var seeds =
                full
                        ? List.of(seed(checkpoint, tick * 256 + 1))
                        : List
                                .<com.lunares.blockfield.common.combat.AnimationEvent.ObserverSeed>
                                        of();
        var references = new java.util.HashSet<>(AnimationSchema.references(checkpoint));
        for (var seed : seeds) references.addAll(AnimationSchema.references(seed));
        var definitions =
                references.stream()
                        .map(
                                ref ->
                                        new com.lunares.blockfield.common.combat.AnimationResources
                                                .Definition(
                                                ref,
                                                new com.lunares.blockfield.common.combat
                                                        .AnimationResources.Bytes(new byte[0]),
                                                List.of()))
                        .toList();
        return PlayerSnapshotS2CPacket.animation(
                STREAM,
                previous,
                tick,
                2,
                6,
                10,
                500,
                new AnimationRecipe.Batch(
                        CONNECTION,
                        SCENE,
                        List.of(
                                new AnimationRecipe.SnapshotRecord(
                                        identity, flags, checkpoint.placement(), checkpoint)),
                        definitions,
                        seeds));
    }

    @Test
    void aJournalRowWithoutSuccessfulNativeInstallationIssuesOnlyAnROnlyTicket() {
        ClientAnimationAssets.install(assets());
        var player = mock(RemotePlayer.class);
        when(player.getId()).thenReturn(ENTITY);
        when(player.getUUID()).thenReturn(new UUID(0, ENTITY));
        var walk = new net.minecraft.world.entity.WalkAnimationState();
        try {
            var field =
                    net.minecraft.world.entity.LivingEntity.class.getDeclaredField("walkAnimation");
            field.setAccessible(true);
            field.set(player, walk);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        var mc = mock(Minecraft.class);
        mc.level = mock(net.minecraft.client.multiplayer.ClientLevel.class);
        when(mc.level.getEntity(ENTITY)).thenReturn(player);
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(player);
            for (int n = 0; n < 12; n++) {
                long tick = 100 + n;
                // Probe of the tick first (sent before the batch), then the batch, then a frame.
                ClientCombatScene.probe(
                        new AnimationProbeS2CPacket(
                                tick, tick * 50_000_000L, tick, tick * 50_000_000L),
                        (long) ((now + n * 50) * 1_000_000));
                ClientCombatScene.accept(batch(n == 0 ? 0 : tick, tick, n == 0), now + n * 50);
                ClientCombatScene.mainFrame(now + n * 50 + 10);
            }
            var receipt = ClientCombatScene.receipt();
            assertNotNull(receipt);
            assertNotNull(receipt.exact(), "an exact stream freezes an ADR4 revision");
            assertFalse(
                    DrivenPresentation.driven(player),
                    "this plain mock lacks the native PA roots; it cannot install");
            assertNull(receipt.exact().report(), "a complete journal is not installation evidence");
            // Protocol 9: the shot still carries R, without victim rows to replay.
            var ticket = ClientCombatScene.nextTicket(now + 11 * 50 + 20);
            assertEquals(receipt.time(), ticket.renderTime(), 1e-9);
            assertNull(ticket.animation().starts());
            // Without its resource generation the next seed cannot activate: no row, no ticket.
            ClientAnimationAssets.clear();
            ClientCombatScene.disconnect();
            ClientCombatScene.add(player);
            for (int n = 0; n < 12; n++) {
                long tick = 200 + n;
                ClientCombatScene.accept(batch(n == 0 ? 0 : tick, tick, n == 0), now + n * 50);
                ClientCombatScene.mainFrame(now + n * 50 + 10);
            }
            assertNull(ClientCombatScene.receipt().exact().report());
            assertNull(ClientCombatScene.nextTicket(now + 11 * 50 + 20).animation().starts());
            // Placement still drives locomotion: the R walk triple, not the r10 chord.
            var sample = ClientCombatScene.receipt().entities().get(ENTITY);
            var placement = sample.before().placement();
            assertEquals(
                    com.lunares.blockfield.common.combat.SnapshotWireMath.walkPosition(
                            placement.walk(), (float) sample.fraction()),
                    DrivenPresentation.walkPosition(walk));
            assertTrue(
                    String.valueOf(ClientCombatScene.debugSnapshot().get("reportUnavailable"))
                            .contains("resource_unavailable"));
        }
    }
}
