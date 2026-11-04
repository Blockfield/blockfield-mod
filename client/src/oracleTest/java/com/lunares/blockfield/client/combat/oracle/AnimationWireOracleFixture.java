package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.combat.AnimationWireReceiver;
import com.lunares.blockfield.client.combat.ClientCombatScene;
import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.lunares.blockfield.common.combat.AnimationEvent;
import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationRefTable;
import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationWire;
import com.lunares.blockfield.common.combat.AnimationWire.Family;
import com.lunares.blockfield.common.combat.AnimationWire.Header;
import com.lunares.blockfield.network.packet.AnimationEventS2CPacket;
import com.lunares.blockfield.network.packet.AnimationWireS2CPacket;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Production network stage -> scene/journal -> transformed native preparation, without a socket.
 */
class AnimationWireOracleFixture {
    static final long MS = 1_000_000;
    static final Definition PADDING = padding();

    @BeforeAll
    static void bootstrap() throws Exception {
        SceneTimingOracleFixture.bootstrap();
    }

    static Definition padding() {
        byte[] value = new byte[AnimationWire.BODY * 2];
        Arrays.fill(value, (byte) 7);
        var digest = Digest.hash(value);
        return new Definition(
                new DefinitionRef(1, 0, 992_001, digest), new Bytes(value), List.of());
    }

    static AnimationEvent padded(AnimationEvent e) {
        var definitions = new ArrayList<>(e.dependencies());
        definitions.add(PADDING);
        return new AnimationEvent(
                e.identity(),
                e.id(),
                e.predecessorEventSequence(),
                e.family(),
                e.kind(),
                e.broadcastStage(),
                e.acceptedNs(),
                e.broadcastNs(),
                e.seed(),
                e.patch(),
                List.copyOf(definitions),
                e.nativePayload());
    }

    static CustomPacketPayload padded(CustomPacketPayload payload) {
        if (payload instanceof PlayerSnapshotS2CPacket packet) {
            var batch = packet.animation();
            var definitions = new ArrayList<>(batch.definitions());
            definitions.add(PADDING);
            return PlayerSnapshotS2CPacket.animation(
                    packet.observerStreamEpoch(),
                    packet.previousTickPlusOne(),
                    packet.serverTick(),
                    packet.interpMin(),
                    packet.interpMax(),
                    packet.dilationPercent(),
                    packet.holdToSnapMs(),
                    new AnimationRecipe.Batch(
                            batch.connectionId(),
                            batch.sceneGeneration(),
                            batch.records(),
                            definitions,
                            batch.seeds()));
        }
        var events = ((AnimationEventS2CPacket) payload).events();
        return new AnimationEventS2CPacket(
                events.stream().map(AnimationWireOracleFixture::padded).toList());
    }

    /**
     * Installed by the real transformed Connection.channelActive, then observed before its
     * listener.
     */
    static final class Wire implements AutoCloseable {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final AnimationWireReceiver receiver;
        final java.util.concurrent.ExecutorService network =
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        runnable -> new Thread(runnable, "wire-client-oracle-network"));
        long sequence;
        int fragments, transactions;

        Wire() {
            var connection = new Connection(PacketFlow.CLIENTBOUND);
            onNetwork(
                    () -> {
                        channel.pipeline().addLast("packet_handler", connection);
                        channel.pipeline().fireChannelActive();
                    });
            receiver = AnimationWireReceiver.of(connection);
            assertSame(receiver, channel.pipeline().get(AnimationWireReceiver.NAME));
            assertTrue(
                    channel.pipeline().names().indexOf(AnimationWireReceiver.NAME)
                            < channel.pipeline().names().indexOf("packet_handler"));
            // The tail records the logical packet the real listener would receive.
            onNetwork(() -> channel.pipeline().remove(connection));
            OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
        }

        List<AnimationWireS2CPacket> packets(CustomPacketPayload payload, AnimationRefTable table) {
            long connection, stream, scene, tick, previous;
            Family family;
            byte[] body;
            if (payload instanceof PlayerSnapshotS2CPacket snapshot) {
                connection = snapshot.animation().connectionId();
                scene = snapshot.animation().sceneGeneration();
                stream = snapshot.observerStreamEpoch();
                tick = snapshot.serverTick();
                previous = snapshot.previousTickPlusOne();
                family = Family.SNAPSHOT;
                body = AnimationWireS2CPacket.snapshot(snapshot, table, null);
            } else {
                var events = (AnimationEventS2CPacket) payload;
                var event = events.events().getFirst();
                connection = event.identity().connectionId();
                scene = event.identity().sceneGeneration();
                stream = event.identity().observerStreamEpoch();
                tick = event.id().serverTick();
                previous = 0;
                family = Family.EVENT;
                body = AnimationWireS2CPacket.events(events, table, null);
            }
            return AnimationWireS2CPacket.packets(
                    Header.of(
                            family,
                            table == null ? 0 : table.generation(),
                            connection,
                            stream,
                            scene,
                            ++sequence,
                            tick,
                            previous,
                            body),
                    body);
        }

