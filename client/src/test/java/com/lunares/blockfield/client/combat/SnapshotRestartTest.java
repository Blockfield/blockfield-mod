package com.lunares.blockfield.client.combat;

import static com.lunares.blockfield.client.combat.ExactFixtures.CONNECTION;
import static com.lunares.blockfield.client.combat.ExactFixtures.SCENE;
import static com.lunares.blockfield.client.combat.ExactFixtures.SUBSCRIPTION;
import static com.lunares.blockfield.client.combat.ExactFixtures.TARGET;
import static com.lunares.blockfield.client.combat.ExactFixtures.ZERO;
import static com.lunares.blockfield.client.combat.RaknetSnapshotSwitchTest.delta;
import static com.lunares.blockfield.client.combat.RaknetSnapshotSwitchTest.full;
import static com.lunares.blockfield.client.combat.RaknetSnapshotSwitchTest.sent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import com.lunares.blockfield.network.packet.SnapshotRestartC2SPacket;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SnapshotRestartTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void reset() {
        ClientCombatScene.disconnect();
    }

    @Test
    void missingFirstFullIsHeldUntilTimeoutWithoutAdvancingPrefix() {
        for (boolean empty : List.of(false, true)) {
            var buffer = new ClientSnapshotBuffer();
            assertFalse(
                    buffer.apply(
                            empty ? sent(17, 101, 101) : sent(17, 101, 101, delta(1, 2.24)),
                            id -> false));
            assertEquals(0, buffer.defects());
            assertTrue(buffer.expireHeld(Double.MAX_VALUE));
            assertEquals("STREAM_ORDER", buffer.defectReason());
            assertEquals(-1, buffer.high());
            assertEquals(1, buffer.defects());
        }
    }

    @Test
    void restartRequiresFreshEpochFirstFullAndPreservesNativeEntities() {
        var buffer = new ClientSnapshotBuffer();
        buffer.add(1, 0);
        assertTrue(buffer.apply(sent(17, 0, 100, full(1, 1.0)), id -> true));
        assertFalse(buffer.apply(sent(17, 102, 102, delta(1, 1.28)), id -> true));
        assertTrue(buffer.expireHeld(Double.MAX_VALUE));
        assertEquals(100, buffer.high());
        var request = buffer.restartRequest();
        assertEquals(17, request.observerStreamEpoch());
        assertEquals(SnapshotRestartC2SPacket.Reason.STREAM_ORDER, request.reason());
        assertNull(buffer.restartRequest());
        assertFalse(buffer.apply(sent(17, 101, 101, delta(1, 1.28)), id -> true));
        assertTrue(buffer.apply(sent(18, 0, 105, full(1, 4.0)), id -> true));
        assertFalse(buffer.broken());
        assertEquals(4.0, buffer.frames(1).get(105L).x());
        assertTrue(buffer.apply(sent(18, 106, 106, delta(1, 4.28)), id -> true));
        assertEquals(4.28, buffer.frames(1).get(106L).x());
        assertFalse(buffer.apply(sent(17, 103, 103, delta(1, 1.0)), id -> true));
        assertEquals(106, buffer.high());
        assertFalse(buffer.broken());
        assertEquals(1, buffer.defects());
        assertEquals("STREAM_ORDER", buffer.defectReason());
        assertFalse(buffer.apply(sent(18, 108, 108, delta(1, 4.56)), id -> true));
        assertTrue(buffer.expireHeld(Double.MAX_VALUE));
        assertNotNull(buffer.restartRequest());
        assertEquals(2, buffer.defects());
        assertTrue(buffer.apply(sent(19, 0, 110, full(1, 5.0)), id -> true));
        assertEquals(5.0, buffer.frames(1).get(110L).x());
        assertFalse(buffer.apply(sent(17, 101, 101, delta(1, 1.28)), id -> true));
        assertFalse(buffer.broken());
        assertEquals(2, buffer.defects());
    }

    @Test
    void initialFullAndSparseSenderChainCanArriveBehindTheirDependentDeltas() {
        var buffer = new ClientSnapshotBuffer();
        buffer.add(1, 0);
        var applied = new ArrayList<Long>();
        java.util.function.Consumer<PlayerSnapshotS2CPacket> onApply =
                batch -> applied.add(batch.serverTick());
        assertFalse(buffer.apply(sent(17, 102, 104, delta(1, 1.84)), id -> true, 0, onApply));
        assertFalse(buffer.apply(sent(17, 101, 101, delta(1, 1.28)), id -> true, 1, onApply));
        assertEquals(-1, buffer.high());
        assertNull(buffer.frames(1).get(104L));
        assertTrue(buffer.apply(sent(17, 0, 100, full(1, 1.0)), id -> true, 2, onApply));
        assertEquals(List.of(100L, 101L, 104L), applied);
        assertEquals(104, buffer.high());
        assertEquals(1.84, buffer.frames(1).get(104L).x());
        assertEquals(0, buffer.defects());
    }

    @Test
    void freshRestartFullCanArriveBehindItsOwnDeltasWithoutBorrowingRetiredBase() {
        var buffer = new ClientSnapshotBuffer();
        buffer.add(1, 0);
        assertTrue(buffer.apply(sent(17, 0, 100, full(1, 1.0)), id -> true, 0, b -> {}));
        assertFalse(buffer.apply(sent(17, 101, 101, delta(2, 0.001)), id -> true, 1, b -> {}));
        assertNotNull(buffer.restartRequest(2));
        assertFalse(buffer.apply(sent(18, 106, 106, delta(1, 4.28)), id -> true, 3, b -> {}));
        assertEquals(100, buffer.high());
        assertTrue(buffer.apply(sent(18, 0, 105, full(1, 4.0)), id -> true, 4, b -> {}));
        assertEquals(106, buffer.high());
        assertEquals(4.28, buffer.frames(1).get(106L).x());
        assertEquals(1, buffer.defects());
        assertFalse(buffer.broken());
    }

    @Test
    void reorderCountBoundProducesOneRestartAndRejectedRequestRetriesAfterCooldown() {
        var buffer = new ClientSnapshotBuffer();
        assertTrue(buffer.apply(sent(17, 0, 100), id -> false, 0, b -> {}));
        for (int n = 0; n <= ClientSnapshotBuffer.MAX_HELD_BATCHES; n++)
            assertFalse(buffer.apply(sent(17, 102 + n, 102 + n), id -> false, 1, b -> {}));
        assertEquals(100, buffer.high());
        assertEquals(1, buffer.defects());
        assertNotNull(buffer.restartRequest(2));
        assertNull(buffer.restartRequest(3));
        assertNull(buffer.restartRequest(10_001));
        assertNotNull(buffer.restartRequest(10_002));
        assertTrue(buffer.apply(sent(18, 0, 200), id -> false, 10_003, b -> {}));
        assertFalse(buffer.broken());
        assertEquals(1, buffer.defects());
    }

    @Test
    void gapTimeoutIsStrictAndDoesNotRenewWhenMoreSuccessorsArrive() {
        var buffer = new ClientSnapshotBuffer();
        assertTrue(buffer.apply(sent(17, 0, 100), id -> false, 0, b -> {}));
        assertFalse(buffer.apply(sent(17, 102, 102), id -> false, 1, b -> {}));
        assertFalse(buffer.apply(sent(17, 103, 103), id -> false, 500, b -> {}));
        assertFalse(buffer.expireHeld(501));
        assertTrue(buffer.expireHeld(502));
        assertEquals(1, buffer.defects());
        assertFalse(buffer.expireHeld(503));
        assertEquals(100, buffer.high());
    }

    @Test
    void restartDiscardsRetiredPendingCapacityBeforeAdoptingFreshPendingSet() {
        var oldSpecs = new RaknetSnapshotSwitchTest.Spec[4096];
        var freshSpecs = new RaknetSnapshotSwitchTest.Spec[4096];
        for (int id = 0; id < oldSpecs.length; id++) {
            oldSpecs[id] = full(id, 1.0);
            freshSpecs[id] = full(id + 4096, 2.0);
        }
        var buffer = new ClientSnapshotBuffer();
        assertTrue(buffer.apply(sent(17, 0, 100, oldSpecs), id -> false));
        assertFalse(buffer.apply(sent(17, 102, 102), id -> false));
        assertTrue(buffer.expireHeld(Double.MAX_VALUE));
        assertNotNull(buffer.restartRequest());
        assertTrue(buffer.apply(sent(18, 0, 105, freshSpecs), id -> false));
        assertEquals(4096, buffer.size());
        buffer.add(8191, 0);
        assertEquals(2.0, buffer.frames(8191).get(105L).x());
        assertNull(buffer.frames(0).get(105L));
    }

    @Test
    void emptyRestartStartsAnHonestPrefixAndLaterPairingStillNeedsFullReset() {
        var buffer = new ClientSnapshotBuffer();
        buffer.add(1, 0);
        assertTrue(buffer.apply(sent(17, 0, 100), id -> true));
        assertFalse(buffer.apply(sent(17, 102, 102), id -> true));
        assertTrue(buffer.expireHeld(Double.MAX_VALUE));
        assertNotNull(buffer.restartRequest());
        assertTrue(buffer.apply(sent(18, 0, 105), id -> true));
        assertFalse(buffer.hasLiveSamples(1));
        assertTrue(buffer.apply(sent(18, 106, 106, full(1, 1.0)), id -> true));
        assertEquals(1.0, buffer.frames(1).get(106L).x());
    }

    @Test
    void malformedRestartPublishesNoPartialBatchAndNeverBorrowsOldBaseline() {
        for (var spec :
                List.of(
                        delta(1, 2.8),
                        new RaknetSnapshotSwitchTest.Spec(
                                1, 2.0, AnimationRecipe.SnapshotRecord.FLAG_FULL))) {
            var buffer = new ClientSnapshotBuffer();
            buffer.add(1, 0);
            assertTrue(buffer.apply(sent(17, 0, 100, full(1, 1.0)), id -> true));
            assertFalse(buffer.apply(sent(17, 102, 102), id -> true));
            assertTrue(buffer.expireHeld(Double.MAX_VALUE));
            assertNotNull(buffer.restartRequest());
            assertFalse(buffer.apply(sent(18, 0, 105, spec, full(2, 8.0)), id -> true));
            assertEquals("PAIRING_FULL", buffer.defectReason());
            assertEquals(100, buffer.high());
            assertEquals(17, buffer.epoch());
            assertNull(buffer.frames(1).get(100L));
            assertNull(buffer.restartRequest());
        }
    }

    @Test
    void diagnosticsWarnOnlyOncePerConnectionAndKeepLastBranch() {
        var logger = mock(org.slf4j.Logger.class);
        try (var logs = mockStatic(com.mojang.logging.LogUtils.class)) {
            logs.when(com.mojang.logging.LogUtils::getLogger).thenReturn(logger);
            var buffer = new ClientSnapshotBuffer();
            assertTrue(buffer.apply(sent(17, 0, 100), id -> false));
            assertFalse(buffer.apply(sent(17, 102, 102, delta(1, 2.24)), id -> false));
            assertTrue(buffer.expireHeld(Double.MAX_VALUE));
            assertNotNull(buffer.restartRequest());
            assertFalse(buffer.apply(sent(18, 0, 103, delta(1, 2.24)), id -> false));
            assertEquals("PAIRING_FULL", buffer.defectReason());
            assertEquals(2, buffer.defects());
            verify(logger)
                    .warn(
                            "Snapshot protocol defect: reason={} detail={} epoch={} batchTick={}"
                                    + " previousTickPlusOne={} high={} records={}",
                            SnapshotRestartC2SPacket.Reason.STREAM_ORDER,
                            "Snapshot reorder timeout",
                            17L,
                            102L,
                            102L,
                            100L,
                            1);
            verifyNoMoreInteractions(logger);
        }
    }

    @Test
    void diagnosticsDistinguishSettingsDeltaKindAndCapacity() {
        var settings = new ClientSnapshotBuffer();
        assertFalse(
                settings.apply(
                        PlayerSnapshotS2CPacket.animation(
                                17,
                                0,
                                100,
                                4,
                                2,
                                10,
                                500,
                                new AnimationRecipe.Batch(
                                        CONNECTION, SCENE, List.of(), List.of(), List.of())),
                        id -> false));
        assertEquals("SETTINGS", settings.defectReason());
        var delta = new ClientSnapshotBuffer();
        assertTrue(delta.apply(sent(17, 0, 100, full(1, 1.0)), id -> false));
        var kindIdentity =
                new AnimationState.Identity(
                        CONNECTION,
                        17,
                        SCENE,
                        1,
                        1,
                        SUBSCRIPTION,
                        TARGET,
                        0,
                        0,
                        0,
                        0,
                        AnimationSchema.layoutDigest(),
                        ZERO);
        assertFalse(
                delta.apply(
                        sent(
                                17,
                                101,
                                101,
                                List.of(
                                        new AnimationRecipe.SnapshotRecord(
                                                kindIdentity,
                                                0,
                                                ExactFixtures.checkpoint(kindIdentity, 101, 1.28)
                                                        .placement(),
                                                null)),
                                List.of()),
                        id -> false));
        assertEquals("DELTA_BASELINE", delta.defectReason());
        var capacity = new ClientSnapshotBuffer();
        for (int id = 0; id <= 4096; id++) capacity.add(id, 0);
        assertFalse(capacity.apply(sent(17, 0, 100), id -> false));
        assertEquals("CAPACITY", capacity.defectReason());
        for (var failed : List.of(settings, delta, capacity)) {
            assertEquals(1, failed.defects());
            assertTrue(failed.broken());
        }
    }

    @Test
    void sceneRestartsClockRewarmsAndRetiredPacketsDoNotEraseReceipt() {
        var mc = mock(Minecraft.class);
        var p = ClientCombatNetLogTest.player(1);
        var requests = new ArrayList<SnapshotRestartC2SPacket>();
        try (var app = mockStatic(Minecraft.class);
                var network = mockStatic(ClientPlayNetworking.class)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            network.when(() -> ClientPlayNetworking.send(any(SnapshotRestartC2SPacket.class)))
                    .thenAnswer(
                            call -> {
                                requests.add(call.getArgument(0));
                                return null;
                            });
            ClientCombatScene.add(p);
            double now = ClientCombatScene.nowMs();
            ClientCombatNetLogTest.warm(now);
            var before = ClientCombatScene.receipt();
            assertNotNull(before);
            assertEquals(17, before.observerStreamEpoch());
            ClientCombatScene.accept(sent(17, 109, 109, delta(1, 2.24)), now + 450);
            ClientCombatScene.mainFrame(now + 951);
            assertNull(ClientCombatScene.receipt());
            assertEquals(
                    "STREAM_ORDER", ClientCombatScene.debugSnapshot().get("protocolDefectReason"));
            assertEquals(1, requests.size());
            ClientCombatScene.sceneReset();
            ClientCombatScene.add(p);
            for (int n = 0; n < 8; n++) {
                long tick = 110 + n;
                ClientCombatScene.accept(
                        sent(
                                18,
                                n == 0 ? 0 : tick,
                                tick,
                                n == 0 ? full(1, 4.0) : delta(1, 4.0 + n * .28)),
                        now + 1000 + n * 50);
                ClientCombatScene.mainFrame(now + 1000 + n * 50);
            }
            var receipt = ClientCombatScene.receipt();
            assertNotNull(receipt);
            assertEquals(18, receipt.observerStreamEpoch());
            assertEquals(117, receipt.high());
            ClientCombatScene.accept(sent(17, 108, 108, delta(1, 9.99)), now + 1352);
            assertEquals(receipt, ClientCombatScene.receipt());
            assertEquals(1, requests.size());
            ClientCombatScene.disconnect();
            assertEquals("", ClientCombatScene.debugSnapshot().get("protocolDefectReason"));
            assertEquals(0L, ClientCombatScene.debugSnapshot().get("protocolDefects"));
            assertEquals(0L, ClientCombatScene.debugSnapshot().get("sequence"));
        }
    }
}
