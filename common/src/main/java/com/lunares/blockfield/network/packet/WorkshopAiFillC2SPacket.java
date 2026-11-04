package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * «Продлить» smart fill of the sender's current selection with an optional short hint. The channel
 * is its own capability: clients send it only when {@code ClientPlayNetworking.canSend(TYPE)}, so
 * older servers (no receiver) never see it and existing request codecs stay unchanged.
 */
public record WorkshopAiFillC2SPacket(String hint) implements CustomPacketPayload {
    public static final int MAX_HINT = WorkshopAiRequestC2SPacket.MAX_PROMPT;
    public static final Type<WorkshopAiFillC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "workshop_ai_fill"));
    public static final StreamCodec<FriendlyByteBuf, WorkshopAiFillC2SPacket> CODEC =
            CustomPacketPayload.codec(WorkshopAiFillC2SPacket::write, WorkshopAiFillC2SPacket::new);

    public WorkshopAiFillC2SPacket {
        if (hint == null || hint.length() > MAX_HINT)
            throw new IllegalArgumentException("Invalid workshop fill hint");
    }

    private WorkshopAiFillC2SPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(MAX_HINT));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(hint, MAX_HINT);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
