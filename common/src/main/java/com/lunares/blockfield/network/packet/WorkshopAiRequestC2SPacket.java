package com.lunares.blockfield.network.packet;

import java.util.Set;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** {@code fast} picks the speed mode for {@code generate}; every other action ignores it. */
public record WorkshopAiRequestC2SPacket(String action, UUID jobId, String prompt, boolean fast)
        implements CustomPacketPayload {
    public static final UUID ZERO_ID = new UUID(0, 0);
    public static final int MAX_PROMPT = 4096;
    private static final Set<String> ACTIONS =
            Set.of("status", "generate", "apply", "cancel", "undo");
    public static final Type<WorkshopAiRequestC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "workshop_ai_request"));
    public static final StreamCodec<FriendlyByteBuf, WorkshopAiRequestC2SPacket> CODEC =
            CustomPacketPayload.codec(
                    WorkshopAiRequestC2SPacket::write, WorkshopAiRequestC2SPacket::new);

    public WorkshopAiRequestC2SPacket {
        if (!ACTIONS.contains(action)
                || jobId == null
                || prompt == null
                || prompt.length() > MAX_PROMPT)
            throw new IllegalArgumentException("Invalid workshop AI request");
    }

    public WorkshopAiRequestC2SPacket(String action, UUID jobId, String prompt) {
        this(action, jobId, prompt, false);
    }

    private WorkshopAiRequestC2SPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(16), buf.readUUID(), buf.readUtf(MAX_PROMPT), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(action, 16);
        buf.writeUUID(jobId);
        buf.writeUtf(prompt, MAX_PROMPT);
        buf.writeBoolean(fast);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
