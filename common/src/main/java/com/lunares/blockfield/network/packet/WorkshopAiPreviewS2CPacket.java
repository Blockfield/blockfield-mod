package com.lunares.blockfield.network.packet;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One bounded blueprint, then compact progress updates; no packet stream per block. */
public record WorkshopAiPreviewS2CPacket(
        UUID ownerId,
        UUID jobId,
        BlockPos origin,
        String plan,
        int sx,
        int sy,
        int sz,
        int rotY,
        boolean mirror)
        implements CustomPacketPayload {
    public static final int MAX_PLAN = 65536;
    public static final int MAX_SIDE = 128;
    public static final Type<WorkshopAiPreviewS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "workshop_ai_preview"));
    public static final StreamCodec<FriendlyByteBuf, WorkshopAiPreviewS2CPacket> CODEC =
            CustomPacketPayload.codec(
                    WorkshopAiPreviewS2CPacket::write, WorkshopAiPreviewS2CPacket::new);

    public WorkshopAiPreviewS2CPacket {
        if (ownerId == null
                || jobId == null
                || origin == null
                || plan == null
                || plan.length() > com.lunares.blockfield.workshop.WorkshopGeometry.MAX_MERGED_JSON
                || sx < 1
                || sx > MAX_SIDE
                || sy < 1
                || sy > MAX_SIDE
                || sz < 1
                || sz > MAX_SIDE
                || rotY < 0
                || rotY > 3) throw new IllegalArgumentException("Invalid workshop AI preview");
        origin = origin.immutable();
    }

    public WorkshopAiPreviewS2CPacket(UUID ownerId, UUID jobId, BlockPos origin, String plan) {
        this(ownerId, jobId, origin, plan, MAX_SIDE, MAX_SIDE, MAX_SIDE, 0, false);
    }

    public WorkshopAiPreviewS2CPacket(
            UUID ownerId, UUID jobId, BlockPos origin, String plan, int sx, int sy, int sz) {
        this(ownerId, jobId, origin, plan, sx, sy, sz, 0, false);
    }

    private WorkshopAiPreviewS2CPacket(FriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readUUID(), buf.readBlockPos(), buf.readUtf(MAX_PLAN));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(ownerId);
        buf.writeUUID(jobId);
        buf.writeBlockPos(origin);
        buf.writeUtf(plan, MAX_PLAN);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
