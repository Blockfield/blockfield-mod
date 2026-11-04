package com.lunares.blockfield.client.gui.tablet;

import com.lunares.blockfield.network.packet.MapTerrainS2CPacket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/**
 * Surfaces of one map image, appended by its tiles and patches. Pixels keep indices into it, so the
 * client can recolor the whole image after a resource reload.
 */
final class MapPalette {
    private final Map<Long, Integer> index = new HashMap<>();
    private int[] states = new int[64], colors = new int[64], flats = new int[64];
    private int size;

    int add(int state, int color) {
        return index.computeIfAbsent(
                (long) state << 32 | color & 0xFFFFFFFFL,
                key -> {
                    if (size == states.length) {
                        states = Arrays.copyOf(states, size * 2);
                        colors = Arrays.copyOf(colors, size * 2);
                        flats = Arrays.copyOf(flats, size * 2);
                    }
                    states[size] = state;
                    colors[size] = color;
                    flats[size] = -1;
                    return size++;
                });
    }

    int size() {
        return size;
    }

    int state(int entry) {
        return states[entry];
    }

    /** Unlit map color: the server's for fixed surfaces, the resource pack's for others. */
    int flat(int entry, IntBinaryOperator resolve) {
        if (flats[entry] == -1) {
            int color = colors[entry] & 0xFFFFFF;
            flats[entry] =
                    (colors[entry] & MapTerrainS2CPacket.FIXED) != 0 || states[entry] < 0
                            ? color
                            : resolve.applyAsInt(states[entry], color);
        }
        return flats[entry];
    }

    void recolor() {
        Arrays.fill(flats, 0, size, -1);
    }
}
