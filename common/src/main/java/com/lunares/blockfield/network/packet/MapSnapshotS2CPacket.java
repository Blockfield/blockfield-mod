package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded RGB image; vanilla packet compression handles repeated terrain colours. */
public record MapSnapshotS2CPacket(
        ResourceLocation dimension,
        String mapId,
        double minX,
        double maxX,
        double minZ,
        double maxZ,
        byte[] rgb)
        implements CustomPacketPayload {
    // ponytail: fixed 256px bounds packet/memory cost; add tiled detail if large maps need
    // building-level zoom.
    public static final int SIZE = 256;
    public static final int BYTES = SIZE * SIZE * 3;
    public static final Type<MapSnapshotS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "map_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, MapSnapshotS2CPacket> CODEC =
            CustomPacketPayload.codec(MapSnapshotS2CPacket::write, MapSnapshotS2CPacket::decode);

    private static final MapSnapshotS2CPacket INVALID =
            new MapSnapshotS2CPacket(
                    ResourceLocation.fromNamespaceAndPath("blockfield", "invalid"),
                    "invalid",
                    0,
                    1,
                    0,
                    1,
                    new byte[BYTES]);

    public MapSnapshotS2CPacket {
        if (rgb.length != BYTES
                || !Double.isFinite(minX)
                || !Double.isFinite(maxX)
                || !Double.isFinite(minZ)
                || !Double.isFinite(maxZ)
                || minX >= maxX
                || minZ >= maxZ) {
            throw new IllegalArgumentException("Invalid map snapshot");
        }
    }

    private MapSnapshotS2CPacket(FriendlyByteBuf buf) {
        this(
                buf.readResourceLocation(),
                buf.readUtf(128),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readByteArray(BYTES));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeUtf(mapId, 128);
        buf.writeDouble(minX);
        buf.writeDouble(maxX);
        buf.writeDouble(minZ);
        buf.writeDouble(maxZ);
        buf.writeByteArray(rgb);
    }

    /** False only for a wire payload that failed to decode; receivers ignore those. */
    public boolean valid() {
        return this != INVALID;
    }

    /** Malformed payloads degrade to the invalid sentinel instead of throwing. */
    static MapSnapshotS2CPacket decode(FriendlyByteBuf buf) {
        return PacketCompat.decode(buf, MapSnapshotS2CPacket::new, INVALID);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
