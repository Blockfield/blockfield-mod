package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.common.combat.AnimationEvent;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationTypes.NativeFamily;
import com.lunares.blockfield.network.packet.AnimationEventS2CPacket;
import com.lunares.blockfield.network.packet.SnapshotRestartC2SPacket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ParcoolBatchContainmentTest {
    @AfterEach
    void reset() {
        ClientCombatScene.disconnect();
    }

    private static AnimationEventS2CPacket truncatedBatch() {
        var base = ExactFixtures.create(ExactFixtures.identity(), 100, 1, 0, true, 1_000_000L);
        byte[] marker = ClientAnimationJournal.PARCOOL_BATCH;
        var digest = AnimationResources.Digest.hash(marker);
        var ref = new AnimationResources.DefinitionRef(0, 0, digest.a() & Long.MAX_VALUE, digest);
        var dependency =
                new AnimationResources.Definition(
                        ref, new AnimationResources.Bytes(marker.clone()), List.of());
        var payload =
                new AnimationEvent.NativePayload(
                        ref,
                        new AnimationResources.Bytes("truncated".getBytes(StandardCharsets.UTF_8)));
        var event =
                new AnimationEvent(
                        base.identity(),
                        base.id(),
                        base.predecessorEventSequence(),
                        NativeFamily.PARCOOL,
                        base.kind(),
                        base.broadcastStage(),
                        base.acceptedNs(),
                        base.broadcastNs(),
                        base.seed(),
                        base.patch(),
                        List.of(dependency),
                        payload);
        return new AnimationEventS2CPacket(List.of(event));
    }

    @Test
    void truncatedParcoolBatchIsContainedAsWireDefect() {
        var mc = mock(Minecraft.class);
        var restarts = new ArrayList<SnapshotRestartC2SPacket>();
        try (MockedStatic<Minecraft> app = mockStatic(Minecraft.class);
                MockedStatic<ClientPlayNetworking> network =
                        mockStatic(ClientPlayNetworking.class)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            network.when(() -> ClientPlayNetworking.send(any(SnapshotRestartC2SPacket.class)))
                    .thenAnswer(
                            call -> {
                                restarts.add(call.getArgument(0));
                                return null;
                            });
            // Before the fix the decoder throw propagated out of the packet handler (client crash).
            assertDoesNotThrow(() -> ClientCombatScene.parcoolBatch(truncatedBatch()));
            assertEquals(1L, ClientCombatScene.debugSnapshot().get("protocolDefects"));
            assertEquals(
                    "STREAM_ORDER", ClientCombatScene.debugSnapshot().get("protocolDefectReason"));
            assertEquals(1, restarts.size());
        }
    }
}
