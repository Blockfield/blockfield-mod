package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded tiles keep detailed maps below the custom-payload limit. */
public record MapTileS2CPacket(
        ResourceLocation dimension,
        String mapId,
        double minX,
        double maxX,
        double minZ,
        double maxZ,
        int width,
        int height,
        long revision,
        int index,
        byte[] rgb)
        implements CustomPacketPayload {
    public static final int SIZE = 128;
    public static final int MAX_SIDE = 2048;
    public static final Type<MapTileS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "map_tile"));
    public static final StreamCodec<FriendlyByteBuf, MapTileS2CPacket> CODEC =
            CustomPacketPayload.codec(MapTileS2CPacket::write, MapTileS2CPacket::decode);

    private static final MapTileS2CPacket INVALID =
            new MapTileS2CPacket(
                    ResourceLocation.fromNamespaceAndPath("blockfield", "invalid"),
                    "invalid",
                    0,
                    1,
                    0,
                    1,
                    1,
                    1,
                    0,
                    0,
                    new byte[3]);

    public MapTileS2CPacket {
        if (width < 1
                || height < 1
                || width > MAX_SIDE
                || height > MAX_SIDE
                || mapId.length() > 128
                || !Double.isFinite(minX)
                || !Double.isFinite(maxX)
                || !Double.isFinite(minZ)
                || !Double.isFinite(maxZ)
                || minX >= maxX
                || minZ >= maxZ
                || index < 0
                || index >= ((width + SIZE - 1) / SIZE) * ((height + SIZE - 1) / SIZE))
            throw new IllegalArgumentException("Invalid map tile bounds");
        int columns = (width + SIZE - 1) / SIZE;
        int w = Math.min(SIZE, width - index % columns * SIZE);
        int h = Math.min(SIZE, height - index / columns * SIZE);
        if (rgb.length != w * h * 3) throw new IllegalArgumentException("Invalid map tile pixels");
    }

    public int x() {
        return index % ((width + SIZE - 1) / SIZE) * SIZE;
    }

    public int z() {
        return index / ((width + SIZE - 1) / SIZE) * SIZE;
    }

    public int tileWidth() {
        return Math.min(SIZE, width - x());
    }

    public int tileHeight() {
        return Math.min(SIZE, height - z());
    }

    public int count() {
        return ((width + SIZE - 1) / SIZE) * ((height + SIZE - 1) / SIZE);
    }

    public boolean sameImage(MapTileS2CPacket other) {
        return revision == other.revision && sameMap(other);
    }

    /** Same dimension, map and geometry, at any revision. */
    public boolean sameMap(MapTileS2CPacket other) {
        return dimension.equals(other.dimension)
                && mapId.equals(other.mapId)
                && width == other.width
                && height == other.height
                && minX == other.minX
                && maxX == other.maxX
                && minZ == other.minZ
                && maxZ == other.maxZ;
    }

    private MapTileS2CPacket(FriendlyByteBuf buf) {
        this(
                buf.readResourceLocation(),
                buf.readUtf(128),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readLong(),
                buf.readVarInt(),
                buf.readByteArray(SIZE * SIZE * 3));
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
        buf.writeLong(revision);
        buf.writeVarInt(index);
        buf.writeByteArray(rgb);
    }

    /** False only for a wire payload that failed to decode; receivers ignore those. */
    public boolean valid() {
        return this != INVALID;
    }

    /** Malformed payloads degrade to the invalid sentinel instead of throwing. */
    static MapTileS2CPacket decode(FriendlyByteBuf buf) {
        return PacketCompat.decode(buf, MapTileS2CPacket::new, INVALID);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
