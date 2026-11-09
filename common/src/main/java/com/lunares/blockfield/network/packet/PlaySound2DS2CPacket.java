package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * UI-звук без 3D-затухания: телепортируемый игрок собственного 3D-звука в точке прибытия не слышит.
 */
public class PlaySound2DS2CPacket implements CustomPacketPayload {
    public static final Type<PlaySound2DS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "play_sound_2d"));
    public static final StreamCodec<FriendlyByteBuf, PlaySound2DS2CPacket> CODEC =
            CustomPacketPayload.codec(PlaySound2DS2CPacket::toBytes, PlaySound2DS2CPacket::decode);

    private static final PlaySound2DS2CPacket INVALID = new PlaySound2DS2CPacket("", 0, 0);

    public final String soundId;
    public final float volume;
    public final float pitch;

    public PlaySound2DS2CPacket(String soundId, float volume, float pitch) {
        this.soundId = soundId;
        this.volume = volume;
        this.pitch = pitch;
    }

    public PlaySound2DS2CPacket(FriendlyByteBuf buf) {
        this.soundId = buf.readUtf();
        this.volume = buf.readFloat();
        this.pitch = buf.readFloat();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUtf(this.soundId);
        buf.writeFloat(this.volume);
        buf.writeFloat(this.pitch);
    }

    /** False only for a wire payload that failed to decode; receivers ignore those. */
    public boolean valid() {
        return this != INVALID;
    }

    /** Malformed payloads degrade to the invalid sentinel instead of throwing. */
    static PlaySound2DS2CPacket decode(FriendlyByteBuf buf) {
        return PacketCompat.decode(buf, PlaySound2DS2CPacket::new, INVALID);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
