package com.lunares.blockfield.network.packet;

import com.lunares.blockfield.workshop.WorkshopAiProgress;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Additional capability-gated state; the released workshop_ai_state codec remains unchanged. */
public record WorkshopAiProgressS2CPacket(
        UUID ownerId, UUID jobId, WorkshopAiProgress progress, long serverNow)
        implements CustomPacketPayload {
    public static final Type<WorkshopAiProgressS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "workshop_ai_progress"));
    public static final StreamCodec<FriendlyByteBuf, WorkshopAiProgressS2CPacket> CODEC =
            CustomPacketPayload.codec(
                    WorkshopAiProgressS2CPacket::write, WorkshopAiProgressS2CPacket::new);

    public WorkshopAiProgressS2CPacket {
        if (ownerId == null
                || jobId == null
                || progress == null
                || serverNow < 0
                || progress.phaseStartedAt() > serverNow)
            throw new IllegalArgumentException("Invalid progress packet");
    }

    private WorkshopAiProgressS2CPacket(FriendlyByteBuf buf) {
        this(
                buf.readUUID(),
                buf.readUUID(),
                new WorkshopAiProgress(
                        buf.readEnum(WorkshopAiProgress.Phase.class),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readLong(),
                        buf.readLong(),
                        buf.readLong(),
                        buf.readLong()),
                buf.readLong());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(ownerId);
        buf.writeUUID(jobId);
        buf.writeEnum(progress.phase());
        buf.writeVarInt(progress.partsDone());
        buf.writeVarInt(progress.partsTotal());
        buf.writeLong(progress.phaseStartedAt());
        buf.writeLong(progress.phaseDeadlineAt());
        buf.writeLong(progress.jobStartedAt());
        buf.writeLong(progress.expectedDoneAt());
        buf.writeLong(serverNow);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
