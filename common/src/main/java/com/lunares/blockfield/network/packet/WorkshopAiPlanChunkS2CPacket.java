package com.lunares.blockfield.network.packet;

import com.lunares.blockfield.workshop.WorkshopGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** V3 transfer: bounded chunks retain the requested volume, which defines mirror planes. */
public record WorkshopAiPlanChunkS2CPacket(
        UUID transferId,
        UUID ownerId,
        UUID jobId,
        BlockPos origin,
        String name,
        int sx,
        int sy,
        int sz,
        int rotY,
        boolean mirror,
        boolean hand,
        int index,
        int count,
        String data)
        implements CustomPacketPayload {
    public static final int CHUNK = 16_000;
    public static final int MAX_CHUNKS = (WorkshopGeometry.MAX_MERGED_JSON + CHUNK - 1) / CHUNK;
    public static final Type<WorkshopAiPlanChunkS2CPacket> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath("blockfield", "workshop_ai_plan_chunk"));
    public static final StreamCodec<FriendlyByteBuf, WorkshopAiPlanChunkS2CPacket> CODEC =
            CustomPacketPayload.codec(
                    WorkshopAiPlanChunkS2CPacket::write, WorkshopAiPlanChunkS2CPacket::decode);

    private static final UUID NIL = new UUID(0, 0);
    private static final WorkshopAiPlanChunkS2CPacket INVALID =
            new WorkshopAiPlanChunkS2CPacket(
                    NIL, NIL, NIL, BlockPos.ZERO, "invalid", 1, 1, 1, 0, false, false, 0, 1, "x");

    /** False only for a wire payload that failed to decode; receivers ignore those. */
    public boolean valid() {
        return this != INVALID;
    }

    /** Malformed payloads degrade to the invalid sentinel instead of throwing. */
    static WorkshopAiPlanChunkS2CPacket decode(FriendlyByteBuf buf) {
        return PacketCompat.decode(buf, WorkshopAiPlanChunkS2CPacket::new, INVALID);
    }

    public WorkshopAiPlanChunkS2CPacket {
        if (transferId == null
                || ownerId == null
                || jobId == null
                || origin == null
                || name == null
                || name.length() > 64
                || sx < 1
                || sx > 128
                || sy < 1
                || sy > 128
                || sz < 1
                || sz > 128
                || rotY < 0
                || rotY > 3
                || count < 1
                || count > MAX_CHUNKS
                || index < 0
                || index >= count
                || data == null
                || data.isEmpty()
                || data.length() > CHUNK
                || index < count - 1 && data.length() != CHUNK)
            throw new IllegalArgumentException("Invalid plan chunk");
        origin = origin.immutable();
    }

    public static List<WorkshopAiPlanChunkS2CPacket> split(
            UUID owner,
            UUID job,
            BlockPos origin,
            String name,
            int sx,
            int sy,
            int sz,
            int rotY,
            boolean mirror,
            boolean hand,
            String plan) {
        if (plan == null || plan.isEmpty() || plan.length() > WorkshopGeometry.MAX_MERGED_JSON)
            throw new IllegalArgumentException("Plan size");
        int count = (plan.length() + CHUNK - 1) / CHUNK;
        UUID transfer = UUID.randomUUID();
        var chunks = new ArrayList<WorkshopAiPlanChunkS2CPacket>();
        for (int i = 0; i < count; i++)
            chunks.add(
                    new WorkshopAiPlanChunkS2CPacket(
                            transfer,
                            owner,
                            job,
                            origin,
                            name,
                            sx,
                            sy,
                            sz,
                            rotY,
                            mirror,
                            hand,
                            i,
                            count,
                            plan.substring(i * CHUNK, Math.min(plan.length(), (i + 1) * CHUNK))));
        return List.copyOf(chunks);
    }

    private WorkshopAiPlanChunkS2CPacket(FriendlyByteBuf buf) {
        this(
                buf.readUUID(),
                buf.readUUID(),
                buf.readUUID(),
                buf.readBlockPos(),
                buf.readUtf(64),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readUtf(CHUNK));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(transferId);
        buf.writeUUID(ownerId);
        buf.writeUUID(jobId);
        buf.writeBlockPos(origin);
        buf.writeUtf(name, 64);
        buf.writeVarInt(sx);
        buf.writeVarInt(sy);
        buf.writeVarInt(sz);
        buf.writeVarInt(rotY);
        buf.writeBoolean(mirror);
        buf.writeBoolean(hand);
        buf.writeVarInt(index);
        buf.writeVarInt(count);
        buf.writeUtf(data, CHUNK);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
