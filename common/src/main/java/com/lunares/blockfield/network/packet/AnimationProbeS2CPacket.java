package com.lunares.blockfield.network.packet;

import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.SnapshotWireMath;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * ADR4 §3.2 timing probe, one per observer END tick before its capsules: a server nonce with its
 * monotonic send time, plus the server's latest recorded tick boundary. The observer echoes the id
 * from the main thread (the capsule application path) and keeps the consecutive boundaries to map
 * its application times to server tick-time; it never compares the two machines' clocks directly.
 * Layout: {@code id uvarlong, serverSendNs i64, boundaryTick uvarlong, boundaryNs i64}.
 */
public record AnimationProbeS2CPacket(
        long id, long serverSendNs, long boundaryTick, long boundaryNs)
        implements CustomPacketPayload {
    public static final Type<AnimationProbeS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "animation_probe"));
    public static final StreamCodec<FriendlyByteBuf, AnimationProbeS2CPacket> CODEC =
            StreamCodec.of(AnimationProbeS2CPacket::write, AnimationProbeS2CPacket::read);

    public AnimationProbeS2CPacket {
        AnimationSchema.nonnegative(id, "probe id");
        AnimationSchema.nonnegative(boundaryTick, "boundary tick");
    }

    private static void write(FriendlyByteBuf b, AnimationProbeS2CPacket packet) {
        SnapshotWireMath.writeUvarLong(b, packet.id());
        b.writeLong(packet.serverSendNs());
        SnapshotWireMath.writeUvarLong(b, packet.boundaryTick());
        b.writeLong(packet.boundaryNs());
    }

    private static AnimationProbeS2CPacket read(FriendlyByteBuf b) {
        long id = SnapshotWireMath.readUvarLong(b);
        AnimationSchema.require(b, 8);
        long send = b.readLong();
        long tick = SnapshotWireMath.readUvarLong(b);
        AnimationSchema.require(b, 8);
        long boundary = b.readLong();
        if (b.isReadable()) throw new IllegalArgumentException("Trailing probe bytes");
        return new AnimationProbeS2CPacket(id, send, tick, boundary);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
