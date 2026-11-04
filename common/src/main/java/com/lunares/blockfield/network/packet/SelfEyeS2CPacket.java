package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The receiving player's head offset in the body-yaw frame (unclamped head anchor minus the
 * server's vanilla eye, rotated by the frame's body yaw), sent every END tick after the body-pose
 * commit. A yaw-local offset, not a world position: the client rotates it by its own current body
 * yaw and adds it to its own predicted, frame-interpolated camera, so turning between the server
 * tick and the render never leaves the camera behind the head (a world-space offset did while prone
 * transitions carried ~0.3 m of it). An absolute anchor made the first-person camera judder while
 * walking. The channel id changed with the meaning, so a client that still reads a world offset
 * never receives it. A dedicated packet keeps the pinned snapshot wire untouched.
 */
public record SelfEyeS2CPacket(long serverTick, float dx, float dy, float dz)
        implements CustomPacketPayload {
    /** Far beyond any pose (a full body length); larger values are a broken sender. */
    public static final float MAX_OFFSET = 4;

    public static final Type<SelfEyeS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "self_eye_local"));
    public static final StreamCodec<FriendlyByteBuf, SelfEyeS2CPacket> CODEC =
            CustomPacketPayload.codec(SelfEyeS2CPacket::write, SelfEyeS2CPacket::new);

    public SelfEyeS2CPacket {
        if (serverTick < 0
                || !(Math.abs(dx) <= MAX_OFFSET)
                || !(Math.abs(dy) <= MAX_OFFSET)
                || !(Math.abs(dz) <= MAX_OFFSET))
            throw new IllegalArgumentException("Invalid self eye offset");
    }

    private SelfEyeS2CPacket(FriendlyByteBuf buf) {
        this(buf.readVarLong(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarLong(serverTick);
        buf.writeFloat(dx);
        buf.writeFloat(dy);
        buf.writeFloat(dz);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
