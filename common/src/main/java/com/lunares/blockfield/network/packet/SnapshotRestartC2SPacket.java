package com.lunares.blockfield.network.packet;

import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationTypes;
import com.lunares.blockfield.common.combat.SnapshotWireMath;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Rate-limited recovery; the server owns the replacement stream identity. */
public record SnapshotRestartC2SPacket(long observerStreamEpoch, Reason reason)
        implements CustomPacketPayload {
    /** Explicit stable wire IDs (ADR4 §3.4). */
    public enum Reason implements AnimationTypes.Tag {
        STREAM_ORDER(0),
        SETTINGS(1),
        PAIRING_FULL(2),
        DELTA_BASELINE(3),
        CAPACITY(4),
        ARITHMETIC(5);
        private final int id;

        Reason(int id) {
            this.id = id;
        }

        @Override
        public int id() {
            return id;
        }
    }

    public static final int MIN_RESTART_INTERVAL_MS = 10_000;

    public static final Type<SnapshotRestartC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "snapshot_restart"));
    public static final StreamCodec<FriendlyByteBuf, SnapshotRestartC2SPacket> CODEC =
            StreamCodec.of(SnapshotRestartC2SPacket::write, SnapshotRestartC2SPacket::read);

    public SnapshotRestartC2SPacket {
        if (observerStreamEpoch < 0 || reason == null)
            throw new IllegalArgumentException("Invalid snapshot restart");
    }

    private static void write(FriendlyByteBuf buf, SnapshotRestartC2SPacket packet) {
        SnapshotWireMath.writeUvarLong(buf, packet.observerStreamEpoch());
        buf.writeByte(packet.reason().id());
    }

    private static SnapshotRestartC2SPacket read(FriendlyByteBuf buf) {
        long epoch = SnapshotWireMath.readUvarLong(buf);
        if (buf.readableBytes() != 1)
            throw new IllegalArgumentException("Snapshot restart needs exactly one reason byte");
        return new SnapshotRestartC2SPacket(
                epoch, AnimationSchema.tag(Reason.class, buf.readUnsignedByte()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
