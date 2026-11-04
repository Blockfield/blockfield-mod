package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Empty capability marker: a client that parses v3 plans registers a receiver for this channel (and
 * ignores the payload, which is never sent). The server gates v3 generation on {@code
 * ServerPlayNetworking.canSend(player, TYPE)} so old clients keep v1/v2 plans. The server also
 * registers an empty C2S receiver: clients check canSend before sending the new recall action,
 * which older library codecs would reject. No marker payload needs to be sent.
 */
public record WorkshopAiV3S2CPacket() implements CustomPacketPayload {
    public static final Type<WorkshopAiV3S2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "workshop_ai_v3"));
    public static final StreamCodec<FriendlyByteBuf, WorkshopAiV3S2CPacket> CODEC =
            CustomPacketPayload.codec(WorkshopAiV3S2CPacket::write, WorkshopAiV3S2CPacket::new);

    private WorkshopAiV3S2CPacket(FriendlyByteBuf buf) {
        this();
    }

    private void write(FriendlyByteBuf buf) {}

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
