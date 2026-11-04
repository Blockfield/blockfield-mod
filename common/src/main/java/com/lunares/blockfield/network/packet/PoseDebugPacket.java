package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Pose overlay diagnostics (server started with {@code -Dblockfield.poseDebug}). JSON because this
 * is a debug view, never a contract: nothing in play reads it and no server sends it unsubscribed.
 */
public record PoseDebugPacket(String json) implements CustomPacketPayload {
    private static final int MAX_CHARS = 1 << 18;

    public static final Type<PoseDebugPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "pose_debug"));
    public static final StreamCodec<FriendlyByteBuf, PoseDebugPacket> CODEC =
            CustomPacketPayload.codec(PoseDebugPacket::write, PoseDebugPacket::new);

    private PoseDebugPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(MAX_CHARS));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(json, MAX_CHARS);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
