package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent right before LR's swing packet: the entity under the client's crosshair (`target` = its id)
 * or {@link #NONE}. The client draws other players several ticks behind the server, so a moving
 * target the player clearly hit is often off the server's ray; the server still checks reach and
 * line of sight (KnifeCombat).
 */
public record KnifeAimC2SPacket(int target) implements CustomPacketPayload {
    public static final int NONE = -1;
    public static final Type<KnifeAimC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "knife_aim"));
    public static final StreamCodec<FriendlyByteBuf, KnifeAimC2SPacket> CODEC =
            StreamCodec.of(
                    (buf, packet) -> buf.writeVarInt(packet.target),
                    buf -> new KnifeAimC2SPacket(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
