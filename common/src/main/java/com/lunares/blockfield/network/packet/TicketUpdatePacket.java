package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public class TicketUpdatePacket implements CustomPacketPayload {
    public static final Type<TicketUpdatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "ticket_update"));
    public static final StreamCodec<FriendlyByteBuf, TicketUpdatePacket> CODEC =
            CustomPacketPayload.codec(TicketUpdatePacket::toBytes, TicketUpdatePacket::new);

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

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
