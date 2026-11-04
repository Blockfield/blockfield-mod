package com.lunares.blockfield.network.packet;

import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record WorkshopAiStateS2CPacket(
        UUID ownerId,
        UUID jobId,
        String stage,
        String message,
        String prompt,
        BlockPos min,
        BlockPos max,
        int totalBlocks,
        int completedBlocks,
        boolean canUndo,
        boolean canVoice)
        implements CustomPacketPayload {
    public static final int MAX_TEXT = 4096;
    private static final Set<String> STAGES =
            Set.of(
                    "disabled",
                    "idle",
                    "transcribing",
                    "planning",
                    "awaiting",
                    "preview",
                    "held",
                    "applying",
                    "complete",
                    "cancelled",
                    "error",
                    "undoing");
    public static final Type<WorkshopAiStateS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "workshop_ai_state"));
    public static final StreamCodec<FriendlyByteBuf, WorkshopAiStateS2CPacket> CODEC =
            CustomPacketPayload.codec(
                    WorkshopAiStateS2CPacket::write, WorkshopAiStateS2CPacket::new);

    public WorkshopAiStateS2CPacket {
        if (ownerId == null
                || jobId == null
                || !STAGES.contains(stage)
                || message == null
                || prompt == null
                || message.length() > MAX_TEXT
                || prompt.length() > MAX_TEXT
                || min == null
                || max == null
                || totalBlocks < 0
                || totalBlocks > 32768
                || completedBlocks < 0
                || completedBlocks > totalBlocks)
            throw new IllegalArgumentException("Invalid workshop AI state");
        min = min.immutable();
        max = max.immutable();
    }

    private WorkshopAiStateS2CPacket(FriendlyByteBuf buf) {
        this(
                buf.readUUID(),
                buf.readUUID(),
                buf.readUtf(16),
                buf.readUtf(MAX_TEXT),
                buf.readUtf(MAX_TEXT),
                buf.readBlockPos(),
                buf.readBlockPos(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(ownerId);
        buf.writeUUID(jobId);
        buf.writeUtf(stage, 16);
        buf.writeUtf(message, MAX_TEXT);
        buf.writeUtf(prompt, MAX_TEXT);
        buf.writeBlockPos(min);
        buf.writeBlockPos(max);
        buf.writeVarInt(totalBlocks);
        buf.writeVarInt(completedBlocks);
        buf.writeBoolean(canUndo);
        buf.writeBoolean(canVoice);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
