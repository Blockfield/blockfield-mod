package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One atomic gun-data delta, kept below the custom-payload byte limit. */
public record GunBalanceS2CPacket(String json) implements CustomPacketPayload {
    public static final int MAX_LENGTH = 240000;
    public static final Type<GunBalanceS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "gun_balance"));
    public static final StreamCodec<FriendlyByteBuf, GunBalanceS2CPacket> CODEC =
            CustomPacketPayload.codec(GunBalanceS2CPacket::write, GunBalanceS2CPacket::new);

    public GunBalanceS2CPacket {
        if (json.length() > MAX_LENGTH)
            throw new IllegalArgumentException("Gun balance delta too large");
    }

    private GunBalanceS2CPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(MAX_LENGTH));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(json, MAX_LENGTH);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