        void send(AnimationWireS2CPacket packet) {
            // Use the registered production payload codec before the actual network-stage handler.
            byte[] bytes =
                    FxClientWireOracleFixture.encode(AnimationWireS2CPacket.CODEC::encode, packet);
            onNetwork(
                    () -> {
                        assertEquals(
                                "wire-client-oracle-network", Thread.currentThread().getName());
                        channel.writeInbound(
                                new ClientboundCustomPayloadPacket(
                                        FxClientWireOracleFixture.decode(
                                                AnimationWireS2CPacket.CODEC::decode, bytes)));
                    });
            fragments++;
        }

        void receive(CustomPacketPayload payload, long now, AnimationRefTable table) {
            var packets = packets(payload, table);
            assertTrue(packets.size() > 1, "force real snapshot and capsule fragmentation");
            var before = ClientCombatScene.debugSnapshot();
            for (int i = 0; i < packets.size() - 1; i++) {
                send(packets.get(i));
                assertNull(channel.readInbound(), "partial transaction never reaches main");
                assertEquals(
                        before,
                        ClientCombatScene.debugSnapshot(),
                        "no H/seed/start/native side effect");
            }
            send(packets.getLast());
            var decoded = ((ClientboundCustomPayloadPacket) channel.readInbound()).payload();
            assertEquals(payload, decoded);
            assertNull(channel.readInbound());
            FxClientWireOracleFixture.Replay.apply(decoded, now);
            transactions++;
        }

        void onNetwork(Runnable work) {
            try {
                network.submit(work).get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError("network-stage fixture failed", failure);
            }
        }

        @Override
        public void close() {
            try {
                onNetwork(channel::finishAndReleaseAll);
            } finally {
                network.shutdownNow();
                try {
                    assertTrue(network.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }
    }

    static void defect(Object value) {
        assertInstanceOf(net.minecraft.network.protocol.Packet.class, value);
        assertFalse(
                value instanceof ClientboundCustomPayloadPacket,
                "failure is never a decoded snapshot/event");
    }

    @Test
    void fragmentedProductionStreamKeepsEveryApplicationAndTicketByte() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "wire-client-production-stream",
                () -> {
                    var vector = FxClientWireOracleFixture.vector();
                    try (var replay =
                                    new FxClientWireOracleFixture.Replay(
                                            vector.getAsJsonObject("server"));
                            var wire = new Wire()) {
                        var table = AnimationRefTable.of(1, List.of(PADDING.ref()));
                        wire.receiver.register(table);
                        wire.receiver.retain(Set.of(1L));
                        replay.intake = (payload, now) -> wire.receive(padded(payload), now, table);
                        replay.play(
                                java.util.function.UnaryOperator.identity(),
                                replay.end() + 4 * FxClientWireOracleFixture.FRAME,
                                now -> {
                                    var receipt = ClientCombatScene.receipt();
                                    var report =
                                            receipt == null || receipt.exact() == null
                                                    ? null
                                                    : receipt.exact().report();
                                    if (report == null) return;
                                    var row = report.victims().getFirst();
                                    boolean held =
                                            row.starts().stream()
                                                    .anyMatch(
                                                            s ->
                                                                    s.layerTag()
                                                                            == LayerTag
                                                                                    .CREATE_HANGING);
                                    boolean exited = !held && row.applications().size() == 2;
                                    int phase = replay.tickets;
                                    // The live player attacks on consecutive frames (see the
                                    // recorded clientNs): entry plus one confirmation frame.
                                    if (phase == 0 && held
                                            || phase == 1 && held
                                            || phase == 2 && exited
                                            || phase == 3 && exited) {
                                        replay.ticket();
                                    }
                                });
                        assertEquals(4, replay.tickets);
                        assertEquals(
                                vector.get("client"),
                                replay.client(),
                                "fragmentation changes no start/ticket byte");
                        assertTrue(DrivenPresentation.driven(replay.world.driven));
                        OracleTransformAudit.requireConfig("playerAnimator-common.mixins.json");
                        return Map.of(
                                "transactions",
                                wire.transactions,
                                "fragments",
                                wire.fragments,
                                "tickets",
                                replay.tickets,
                                "c2sByteIdentity",
                                true,
                                "addedClientTicks",
                                0);
                    }
                });
    }

