package com.lunares.blockfield.network.packet;

import com.lunares.blockfield.common.combat.SnapshotWireMath;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client snapshot batch (phase 3): exactly one {@code blockfield:player_snapshot} packet
 * per observer tick, carrying the complete ADR4 observer batch (connection identity, header ticks
 * and settings, then the full {@code Batch} of records, definitions and seeds).
 */
public record PlayerSnapshotS2CPacket(
        long observerStreamEpoch,
        long previousTickPlusOne,
        long serverTick,
        int interpMin,
        int interpMax,
        int dilationPercent,
        int holdToSnapMs,
        com.lunares.blockfield.common.combat.AnimationRecipe.Batch animation)
        implements CustomPacketPayload {
    public static final Type<PlayerSnapshotS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "player_snapshot"));

    /** Failure bound, not truncation: a larger complete set disables the stream instead. */
    public static final int MAX_RECORDS =
            com.lunares.blockfield.common.combat.AnimationSchema.MAX_VICTIMS;

    /** Failure bound on the whole batch payload. */
    public static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    public static final int KIND_PLAYER = 0;
    public static final int KIND_SIMPLE_VEHICLE = 1;
    public static final int KIND_SBW_VEHICLE = 2;

    /** ADR4 full absolute placement and complete state. */
    public static final StreamCodec<FriendlyByteBuf, PlayerSnapshotS2CPacket> ANIMATION_CODEC =
            StreamCodec.of(
                    PlayerSnapshotS2CPacket::writeAnimation,
                    PlayerSnapshotS2CPacket::readAnimation);

    public static PlayerSnapshotS2CPacket animation(
            long stream,
            long previous,
            long tick,
            int min,
            int max,
            int dilation,
            int hold,
            com.lunares.blockfield.common.combat.AnimationRecipe.Batch batch) {
        return new PlayerSnapshotS2CPacket(stream, previous, tick, min, max, dilation, hold, batch);
    }

    public PlayerSnapshotS2CPacket {
        if (observerStreamEpoch < 0 || previousTickPlusOne < 0 || serverTick < 0)
            throw new IllegalArgumentException("Snapshot header ticks must be non-negative");
        if ((interpMin & ~0xFF) != 0 || (interpMax & ~0xFF) != 0 || (dilationPercent & ~0xFF) != 0)
            throw new IllegalArgumentException("Snapshot header settings must be u8");
        if ((holdToSnapMs & ~0xFFFF) != 0)
            throw new IllegalArgumentException("Snapshot holdToSnapMs must be u16");
        java.util.Objects.requireNonNull(animation, "Complete ADR4 snapshot required");
        for (var record : animation.records()) {
            if (record.identity().observerStreamEpoch() != observerStreamEpoch
                    || record.checkpoint() != null
                            && record.checkpoint().serverTick() != serverTick)
                throw new IllegalArgumentException("Snapshot tick/stream mismatch");
        }
    }

    private static void require(FriendlyByteBuf buf, int bytes) {
        if (buf.readableBytes() < bytes)
            throw new IllegalArgumentException("Truncated snapshot batch");
    }

    private static void writeAnimation(FriendlyByteBuf buf, PlayerSnapshotS2CPacket packet) {
        byte[] bytes =
                com.lunares.blockfield.common.combat.AnimationSchema.encodeBounded(
                        b -> {
                            b.writeByte(
                                    com.lunares.blockfield.common.combat.AnimationSchema.VERSION);
                            SnapshotWireMath.writeUvarLong(b, packet.observerStreamEpoch());
                            SnapshotWireMath.writeUvarLong(b, packet.previousTickPlusOne());
                            SnapshotWireMath.writeUvarLong(b, packet.serverTick());
                            b.writeByte(packet.interpMin());
                            b.writeByte(packet.interpMax());
                            b.writeByte(packet.dilationPercent());
                            b.writeShort(packet.holdToSnapMs());
                            com.lunares.blockfield.common.combat.AnimationSchema.write(
                                    b, packet.animation());
                        });
        buf.writeBytes(bytes);
    }

    private static PlayerSnapshotS2CPacket readAnimation(FriendlyByteBuf b) {
        com.lunares.blockfield.common.combat.AnimationSchema.count(
                b.readableBytes(), MAX_PAYLOAD_BYTES, "snapshot payload");
        require(b, 1);
        com.lunares.blockfield.common.combat.AnimationSchema.checkVersion(
                b.readUnsignedByte(), "snapshot");
        long stream = SnapshotWireMath.readUvarLong(b),
                previous = SnapshotWireMath.readUvarLong(b),
                tick = SnapshotWireMath.readUvarLong(b);
        require(b, 5);
        int min = b.readUnsignedByte(),
                max = b.readUnsignedByte(),
                dilation = b.readUnsignedByte(),
                hold = b.readUnsignedShort();
        var batch =
                com.lunares.blockfield.common.combat.AnimationSchema.readComplete(
                        b, com.lunares.blockfield.common.combat.AnimationRecipe.Batch.class);
        return animation(stream, previous, tick, min, max, dilation, hold, batch);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
