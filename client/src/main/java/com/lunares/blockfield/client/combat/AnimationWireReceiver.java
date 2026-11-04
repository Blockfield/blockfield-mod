package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.AnimationRefTable;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationWire;
import com.lunares.blockfield.network.packet.AnimationEventS2CPacket;
import com.lunares.blockfield.network.packet.AnimationWireS2CPacket;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;

/**
 * One connection's network-thread wire stage, before packet_handler and across protocol switches.
 * Nonfinal fragments never reach a listener. The last fragment becomes the existing logical packet
 * at exactly that position in the packet stream; Fabric schedules its unchanged application on
 * main.
 */
public final class AnimationWireReceiver extends ChannelInboundHandlerAdapter {
    public static final String NAME = "blockfield_animation_wire";

    /** Implemented by the client Connection mixin, including during configuration. */
    public interface Access {
        AnimationWireReceiver blockfield$animationWire();
    }

    public interface ListenerAccess {
        Connection blockfield$wireConnection();
    }

    private final AnimationWire.Assembler assembler = new AnimationWire.Assembler(true);
    // A permitted cross-family interleave may complete a newer transaction before an older one.
    private final AnimationRefTable[] decodingTables = new AnimationRefTable[2];
    private final ConcurrentHashMap<Long, AnimationRefTable> tables = new ConcurrentHashMap<>();
    private volatile Set<Long> retained = Set.of();
    private volatile long completedTable;
    private long connectionId = -1;

    public static AnimationWireReceiver of(Connection connection) {
        var receiver = ((Access) connection).blockfield$animationWire();
        if (receiver == null) throw new IllegalStateException("Animation wire stage missing");
        return receiver;
    }

    /**
     * Advertise the physical channel; reaching Fabric with a fragment means the stage was bypassed.
     */
    public static void register() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                AnimationWireS2CPacket.TYPE,
                (packet, context) ->
                        ClientCombatScene.wireDefect(
                                packet.fragment().header().observerStreamEpoch(),
                                "Wire fragment bypassed the connection receive stage"));
    }

    /** Main publishes an immutable table before READY can make the server name it. */
    public void register(AnimationRefTable table) {
        if (table == null || table.generation() == 0)
            throw new IllegalArgumentException("READY requires a positive-generation wire table");
        var previous = tables.putIfAbsent(table.generation(), table);
        if (previous != null && !previous.digest().equals(table.digest()))
            throw new IllegalArgumentException("Wire table changed within a generation");
    }

    /** Main publishes the asset-retention scan; tables also wait for ordered-channel evidence. */
    public void retain(Set<Long> generations) {
        retained = Set.copyOf(generations);
        retire();
    }

    private void retire() {
        tables.keySet().removeIf(g -> g < completedTable && !retained.contains(g));
    }

    public AnimationRefTable table(long generation) {
        return tables.get(generation);
    }

    /** Called in stream order, before forwarding configuration start to the listener. */
    public void clear() {
        assembler.clear();
        Arrays.fill(decodingTables, null);
        configuration();
        connectionId = -1;
    }

    /** Main's INIT fence also removes any old READY callback queued before the network barrier. */
    public void configuration() {
        tables.clear();
        retained = Set.of();
        completedTable = 0;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (message instanceof ClientboundBundlePacket bundle) {
            var packets = new ArrayList<Packet<? super ClientGamePacketListener>>();
            for (var packet : bundle.subPackets()) {
                Object decoded = receive(packet);
                if (decoded != null) {
                    @SuppressWarnings("unchecked")
                    var entry = (Packet<? super ClientGamePacketListener>) decoded;
                    packets.add(entry);
                }
            }
            if (!packets.isEmpty()) context.fireChannelRead(new ClientboundBundlePacket(packets));
            return;
        }
        Object decoded = receive(message);
        if (decoded != null) context.fireChannelRead(decoded);
    }

    private Object receive(Object message) {
        if (message instanceof ClientboundStartConfigurationPacket) clear();
        if (!(message instanceof ClientboundCustomPayloadPacket packet)
                || !(packet.payload() instanceof AnimationWireS2CPacket wire)) {
            return message;
        }
        var header = wire.fragment().header();
        try {
            if (connectionId != -1 && connectionId != header.connectionId())
                throw new IllegalArgumentException("Wire connection changed without configuration");
            int slot = header.family().id;
            if (wire.fragment().index() == 0) {
                var table = tables.get(header.tableGeneration());
                if (header.tableGeneration() != 0 && table == null)
                    throw new IllegalArgumentException(
                            "Wire transaction names an unavailable table");
                decodingTables[slot] = table;
            }
            var transaction = assembler.accept(wire.fragment());
            if (transaction == null) return null;
            var table = decodingTables[slot];
            decodingTables[slot] = null;
            CustomPacketPayload decoded = AnimationWireS2CPacket.decode(transaction, g -> table);
            validateIdentity(header, decoded);
            if (decoded instanceof PlayerSnapshotS2CPacket snapshot)
                ClientCombatScene.decodedSnapshot(header, snapshot);
            connectionId = header.connectionId();
            completedTable = Math.max(completedTable, header.tableGeneration());
            retire();
            return new ClientboundCustomPayloadPacket(decoded);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            assembler.clear();
            Arrays.fill(decodingTables, null);
            // A local-only packet preserves task order with surrounding native/probe packets.
            return new Defect(header.observerStreamEpoch(), invalid.getMessage());
        }
    }

    private static void validateIdentity(AnimationWire.Header header, CustomPacketPayload decoded) {
        if (decoded instanceof PlayerSnapshotS2CPacket snapshot) {
            var batch = snapshot.animation();
            if (batch.connectionId() != header.connectionId()
                    || batch.sceneGeneration() != header.sceneGeneration())
                throw new IllegalArgumentException("Snapshot identity differs from its envelope");
            for (var record : batch.records())
                if (record.identity().observerStreamEpoch() != header.observerStreamEpoch())
                    throw new IllegalArgumentException("Snapshot stream differs from its envelope");
            for (var seed : batch.seeds()) check(header, seed.identity());
        } else if (decoded instanceof AnimationEventS2CPacket events)
            for (var event : events.events()) check(header, event.identity());
    }

    private static void check(AnimationWire.Header header, AnimationState.Identity identity) {
        if (identity.connectionId() != header.connectionId()
                || identity.observerStreamEpoch() != header.observerStreamEpoch()
                || identity.sceneGeneration() != header.sceneGeneration())
            throw new IllegalArgumentException("Animation identity differs from its envelope");
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        clear();
        context.fireChannelInactive();
    }

    /** Never serialized: only the receive stage can emit this ordered recovery notification. */
    private record Defect(long stream, String detail)
            implements Packet<ClientCommonPacketListener> {
        @Override
        public PacketType<? extends Packet<ClientCommonPacketListener>> type() {
            return CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD;
        }

        @Override
        public void handle(ClientCommonPacketListener listener) {
            PacketUtils.ensureRunningOnSameThread(this, listener, Minecraft.getInstance());
            ClientCombatScene.wireDefect(stream, detail);
        }
    }
}