    @Test
    void fragmentedNativeCarrierRunsOnceAtTheFinalFragmentAndRevisesTheSameFrame() {
        AnimSyncOracleRunner.run(
                "lr",
                "wire-client-native-final-fragment",
                () -> {
                    ClientCombatScene.disconnect();
                    var world = new SceneTimingOracleFixture.World();
                    try (var wire = new Wire()) {
                        long now = 5_060 * MS;
                        var level =
                                (net.minecraft.client.multiplayer.ClientLevel) world.driven.level();
                        NativeCapsuleOracleFixture.attach(level);
                        ExactSceneOracleFixture.prime(world, now);
                        var event =
                                padded(
                                        NativeCapsuleOracleFixture.lrCarried(
                                                world.capsule(112, 112 * 256 + 1, 0, now),
                                                world.driven.getId()));
                        var payload = new AnimationEventS2CPacket(List.of(event));
                        Class<?> pre =
                                Class.forName("me.xjqsh.lrtactical.api.event.MeleePreAttackEvent");
                        var before = ClientCombatScene.debugSnapshot();
                        try (var constructed = mockConstruction(pre)) {
                            var packets = wire.packets(payload, null);
                            assertTrue(packets.size() > 1);
                            for (int i = 0; i < packets.size() - 1; i++) {
                                wire.send(packets.get(i));
                                assertNull(wire.channel.readInbound());
                                assertTrue(constructed.constructed().isEmpty());
                            }
                            wire.send(packets.getLast());
                            var complete =
                                    ((ClientboundCustomPayloadPacket) wire.channel.readInbound())
                                            .payload();
                            FxClientWireOracleFixture.Replay.apply(complete, now + MS);
                            assertEquals(
                                    1,
                                    constructed.constructed().size(),
                                    "native callback in this application");
                            assertEquals(
                                    before.get("frameId"),
                                    ClientCombatScene.debugSnapshot().get("frameId"));
                            // A repeated native event in a fresh logical transaction is idempotent.
                            wire.receive(payload, now + 2 * MS, null);
                            assertEquals(1, constructed.constructed().size());
                            OracleTransformAudit.requireConfig("lrtactical.mixins.json");
                            return Map.of(
                                    "nativeRuns",
                                    1,
                                    "fragments",
                                    packets.size(),
                                    "addedClientTicks",
                                    0);
                        }
                    } finally {
                        ClientCombatScene.disconnect();
                        world.close();
                    }
                });
    }

