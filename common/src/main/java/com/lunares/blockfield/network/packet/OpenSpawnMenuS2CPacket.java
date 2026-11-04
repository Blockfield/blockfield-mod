package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public class OpenSpawnMenuS2CPacket implements CustomPacketPayload {
    public static final Type<OpenSpawnMenuS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "open_spawn_menu"));
    public static final StreamCodec<FriendlyByteBuf, OpenSpawnMenuS2CPacket> CODEC =
            CustomPacketPayload.codec(OpenSpawnMenuS2CPacket::toBytes, OpenSpawnMenuS2CPacket::new);

    public final String spawnsJson;
    public final String mapId;

    public OpenSpawnMenuS2CPacket(String spawnsJson, String mapId) {
        this.spawnsJson = spawnsJson;
        this.mapId = mapId;
    }

    public OpenSpawnMenuS2CPacket(FriendlyByteBuf buf) {
        this.spawnsJson = buf.readUtf(262144);
        this.mapId = buf.readUtf(256);
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUtf(this.spawnsJson, 262144);
        buf.writeUtf(this.mapId, 256);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
