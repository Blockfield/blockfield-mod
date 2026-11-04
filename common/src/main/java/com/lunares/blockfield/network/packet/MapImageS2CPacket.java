package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Aspect-correct RGB image, bounded below Minecraft's 1 MiB custom-payload limit. */
public record MapImageS2CPacket(
        ResourceLocation dimension,
        String mapId,
        double minX,
        double maxX,
        double minZ,
        double maxZ,
        int width,
        int height,
        byte[] rgb)
        implements CustomPacketPayload {
    public static final int MAX_PIXELS = 262144;
    public static final Type<MapImageS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "map_image"));
    public static final StreamCodec<FriendlyByteBuf, MapImageS2CPacket> CODEC =
            CustomPacketPayload.codec(MapImageS2CPacket::write, MapImageS2CPacket::new);

    public MapImageS2CPacket {
        if (width < 1
                || height < 1
                || width > 1024
                || height > 1024
                || width * height > MAX_PIXELS
                || rgb.length != width * height * 3
                || mapId.length() > 128
                || !Double.isFinite(minX)
                || !Double.isFinite(maxX)
                || !Double.isFinite(minZ)
                || !Double.isFinite(maxZ)
                || minX >= maxX
                || minZ >= maxZ) throw new IllegalArgumentException("Invalid map image");
    }

    private MapImageS2CPacket(FriendlyByteBuf buf) {
        this(
                buf.readResourceLocation(),
                buf.readUtf(128),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readByteArray(MAX_PIXELS * 3));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeUtf(mapId, 128);
        buf.writeDouble(minX);
        buf.writeDouble(maxX);
        buf.writeDouble(minZ);
        buf.writeDouble(maxZ);
        buf.writeVarInt(width);
        buf.writeVarInt(height);
        buf.writeByteArray(rgb);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