    @Test
    void overlapRetirementAndConfigurationUseOnlyThisConnectionsConfirmedTables() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "wire-client-table-lifecycle",
                () -> {
                    try (var wire = new Wire()) {
                        var table1 = AnimationRefTable.of(1, List.of(PADDING.ref()));
                        var nextRef =
                                new DefinitionRef(
                                        2,
                                        0,
                                        PADDING.ref().contentId(),
                                        PADDING.ref().dependencyDigest());
                        var next = new Definition(nextRef, PADDING.value(), List.of());
                        var table2 = AnimationRefTable.of(2, List.of(nextRef));
                        wire.receiver.register(table1);
                        wire.receiver.register(table2);
                        wire.receiver.retain(Set.of(1L, 2L));
                        var snapshot =
                                PlayerSnapshotS2CPacket.animation(
                                        17,
                                        0,
                                        100,
                                        2,
                                        6,
                                        10,
                                        500,
                                        new AnimationRecipe.Batch(
                                                3, 1, List.of(), List.of(next), List.of()));
                        for (var table : List.of(table1, table2)) {
                            var parts = wire.packets(snapshot, table);
                            for (int i = 0; i < parts.size() - 1; i++) {
                                wire.send(parts.get(i));
                                assertNull(wire.channel.readInbound());
                                assertNotNull(wire.receiver.table(1));
                            }
                            wire.send(parts.getLast());
                            assertEquals(
                                    snapshot,
                                    ((ClientboundCustomPayloadPacket) wire.channel.readInbound())
                                            .payload());
                            assertNotNull(wire.receiver.table(1), "old assets still retain G-1");
                        }
                        wire.receiver.retain(Set.of(2L));
                        assertNull(
                                wire.receiver.table(1),
                                "complete newer transaction plus asset retirement");
                        var retired = wire.packets(snapshot, table1);
                        wire.send(retired.getFirst());
                        assertInstanceOf(
                                net.minecraft.network.protocol.Packet.class,
                                wire.channel.readInbound());
                        assertNull(wire.channel.readInbound());
                        // This value has 3 fixed-partition fragments, so index 2 skips index 1.
                        for (boolean repeat : List.of(false, true)) {
                            var broken = wire.packets(snapshot, table2);
                            wire.send(broken.getFirst());
                            assertNull(wire.channel.readInbound());
                            wire.send(repeat ? broken.getFirst() : broken.getLast());
                            assertInstanceOf(
                                    net.minecraft.network.protocol.Packet.class,
                                    wire.channel.readInbound());
                            assertNull(wire.channel.readInbound());
                        }
                        wire.receiver.clear();
                        wire.receiver.register(table2);
                        var partial = wire.packets(snapshot, table2);
                        wire.send(partial.getFirst());
                        assertNull(wire.channel.readInbound());
                        wire.onNetwork(
                                () ->
                                        wire.channel.writeInbound(
                                                ClientboundStartConfigurationPacket.INSTANCE));
                        assertSame(
                                ClientboundStartConfigurationPacket.INSTANCE,
                                wire.channel.readInbound());
                        assertNull(wire.receiver.table(2));
                        // An old main-thread READY callback may run after netty observed
                        // StartConfiguration.
                        wire.receiver.register(table2);
                        wire.receiver.configuration();
                        assertNull(
                                wire.receiver.table(2),
                                "main INIT fences late old READY publication");
                        wire.send(partial.getLast());
                        assertFalse(
                                wire.channel.readInbound()
                                        instanceof ClientboundCustomPayloadPacket);
                        // Same number on a new backend is never resolved through its predecessor's
                        // list.
                        var newBackend = AnimationRefTable.of(2, List.of());
                        wire.receiver.register(newBackend);
                        assertSame(newBackend, wire.receiver.table(2));
                        assertEquals(-1, newBackend.handle(nextRef));
                        return Map.of(
                                "overlapModes", 2, "oldTableRetired", true, "switchCleared", true);
                    }
                });
    }

    @Test
    void completeReorderedCapsulesWaitForTheirPredecessorAndDuplicatesStayIdempotent() {
        AnimSyncOracleRunner.run(
                "lr",
                "wire-client-causal-reorder",
                () -> {
                    ClientCombatScene.disconnect();
                    var world = new SceneTimingOracleFixture.World();
                    try (var wire = new Wire()) {
                        long now = 5_060 * MS;
                        NativeCapsuleOracleFixture.attach(
                                (net.minecraft.client.multiplayer.ClientLevel)
                                        world.driven.level());
                        ExactSceneOracleFixture.prime(world, now);
                        long firstId = 112 * 256 + 1;
                        var first =
                                padded(
                                        NativeCapsuleOracleFixture.lrCarried(
                                                world.capsule(112, firstId, 0, now),
                                                world.driven.getId()));
                        var second =
                                padded(
                                        NativeCapsuleOracleFixture.lrCarried(
                                                world.capsule(112, firstId + 1, firstId, now),
                                                world.driven.getId()));
                        Class<?> pre =
                                Class.forName("me.xjqsh.lrtactical.api.event.MeleePreAttackEvent");
                        try (var constructed = mockConstruction(pre)) {
                            wire.receive(
                                    new AnimationEventS2CPacket(List.of(second)), now + MS, null);
                            assertEquals(
                                    0,
                                    constructed.constructed().size(),
                                    "causal hold, not an animation clock wait");
                            wire.receive(
                                    new AnimationEventS2CPacket(List.of(first)),
                                    now + 2 * MS,
                                    null);
                            assertEquals(
                                    2,
                                    constructed.constructed().size(),
                                    "both native bodies release in this callback");
                            wire.receive(
                                    new AnimationEventS2CPacket(List.of(second)),
                                    now + 3 * MS,
                                    null);
                            assertEquals(
                                    2,
                                    constructed.constructed().size(),
                                    "duplicate event in a new transaction");
                            OracleTransformAudit.requireConfig("lrtactical.mixins.json");
                            return Map.of(
                                    "reorderedNativeRuns",
                                    2,
                                    "duplicateNativeRuns",
                                    0,
                                    "addedClientTicks",
                                    0);
                        }
                    } finally {
                        ClientCombatScene.disconnect();
                        world.close();
                    }
                });
    }

    @Test
    void fabricAdvertisesThePhysicalChannelWhileTheConnectionOwnsIntake() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "wire-client-channel-registration",
                () -> {
                    net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                            .register(AnimationWireS2CPacket.TYPE, AnimationWireS2CPacket.CODEC);
                    AnimationWireReceiver.register();
                    try (var wire = new Wire()) {
                        assertTrue(
                                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                                        .getGlobalReceivers()
                                        .contains(AnimationWireS2CPacket.TYPE.id()));
                        assertSame(
                                wire.receiver,
                                wire.channel.pipeline().get(AnimationWireReceiver.NAME));
                        return Map.of(
                                "advertisedChannel",
                                AnimationWireS2CPacket.TYPE.id().toString(),
                                "receiveStage",
                                "before packet_handler");
                    } finally {
                        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                                .unregisterGlobalReceiver(AnimationWireS2CPacket.TYPE.id());
                    }
                });
    }
}
