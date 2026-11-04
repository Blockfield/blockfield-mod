package com.lunares.blockfield.network.packet;

import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.SnapshotWireMath;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Echo of {@link AnimationProbeS2CPacket} on the ordered gameplay channel. The server measures the
 * round trip on its own clock; client times never travel (they are untrusted and unneeded).
 */
public record AnimationProbeC2SPacket(long id) implements CustomPacketPayload {
    public static final Type<AnimationProbeC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "animation_probe_echo"));
    public static final StreamCodec<FriendlyByteBuf, AnimationProbeC2SPacket> CODEC =
            StreamCodec.of(AnimationProbeC2SPacket::write, AnimationProbeC2SPacket::read);

    public AnimationProbeC2SPacket {
        AnimationSchema.nonnegative(id, "probe id");
    }

    private static void write(FriendlyByteBuf b, AnimationProbeC2SPacket packet) {
        SnapshotWireMath.writeUvarLong(b, packet.id());
    }

    private static AnimationProbeC2SPacket read(FriendlyByteBuf b) {
        long id = SnapshotWireMath.readUvarLong(b);
        if (b.isReadable()) throw new IllegalArgumentException("Trailing probe echo bytes");
        return new AnimationProbeC2SPacket(id);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
