package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * One {@link MapTileS2CPacket#SIZE} tile as surfaces and baked light, so the client can color
 * blocks from its own resource pack. A delivery of {@code count} tiles either replaces the image
 * ({@code base == 0}) or patches the client's complete revision {@code base}.
 *
 * @param states global block-state id per palette entry, or -1 for water and the void
 * @param colors server RGB per palette entry, the fallback for unresolved states; {@link #FIXED}
 *     marks hand-tuned materials whose flat color the client keeps while texturing close zoom
 * @param pixels palette index per pixel, two bytes big-endian
 * @param light per-pixel light, {@link #UNLIT} meaning unchanged color
 */
public record MapTerrainS2CPacket(
        ResourceLocation dimension,
        String mapId,
        double minX,
        double maxX,
        double minZ,
        double maxZ,
        int width,
        int height,
        long revision,
        long base,
        int count,
        int index,
        int[] states,
        int[] colors,
        byte[] pixels,
        byte[] light)
        implements CustomPacketPayload {
    public static final int UNLIT = 200;
    public static final int FIXED = 1 << 24;
    public static final Type<MapTerrainS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "map_terrain"));
    public static final StreamCodec<FriendlyByteBuf, MapTerrainS2CPacket> CODEC =
            CustomPacketPayload.codec(MapTerrainS2CPacket::write, MapTerrainS2CPacket::decode);

    private static final MapTerrainS2CPacket INVALID =
            new MapTerrainS2CPacket(
                    ResourceLocation.fromNamespaceAndPath("blockfield", "invalid"),
                    "invalid",
                    0,
                    1,
                    0,
                    1,
                    1,
                    1,
                    1,
                    0,
                    1,
                    0,
                    new int[1],
                    new int[1],
                    new byte[2],
                    new byte[1]);

    public MapTerrainS2CPacket {
        int size = MapTileS2CPacket.SIZE;
        int columns = (width + size - 1) / size;
        int tiles = columns * ((height + size - 1) / size);
        if (width < 1
                || height < 1
                || width > MapTileS2CPacket.MAX_SIDE
                || height > MapTileS2CPacket.MAX_SIDE
                || mapId.length() > 128
                || !Double.isFinite(minX)
                || !Double.isFinite(maxX)
                || !Double.isFinite(minZ)
                || !Double.isFinite(maxZ)
                || minX >= maxX
                || minZ >= maxZ
                || revision <= 0
                || base < 0
                || base >= revision
                || count < 1
                || count > tiles
                || index < 0
                || index >= tiles
                || states.length != colors.length
                || states.length > size * size)
            throw new IllegalArgumentException("Invalid map terrain");
        int w = Math.min(size, width - index % columns * size);
        int h = Math.min(size, height - index / columns * size);
        if (pixels.length != w * h * 2 || light.length != w * h)
            throw new IllegalArgumentException("Invalid map terrain pixels");
        // Fields are unassigned inside a compact constructor: read the parameter.
        for (int p = 0; p < light.length; p++)
            if (((pixels[p * 2] & 255) << 8 | pixels[p * 2 + 1] & 255) >= states.length)
                throw new IllegalArgumentException("Invalid map terrain palette index");
    }

    public int entry(int pixel) {
        return (pixels[pixel * 2] & 255) << 8 | pixels[pixel * 2 + 1] & 255;
    }

    /** Shared by the server image and client recoloring, so both bake identical light. */
    public static int lit(int color, int light) {
        int result = 0;
        for (int shift = 0; shift <= 16; shift += 8)
            result |=
                    Math.min(255, (((color >> shift) & 255) * light + UNLIT / 2) / UNLIT) << shift;
        return result;
    }

    /** Compress material colors so lighting keeps headroom on both bright and dark roofs. */
    public static int tone(int color) {
        int toned = 0;
        for (int shift = 0; shift <= 16; shift += 8)
            toned |= Math.round(22 + ((color >> shift) & 255) * 0.79f) << shift;
        return toned;
    }

    public MapTileS2CPacket tile(byte[] rgb) {
        return new MapTileS2CPacket(
                dimension, mapId, minX, maxX, minZ, maxZ, width, height, revision, index, rgb);
    }

    private MapTerrainS2CPacket(FriendlyByteBuf buf) {
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
                buf.readLong(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarIntArray(MapTileS2CPacket.SIZE * MapTileS2CPacket.SIZE),
                readInts(buf),
                buf.readByteArray(MapTileS2CPacket.SIZE * MapTileS2CPacket.SIZE * 2),
                buf.readByteArray(MapTileS2CPacket.SIZE * MapTileS2CPacket.SIZE));
    }

    private static int[] readInts(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        // Validate before allocating: a negative or huge count is a malformed packet,
        // and allocating first turns it into an OOM/crash on the client.
        if (count < 0 || count > MapTileS2CPacket.SIZE * MapTileS2CPacket.SIZE)
            throw new IllegalArgumentException("Invalid map terrain palette");
        int[] values = new int[count];
        for (int i = 0; i < values.length; i++) values[i] = buf.readInt();
        return values;
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
        buf.writeLong(base);
        buf.writeVarInt(count);
        buf.writeVarInt(index);
        buf.writeVarIntArray(states);
        buf.writeVarInt(colors.length);
        for (int color : colors) buf.writeInt(color);
        buf.writeByteArray(pixels);
        buf.writeByteArray(light);
    }

    /** False only for a wire payload that failed to decode; receivers ignore those. */
    public boolean valid() {
        return this != INVALID;
    }

    /** Malformed payloads degrade to the invalid sentinel instead of throwing. */
    static MapTerrainS2CPacket decode(FriendlyByteBuf buf) {
        return PacketCompat.decode(buf, MapTerrainS2CPacket::new, INVALID);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
