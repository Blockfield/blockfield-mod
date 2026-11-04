package com.lunares.blockfield.network.packet;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Each fragment fits the C2S payload limit; the server also bounds the assembled recording. */
public record WorkshopAiAudioC2SPacket(UUID requestId, int index, int total, byte[] data)
        implements CustomPacketPayload {
    public static final int MAX_CHUNK_BYTES = 8192;
    public static final int MAX_SECONDS = 30;
    public static final int MAX_AUDIO_BYTES = MAX_SECONDS * 16_000 * 2 + 44;
    public static final int MAX_CHUNKS = (MAX_AUDIO_BYTES + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;
    public static final Type<WorkshopAiAudioC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "workshop_ai_audio"));
    public static final StreamCodec<FriendlyByteBuf, WorkshopAiAudioC2SPacket> CODEC =
            CustomPacketPayload.codec(
                    WorkshopAiAudioC2SPacket::write, WorkshopAiAudioC2SPacket::new);

    public WorkshopAiAudioC2SPacket {
        if (requestId == null
                || total < 1
                || total > MAX_CHUNKS
                || index < 0
                || index >= total
                || data == null
                || data.length < 1
                || data.length > MAX_CHUNK_BYTES)
            throw new IllegalArgumentException("Invalid workshop audio fragment");
        data = data.clone();
    }

    private WorkshopAiAudioC2SPacket(FriendlyByteBuf buf) {
        this(
                buf.readUUID(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readByteArray(MAX_CHUNK_BYTES));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(requestId);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeByteArray(data);
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
