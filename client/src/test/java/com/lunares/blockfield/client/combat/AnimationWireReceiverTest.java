package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationRefTable;
import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationWire;
import com.lunares.blockfield.common.combat.AnimationWire.Family;
import com.lunares.blockfield.common.combat.AnimationWire.Fragment;
import com.lunares.blockfield.common.combat.AnimationWire.Header;
import com.lunares.blockfield.network.packet.AnimationEventS2CPacket;
import com.lunares.blockfield.network.packet.AnimationWireS2CPacket;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Set;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import org.junit.jupiter.api.Test;

class AnimationWireReceiverTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    static Definition definition(long generation, int size) {
        byte[] bytes = new byte[size];
        bytes[size - 1] = 17;
        var digest = Digest.hash(bytes);
        return new Definition(
                new DefinitionRef(generation, 0, 91, digest), new Bytes(bytes), List.of());
    }

    static PlayerSnapshotS2CPacket snapshot(long tick, Definition definition) {
        return PlayerSnapshotS2CPacket.animation(
                3,
                tick == 100 ? 0 : tick,
                tick,
                2,
                6,
                10,
                500,
                new AnimationRecipe.Batch(7, 0, List.of(), List.of(definition), List.of()));
    }

    static List<AnimationWireS2CPacket> fragments(
            PlayerSnapshotS2CPacket packet, AnimationRefTable table) {
        byte[] body = AnimationWireS2CPacket.snapshot(packet, table, null);
        return AnimationWireS2CPacket.packets(
                Header.of(
                        Family.SNAPSHOT,
                        table == null ? 0 : table.generation(),
                        7,
                        packet.observerStreamEpoch(),
                        0,
                        packet.serverTick(),
                        packet.serverTick(),
                        packet.previousTickPlusOne(),
                        body),
                body);
    }

    static void send(EmbeddedChannel channel, AnimationWireS2CPacket packet) {
        channel.writeInbound(new ClientboundCustomPayloadPacket(packet));
    }

    static void defect(Object value) {
        assertInstanceOf(Packet.class, value);
        assertFalse(
                value instanceof ClientboundCustomPayloadPacket,
                "a defect cannot publish decoded state");
    }

    @Test
    void nothingPublishesUntilTheLastFragmentAndOtherFamiliesKeepTheirPosition() {
        var receiver = new AnimationWireReceiver();
        var channel = new EmbeddedChannel(receiver);
        try {
            var packet = snapshot(100, definition(1, AnimationWire.BODY * 2));
            var fragments = fragments(packet, null);
            assertEquals(3, fragments.size());
            send(channel, fragments.getFirst());
            assertNull(channel.readInbound());
            byte[] event =
                    AnimationWireS2CPacket.events(
                            new AnimationEventS2CPacket(List.of()), null, null);
            var header = Header.of(Family.EVENT, 0, 7, 3, 0, 1, 100, 0, event);
            send(channel, AnimationWireS2CPacket.packets(header, event).getFirst());
            var delivered = (ClientboundCustomPayloadPacket) channel.readInbound();
            assertEquals(new AnimationEventS2CPacket(List.of()), delivered.payload());
            Object nativeMarker = new Object();
            channel.writeInbound(nativeMarker);
            assertSame(nativeMarker, channel.readInbound());
            send(channel, fragments.get(1));
            assertNull(channel.readInbound());
            send(channel, fragments.getLast());
            assertEquals(
                    packet, ((ClientboundCustomPayloadPacket) channel.readInbound()).payload());
            assertNull(channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void gapsRepeatsAndSameFamilyReplacementEmitOnlyRecoveryAndAllowFreshFull() {
        for (String defect : List.of("gap", "repeat", "replacement")) {
            var receiver = new AnimationWireReceiver();
            var channel = new EmbeddedChannel(receiver);
            try {
                var fragments =
                        fragments(snapshot(100, definition(1, AnimationWire.BODY * 2)), null);
                send(channel, fragments.getFirst());
                assertNull(channel.readInbound());
                send(
                        channel,
                        switch (defect) {
                            case "gap" -> fragments.getLast();
                            case "repeat" -> fragments.getFirst();
                            default -> fragments(snapshot(101, definition(1, 2)), null).getFirst();
                        });
                defect(channel.readInbound());
                assertNull(channel.readInbound());
                var fresh = snapshot(102, definition(1, 2));
                send(channel, fragments(fresh, null).getFirst());
                assertEquals(
                        fresh, ((ClientboundCustomPayloadPacket) channel.readInbound()).payload());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void badCrcNeverLeaksTheDecodedSnapshot() {
        var channel = new EmbeddedChannel(new AnimationWireReceiver());
        try {
            var fragments = fragments(snapshot(100, definition(1, AnimationWire.BODY)), null);
            send(channel, fragments.getFirst());
            var last = fragments.getLast().fragment();
            byte[] corrupt = last.body();
            corrupt[corrupt.length - 1] ^= 1;
            send(
                    channel,
                    new AnimationWireS2CPacket(new Fragment(last.header(), last.index(), corrupt)));
            defect(channel.readInbound());
            assertNull(channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void unknownTableIsRecoveryAndLiteralOnlyHandlesAreRejected() {
        var ref = definition(1, 5).ref();
        var table = AnimationRefTable.of(1, List.of(ref));
        var packet = snapshot(100, definition(1, 5));
        for (boolean zeroHeader : List.of(false, true)) {
            var channel = new EmbeddedChannel(new AnimationWireReceiver());
            try {
                var fragment = fragments(packet, table).getFirst().fragment();
                var h = fragment.header();
                if (zeroHeader)
                    h =
                            new Header(
                                    h.family(),
                                    0,
                                    h.connectionId(),
                                    h.observerStreamEpoch(),
                                    h.sceneGeneration(),
                                    h.logicalSequence(),
                                    h.logicalTick(),
                                    h.previousTickPlusOne(),
                                    h.decodedLength(),
                                    h.crc32c());
                send(channel, new AnimationWireS2CPacket(new Fragment(h, 0, fragment.body())));
                defect(channel.readInbound());
                assertNull(channel.readInbound());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void oldTableWaitsForBothAssetRetirementAndACompleteNewerTransaction() {
        var receiver = new AnimationWireReceiver();
        var first = definition(1, 5);
        var next = definition(2, AnimationWire.BODY);
        var table1 = AnimationRefTable.of(1, List.of(first.ref()));
        var table2 = AnimationRefTable.of(2, List.of(next.ref()));
        receiver.register(table1);
        receiver.register(table2);
        receiver.retain(Set.of(1L, 2L));
        var channel = new EmbeddedChannel(receiver);
        try {
            // A G+1 state is literal while the observer's confirmed table is G.
            var overlap = snapshot(100, next);
            send(channel, fragments(overlap, table1).getFirst());
            // This snapshot is fragmented even though it uses only literals.
            assertNull(channel.readInbound());
            var oldMode = fragments(overlap, table1);
            for (int i = 1; i < oldMode.size(); i++) send(channel, oldMode.get(i));
            assertEquals(
                    overlap, ((ClientboundCustomPayloadPacket) channel.readInbound()).payload());
            receiver.retain(Set.of(2L));
            assertNotNull(receiver.table(1), "a newer READY alone is not ordered-channel evidence");
            var newMode = fragments(snapshot(101, next), table2);
            send(channel, newMode.getFirst());
            assertNotNull(receiver.table(1), "a partial newer transaction cannot retire a table");
            for (int i = 1; i < newMode.size(); i++) send(channel, newMode.get(i));
            assertNotNull(channel.readInbound());
            assertNull(receiver.table(1));
            assertSame(table2, receiver.table(2));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void configurationStartDiscardsAssembliesAndSameNumberedOldBackendTables() {
        var receiver = new AnimationWireReceiver();
        var table = AnimationRefTable.of(1, List.of(definition(1, AnimationWire.BODY).ref()));
        receiver.register(table);
        var channel = new EmbeddedChannel(receiver);
        try {
            var fragments = fragments(snapshot(100, definition(1, AnimationWire.BODY)), table);
            send(channel, fragments.getFirst());
            channel.writeInbound(ClientboundStartConfigurationPacket.INSTANCE);
            assertSame(ClientboundStartConfigurationPacket.INSTANCE, channel.readInbound());
            assertNull(receiver.table(1));
            send(channel, fragments.getLast());
            defect(channel.readInbound());
            send(channel, fragments(snapshot(101, definition(1, 1)), table).getFirst());
            defect(channel.readInbound());
            assertNull(channel.readInbound());
            receiver.register(table); // old READY task queued before the network barrier
            receiver.configuration(); // main configuration INIT precedes the new manifest
            assertNull(receiver.table(1));
            var replacement = AnimationRefTable.of(1, List.of(definition(1, 1).ref()));
            receiver.register(replacement);
            assertSame(replacement, receiver.table(1));
        } finally {
            channel.finishAndReleaseAll();
        }
        assertNull(receiver.table(1), "disconnect clears tables");
    }

    @Test
    void changingAnAlreadyRegisteredTableFailsClosed() {
        var receiver = new AnimationWireReceiver();
        receiver.register(AnimationRefTable.of(1, List.of(definition(1, 1).ref())));
        assertThrows(
                IllegalArgumentException.class,
                () -> receiver.register(AnimationRefTable.of(1, List.of(definition(1, 2).ref()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> receiver.register(AnimationRefTable.of(0, List.of())));
    }

    @Test
    void wireRecoveryKeepsHAndUsesTheExistingRestartCooldown() {
        var buffer = new ClientSnapshotBuffer();
        var batch = snapshot(100, definition(1, 1));
        assertTrue(buffer.apply(batch, id -> false));
        buffer.wireDefect(3, "CRC32C");
        assertEquals(100, buffer.high());
        assertTrue(buffer.broken());
        var restart = buffer.restartRequest(10_000);
        assertNotNull(restart);
        assertEquals(3, restart.observerStreamEpoch());
        assertNull(buffer.restartRequest(10_001));
        assertNotNull(buffer.restartRequest(20_000));
    }

    @Test
    void bundledWirePacketsKeepNativePacketPositions() {
        var channel = new EmbeddedChannel(new AnimationWireReceiver());
        try {
            var snapshot = snapshot(100, definition(1, AnimationWire.BODY));
            var parts = fragments(snapshot, null);
            var marker =
                    new net.minecraft.network.protocol.game.ClientboundSetTimePacket(1, 2, true);
            var packets =
                    new java.util.ArrayList<
                            net.minecraft.network.protocol.Packet<
                                    ? super
                                            net.minecraft.network.protocol.game
                                                    .ClientGamePacketListener>>();
            packets.add(new ClientboundCustomPayloadPacket(parts.getFirst()));
            packets.add(marker);
            packets.add(new ClientboundCustomPayloadPacket(parts.getLast()));
            channel.writeInbound(
                    new net.minecraft.network.protocol.game.ClientboundBundlePacket(packets));
            var bundle =
                    (net.minecraft.network.protocol.game.ClientboundBundlePacket)
                            channel.readInbound();
            var decoded =
                    new java.util.ArrayList<
                            net.minecraft.network.protocol.Packet<
                                    ? super
                                            net.minecraft.network.protocol.game
                                                    .ClientGamePacketListener>>();
            bundle.subPackets().forEach(decoded::add);
            assertEquals(2, decoded.size());
            assertSame(marker, decoded.getFirst());
            assertEquals(snapshot, ((ClientboundCustomPayloadPacket) decoded.getLast()).payload());
            assertNull(channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void aggregateSnapshotMayExceedTheOld256KiBCap() {
        var value = definition(1, AnimationWire.BODY * 2);
        var definitions = new java.util.ArrayList<Definition>();
        for (int i = 0; i < 7; i++)
            definitions.add(
                    new Definition(
                            new DefinitionRef(1, 0, 100 + i, value.ref().dependencyDigest()),
                            value.value(),
                            List.of()));
        var packet =
                PlayerSnapshotS2CPacket.animation(
                        3,
                        0,
                        100,
                        2,
                        6,
                        10,
                        500,
                        new AnimationRecipe.Batch(7, 0, List.of(), definitions, List.of()));
        var fragments = fragments(packet, null);
        assertTrue(fragments.getFirst().fragment().header().decodedLength() > 256 * 1024);
        var channel = new EmbeddedChannel(new AnimationWireReceiver());
        try {
            for (int i = 0; i < fragments.size() - 1; i++) {
                send(channel, fragments.get(i));
                assertNull(channel.readInbound());
            }
            send(channel, fragments.getLast());
            assertEquals(
                    packet, ((ClientboundCustomPayloadPacket) channel.readInbound()).payload());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void anAlreadyOpenFamilyPinsItsTableAcrossNewerFamilyCompletion() {
        var receiver = new AnimationWireReceiver();
        var old = definition(1, AnimationWire.BODY);
        var table1 = AnimationRefTable.of(1, List.of(old.ref()));
        var table2 = AnimationRefTable.of(2, List.of());
        receiver.register(table1);
        receiver.register(table2);
        receiver.retain(Set.of(2L));
        var channel = new EmbeddedChannel(receiver);
        try {
            var snapshot = snapshot(100, old);
            var parts = fragments(snapshot, table1);
            send(channel, parts.getFirst());
            assertNull(channel.readInbound());
            var events = new AnimationEventS2CPacket(List.of());
            byte[] body = AnimationWireS2CPacket.events(events, table2, null);
            var header = Header.of(Family.EVENT, 2, 7, 3, 0, 1, 100, 0, body);
            send(channel, AnimationWireS2CPacket.packets(header, body).getFirst());
            assertEquals(
                    events, ((ClientboundCustomPayloadPacket) channel.readInbound()).payload());
            assertNull(receiver.table(1), "no new transaction can use the retired table");
            send(channel, parts.getLast());
            assertEquals(
                    snapshot,
                    ((ClientboundCustomPayloadPacket) channel.readInbound()).payload(),
                    "the older family's immutable table was captured at its first fragment");
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
