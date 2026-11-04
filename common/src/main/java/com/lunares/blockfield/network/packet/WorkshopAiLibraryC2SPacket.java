package com.lunares.blockfield.network.packet;

import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sketch-library actions: no model call except {@code generate}. Old clients never send this
 * channel and keep the request/transform flow untouched.
 *
 * <p>{@code place} fixes a hand ghost: exactly one of {@code sketchId} (library sketch) or {@code
 * jobId} (live preview) is set, the other is {@link WorkshopAiRequestC2SPacket#ZERO_ID}. The origin
 * is absolute; the server derives preview offsets from the job minimum itself. {@code fast} picks
 * the speed mode for {@code generate} and is ignored by other actions.
 */
public record WorkshopAiLibraryC2SPacket(
        String action,
        UUID sketchId,
        UUID jobId,
        String prompt,
        int sx,
        int sy,
        int sz,
        BlockPos origin,
        int rotY,
        boolean mirror,
        boolean fast)
        implements CustomPacketPayload {
    public static final int MAX_PROMPT = WorkshopAiRequestC2SPacket.MAX_PROMPT;
    public static final int MAX_SIDE = WorkshopAiPreviewS2CPacket.MAX_SIDE;
    private static final Set<String> ACTIONS =
            Set.of("list", "generate", "take", "delete", "place", "recall", "extend");
    public static final Type<WorkshopAiLibraryC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "workshop_ai_library"));
    public static final StreamCodec<FriendlyByteBuf, WorkshopAiLibraryC2SPacket> CODEC =
            CustomPacketPayload.codec(
                    WorkshopAiLibraryC2SPacket::write, WorkshopAiLibraryC2SPacket::new);

    public WorkshopAiLibraryC2SPacket {
        if (!ACTIONS.contains(action)
                || sketchId == null
                || jobId == null
                || prompt == null
                || prompt.length() > MAX_PROMPT
                || sx < 0
                || sx > MAX_SIDE
                || sy < 0
                || sy > MAX_SIDE
                || sz < 0
                || sz > MAX_SIDE
                || origin == null
                || rotY < 0
                || rotY > 3) throw new IllegalArgumentException("Invalid workshop AI library call");
        if ((action.equals("place")
                        || action.equals("take")
                        || action.equals("recall")
                        || action.equals("extend"))
                && sketchId.equals(WorkshopAiRequestC2SPacket.ZERO_ID)
                        == jobId.equals(WorkshopAiRequestC2SPacket.ZERO_ID))
            throw new IllegalArgumentException("Place and take need exactly one sketch or job");
    }

    public WorkshopAiLibraryC2SPacket(
            String action,
            UUID sketchId,
            UUID jobId,
            String prompt,
            int sx,
            int sy,
            int sz,
            BlockPos origin,
            int rotY,
            boolean mirror) {
        this(action, sketchId, jobId, prompt, sx, sy, sz, origin, rotY, mirror, false);
    }

    private WorkshopAiLibraryC2SPacket(FriendlyByteBuf buf) {
        this(
                buf.readUtf(16),
                buf.readUUID(),
                buf.readUUID(),
                buf.readUtf(MAX_PROMPT),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBlockPos(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(action, 16);
        buf.writeUUID(sketchId);
        buf.writeUUID(jobId);
        buf.writeUtf(prompt, MAX_PROMPT);
        buf.writeVarInt(sx);
        buf.writeVarInt(sy);
        buf.writeVarInt(sz);
        buf.writeBlockPos(origin);
        buf.writeVarInt(rotY);
        buf.writeBoolean(mirror);
        buf.writeBoolean(fast);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
