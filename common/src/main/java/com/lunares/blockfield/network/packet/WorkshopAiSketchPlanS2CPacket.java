package com.lunares.blockfield.network.packet;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * One sketch plan for the client-side hand ghost. Sent after an explicit {@code take}: a library
 * sketch arrives with its id, rotY 0 and no mirror, while a picked-up live preview arrives with the
 * job id, the source sides and the job's current base transform. The preview renderer ignores this
 * channel, so old clients and the live preview are unaffected.
 */
public record WorkshopAiSketchPlanS2CPacket(
        UUID sketchId, String name, int sx, int sy, int sz, String plan, int rotY, boolean mirror)
        implements CustomPacketPayload {
    public static final Type<WorkshopAiSketchPlanS2CPacket> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath("blockfield", "workshop_ai_sketch_plan"));
    public static final StreamCodec<FriendlyByteBuf, WorkshopAiSketchPlanS2CPacket> CODEC =
            CustomPacketPayload.codec(
                    WorkshopAiSketchPlanS2CPacket::write, WorkshopAiSketchPlanS2CPacket::decode);

    private static final WorkshopAiSketchPlanS2CPacket INVALID =
            new WorkshopAiSketchPlanS2CPacket(new UUID(0, 0), "invalid", 1, 1, 1, "", 0, false);

    /** False only for a wire payload that failed to decode; receivers ignore those. */
    public boolean valid() {
        return this != INVALID;
    }

    /** Malformed payloads degrade to the invalid sentinel instead of throwing. */
    static WorkshopAiSketchPlanS2CPacket decode(FriendlyByteBuf buf) {
        return PacketCompat.decode(buf, WorkshopAiSketchPlanS2CPacket::new, INVALID);
    }

    public WorkshopAiSketchPlanS2CPacket {
        if (sketchId == null
                || name == null
                || name.isEmpty()
                || name.length() > WorkshopAiLibraryS2CPacket.MAX_NAME
                || sx < 1
                || sx > WorkshopAiPreviewS2CPacket.MAX_SIDE
                || sy < 1
                || sy > WorkshopAiPreviewS2CPacket.MAX_SIDE
                || sz < 1
                || sz > WorkshopAiPreviewS2CPacket.MAX_SIDE
                || plan == null
                || plan.length() > com.lunares.blockfield.workshop.WorkshopGeometry.MAX_MERGED_JSON
                || rotY < 0
                || rotY > 3) throw new IllegalArgumentException("Invalid workshop AI sketch plan");
    }

    /** Library sketches carry no transform; a picked-up preview fills both fields. */
    public WorkshopAiSketchPlanS2CPacket(
            UUID sketchId, String name, int sx, int sy, int sz, String plan) {
        this(sketchId, name, sx, sy, sz, plan, 0, false);
    }

    private WorkshopAiSketchPlanS2CPacket(FriendlyByteBuf buf) {
        this(
                buf.readUUID(),
                buf.readUtf(WorkshopAiLibraryS2CPacket.MAX_NAME),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readUtf(WorkshopAiPreviewS2CPacket.MAX_PLAN),
                buf.readVarInt(),
                buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(sketchId);
        buf.writeUtf(name, WorkshopAiLibraryS2CPacket.MAX_NAME);
        buf.writeVarInt(sx);
        buf.writeVarInt(sy);
        buf.writeVarInt(sz);
        buf.writeUtf(plan, WorkshopAiPreviewS2CPacket.MAX_PLAN);
        buf.writeVarInt(rotY);
        buf.writeBoolean(mirror);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
