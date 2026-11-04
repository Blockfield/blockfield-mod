package com.lunares.blockfield.client.gui.tablet;

import com.lunares.blockfield.network.packet.MapTileS2CPacket;
import java.util.BitSet;

/** Keep the previous complete image visible while its replacement is arriving. */
final class MapTileAssembly {
    final MapTileS2CPacket header;
    final byte[] rgb;
    // Terrain images also keep surfaces and light per pixel; null for plain RGB packets.
    final MapPalette palette;
    final int[] entries;
    final byte[] light;
    private final int expected;
    private final BitSet received = new BitSet();

    MapTileAssembly(MapTileS2CPacket first) {
        this(first, null, first.count());
    }

    /** A patch of {@code expected} tiles applied to a copy of the complete {@code base} image. */
    MapTileAssembly(MapTileS2CPacket first, MapImage base, int expected) {
        header = first;
        int pixels = first.width() * first.height();
        rgb = base != null ? base.rgb().clone() : new byte[pixels * 3];
        palette = base != null && base.palette() != null ? base.palette() : new MapPalette();
        entries = base != null && base.entries() != null ? base.entries().clone() : new int[pixels];
        light = base != null && base.light() != null ? base.light().clone() : new byte[pixels];
        this.expected = expected;
    }

    float progress() {
        return (float) received.cardinality() / expected;
    }

    boolean accept(MapTileS2CPacket tile) {
        return accept(tile, null, null);
    }

    /** Terrain tiles also carry palette indices (into {@link #palette}) and light per pixel. */
    boolean accept(MapTileS2CPacket tile, int[] tileEntries, byte[] tileLight) {
        if (!header.sameImage(tile)) throw new IllegalArgumentException("Mixed map tile revisions");
        int w = tile.tileWidth();
        for (int row = 0; row < tile.tileHeight(); row++) {
            int to = (tile.z() + row) * tile.width() + tile.x();
            System.arraycopy(tile.rgb(), row * w * 3, rgb, to * 3, w * 3);
            if (tileEntries != null) System.arraycopy(tileEntries, row * w, entries, to, w);
            if (tileLight != null) System.arraycopy(tileLight, row * w, light, to, w);
        }
        received.set(tile.index());
        return received.cardinality() == expected;
    }

    MapImage image(boolean terrain) {
        return terrain
                ? new MapImage(header, rgb, palette, entries, light)
                : new MapImage(header, rgb, null, null, null);
    }

    /** A complete image; surfaces are null for plain RGB packets. */
    record MapImage(
            MapTileS2CPacket header, byte[] rgb, MapPalette palette, int[] entries, byte[] light) {}
}
