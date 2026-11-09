package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-accepted knife swing: ParCool stamina is client-authoritative, so the client spends it
 * itself.
 */
public class ConsumeStaminaS2CPacket implements CustomPacketPayload {
    public static final Type<ConsumeStaminaS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "consume_stamina"));
    public static final StreamCodec<FriendlyByteBuf, ConsumeStaminaS2CPacket> CODEC =
            CustomPacketPayload.codec(
                    ConsumeStaminaS2CPacket::toBytes, ConsumeStaminaS2CPacket::decode);

    private static final ConsumeStaminaS2CPacket INVALID = new ConsumeStaminaS2CPacket(0);

    public final int amount;

    public ConsumeStaminaS2CPacket(int amount) {
        this.amount = amount;
    }

    public ConsumeStaminaS2CPacket(FriendlyByteBuf buf) {
        this.amount = buf.readVarInt();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeVarInt(this.amount);
    }

    /** False only for a wire payload that failed to decode; receivers ignore those. */
    public boolean valid() {
        return this != INVALID;
    }

    /** Malformed payloads degrade to the invalid sentinel instead of throwing. */
    static ConsumeStaminaS2CPacket decode(FriendlyByteBuf buf) {
        return PacketCompat.decode(buf, ConsumeStaminaS2CPacket::new, INVALID);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
