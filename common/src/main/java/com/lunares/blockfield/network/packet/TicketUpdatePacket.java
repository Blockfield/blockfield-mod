package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public class TicketUpdatePacket implements CustomPacketPayload {
    public static final Type<TicketUpdatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "ticket_update"));
    public static final StreamCodec<FriendlyByteBuf, TicketUpdatePacket> CODEC =
            CustomPacketPayload.codec(TicketUpdatePacket::toBytes, TicketUpdatePacket::decode);

    private static final TicketUpdatePacket INVALID = new TicketUpdatePacket(0, 0);

    public final int redTickets;
    public final int blueTickets;

    public TicketUpdatePacket(int red, int blue) {
        this.redTickets = red;
        this.blueTickets = blue;
    }

    public TicketUpdatePacket(FriendlyByteBuf buf) {
        this.redTickets = buf.readInt();
        this.blueTickets = buf.readInt();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(this.redTickets);
        buf.writeInt(this.blueTickets);
    }

    /** False only for a wire payload that failed to decode; receivers ignore those. */
    public boolean valid() {
        return this != INVALID;
    }

    /** Malformed payloads degrade to the invalid sentinel instead of throwing. */
    static TicketUpdatePacket decode(FriendlyByteBuf buf) {
        return PacketCompat.decode(buf, TicketUpdatePacket::new, INVALID);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
