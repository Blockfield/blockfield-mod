package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.network.packet.MapTileS2CPacket;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class MapTileAssemblyTest {
    private static MapTileS2CPacket tile(int index, long revision) {
        int w = index % 2 == 0 ? 128 : 1, h = index < 2 ? 128 : 3;
        byte[] rgb = new byte[w * h * 3];
        Arrays.fill(rgb, (byte) (index + 1));
        return new MapTileS2CPacket(
                ResourceLocation.parse("test:map"),
                "map",
                -20,
                109,
                -50,
                81,
                129,
                131,
                revision,
                index,
                rgb);
    }

    @Test
    void reassemblesOddEdgesOutOfOrderAndIgnoresDuplicates() {
        var assembly = new MapTileAssembly(tile(0, 1));
        assertEquals(0f, assembly.progress());
        assertFalse(assembly.accept(tile(0, 1)));
        assertEquals(0.25f, assembly.progress());
        assertFalse(assembly.accept(tile(0, 1)));
        assertEquals(0.25f, assembly.progress());
        assertFalse(assembly.accept(tile(3, 1)));
        assertFalse(assembly.accept(tile(1, 1)));
        assertTrue(assembly.accept(tile(2, 1)));
        assertEquals(1f, assembly.progress());
        assertEquals(1, assembly.rgb[0]);
        assertEquals(2, assembly.rgb[128 * 3]);
        assertEquals(3, assembly.rgb[128 * 129 * 3]);
        assertEquals(4, assembly.rgb[assembly.rgb.length - 1]);
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(tile(0, 2)));
    }

    @Test
    void patchesKeepUntouchedTilesOfTheBaseImage() {
        byte[] base = new byte[129 * 131 * 3];
        Arrays.fill(base, (byte) 9);
        var patch =
                new MapTileAssembly(
                        tile(3, 2),
                        new MapTileAssembly.MapImage(tile(0, 1), base, null, null, null),
                        1);
        assertTrue(patch.accept(tile(3, 2)));
        assertEquals(9, patch.rgb[0]);
        assertEquals(4, patch.rgb[patch.rgb.length - 1]);
        assertEquals(9, base[base.length - 1], "The visible image must stay intact");
    }

    @Test
    void terrainPatchesKeepSurfacesOutsideTheirTiles() {
        var palette = new MapPalette();
        int stone = palette.add(1, 0x808080);
        int[] entries = new int[129 * 131];
        java.util.Arrays.fill(entries, stone);
        byte[] light = new byte[129 * 131];
        var base =
                new MapTileAssembly.MapImage(
                        tile(0, 1), new byte[129 * 131 * 3], palette, entries, light);
        var patch = new MapTileAssembly(tile(3, 2), base, 1);
        assertSame(palette, patch.palette, "Patches extend the image palette");
        int grass = patch.palette.add(2, 0x00FF00);
        int[] tileEntries = {grass, grass, grass};
        assertTrue(patch.accept(tile(3, 2), tileEntries, new byte[] {9, 9, 9}));
        assertEquals(stone, patch.entries[0]);
        assertEquals(grass, patch.entries[patch.entries.length - 1]);
        assertEquals(9, patch.light[patch.light.length - 1]);
        assertEquals(stone, entries[entries.length - 1], "The shown image stays intact");
    }

    @Test
    void paletteKeepsFixedColorsAndRecolorsTheRest() {
        var palette = new MapPalette();
        int tuned =
                palette.add(
                        5,
                        0x112233 | com.lunares.blockfield.network.packet.MapTerrainS2CPacket.FIXED);
        int modded = palette.add(6, 0x445566);
        int water = palette.add(-1, 0x294F70);
        assertEquals(modded, palette.add(6, 0x445566));
        int[] calls = {0};
        java.util.function.IntBinaryOperator pack =
                (state, fallback) -> {
                    calls[0]++;
                    return 0xABCDEF;
                };
        assertEquals(0x112233, palette.flat(tuned, pack));
        assertEquals(0xABCDEF, palette.flat(modded, pack));
        assertEquals(0x294F70, palette.flat(water, pack));
        palette.flat(modded, pack);
        assertEquals(1, calls[0], "Flat colors are cached until a resource reload");
        palette.recolor();
        assertEquals(0x123456, palette.flat(modded, (state, fallback) -> 0x123456));
        assertEquals(5, palette.state(tuned));
    }

    @Test
    void codecAndBoundsKeepAllocationsAndPacketsBounded() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MapTileS2CPacket original = tile(3, 8);
            MapTileS2CPacket.CODEC.encode(buf, original);
            assertTrue(buf.readableBytes() < 65536);
            var decoded = MapTileS2CPacket.CODEC.decode(buf);
            assertTrue(original.sameImage(decoded));
            assertEquals(original.index(), decoded.index());
            assertArrayEquals(original.rgb(), decoded.rgb());
        } finally {
            buf.release();
        }
        assertThrows(IllegalArgumentException.class, () -> tile(4, 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MapTileS2CPacket(
                                ResourceLocation.parse("test:map"),
                                "map",
                                0,
                                1,
                                0,
                                1,
                                2049,
                                1,
                                1,
                                0,
                                new byte[384]));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MapTileS2CPacket(
                                ResourceLocation.parse("test:map"),
                                "map",
                                Double.NaN,
                                1,
                                0,
                                1,
                                1,
                                1,
                                1,
                                0,
                                new byte[3]));
    }
}
