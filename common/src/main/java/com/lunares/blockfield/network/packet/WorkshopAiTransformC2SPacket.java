package com.lunares.blockfield.network.packet;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Reposition/stamp request for a preview-stage job: translate, rotate or mirror without a model
 * call. Offsets are added to the job's original minimum corner; rotation mirrors on X about that
 * corner first, then turns clockwise viewed from above. A pure reposition spends no quota; a stamp
 * spends one hourly request quota and never touches the AI budget.
 */
public record WorkshopAiTransformC2SPacket(
        UUID jobId, int dx, int dy, int dz, int rotY, boolean mirror, boolean stamp)
        implements CustomPacketPayload {
    public static final int MAX_OFFSET = 128;
    public static final Type<WorkshopAiTransformC2SPacket> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath("blockfield", "workshop_ai_transform"));
    public static final StreamCodec<FriendlyByteBuf, WorkshopAiTransformC2SPacket> CODEC =
            CustomPacketPayload.codec(
                    WorkshopAiTransformC2SPacket::write, WorkshopAiTransformC2SPacket::new);

    public WorkshopAiTransformC2SPacket {
        if (jobId == null
                || dx < -MAX_OFFSET
                || dx > MAX_OFFSET
                || dy < -MAX_OFFSET
                || dy > MAX_OFFSET
                || dz < -MAX_OFFSET
                || dz > MAX_OFFSET
                || rotY < 0
                || rotY > 3) throw new IllegalArgumentException("Invalid workshop AI transform");
    }

    private WorkshopAiTransformC2SPacket(FriendlyByteBuf buf) {
        this(
                buf.readUUID(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(jobId);
        buf.writeVarInt(dx);
        buf.writeVarInt(dy);
        buf.writeVarInt(dz);
        buf.writeVarInt(rotY);
        buf.writeBoolean(mirror);
        buf.writeBoolean(stamp);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
