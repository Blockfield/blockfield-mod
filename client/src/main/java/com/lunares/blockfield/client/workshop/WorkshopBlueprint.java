package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.network.packet.WorkshopAiPreviewS2CPacket;
import com.lunares.blockfield.workshop.WorkshopGeometry;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntMaps;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Canonical bounded grid language; expansion is bounded once on receipt, never during rendering.
 * {@code cells} maps every final cell {@link #index} to its {@code blocks} entry.
 */
record WorkshopBlueprint(
        List<Box> boxes, List<Cell> ghosts, int touched, List<String> blocks, Int2IntMap cells) {
    static final int MAX_GHOSTS = 512;
    static final int SIDE = WorkshopAiPreviewS2CPacket.MAX_SIDE;
    private static final int MAX_CELLS = 32_768;
    private static final Set<String> AIR =
            Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    record Box(int x0, int y0, int z0, int x1, int y1, int z1, boolean erase) {}

    record Cell(int x, int y, int z, boolean erase, boolean water) {
        Cell(int x, int y, int z, boolean erase) {
            this(x, y, z, erase, false);
        }
    }

    WorkshopBlueprint {
        boxes = List.copyOf(boxes);
        ghosts = List.copyOf(ghosts);
        blocks = List.copyOf(blocks);
        cells = Int2IntMaps.unmodifiable(cells);
    }

    boolean erase(int block) {
        return AIR.contains(blocks.get(block));
    }

    static WorkshopBlueprint parse(String json, int focusX, int focusY, int focusZ) {
        return parse(json, focusX, focusY, focusZ, SIDE, SIDE, SIDE);
    }

    static WorkshopBlueprint parse(
            String json, int focusX, int focusY, int focusZ, int sx, int sy, int sz) {
        return parse(json, focusX, focusY, focusZ, sx, sy, sz, 0, false);
    }

    static WorkshopBlueprint parse(
            String json,
            int focusX,
            int focusY,
            int focusZ,
            int sx,
            int sy,
            int sz,
            int rotY,
            boolean mirror) {
        // Emission order keeps the block table deterministic across runs.
        Map<Integer, List<WorkshopGeometry.Write>> writes = new LinkedHashMap<>();
        List<String> blocks = new ArrayList<>();
        var geometry =
                WorkshopGeometry.expandMerged(
                        json,
                        MAX_CELLS,
                        sx,
                        sy,
                        sz,
                        voxel -> {
                            int[] p =
                                    WorkshopSketchPlacement.mapCell(
                                            voxel.x(), voxel.y(), voxel.z(), sx, sz, rotY, mirror);
                            writes.computeIfAbsent(
                                            index(p[0], p[1], p[2]), ignored -> new ArrayList<>())
                                    .add(
                                            new WorkshopGeometry.Write(
                                                    com.lunares.blockfield.workshop
                                                            .WorkshopBlockStates.transform(
                                                            voxel.block(), rotY, mirror, false),
                                                    voxel.match()));
                        });
        // The held ghost and the map preview show the empty-world projection with the same
        // sequential match semantics the server snapshot applies: a failed match keeps the
        // earlier planned value instead of overwriting it, and a cell no write reaches stays
        // out of the preview exactly like it stays out of the build.
        boolean legacy = geometry.version() == 1;
        Int2IntOpenHashMap cells = new Int2IntOpenHashMap();
        cells.defaultReturnValue(-1);
        for (var entry : writes.entrySet()) {
            int key = entry.getKey();
            List<WorkshopGeometry.Write> sequence = entry.getValue();
            if (legacy) sequence = sequence.subList(sequence.size() - 1, sequence.size());
            String resolved = WorkshopGeometry.resolve(sequence, "minecraft:air", true);
            if (resolved == null) continue;
            if (!blocks.contains(resolved)) blocks.add(resolved);
            cells.put(key, blocks.indexOf(resolved));
        }
        List<Box> boxes =
                geometry.boxes().stream()
                        .map(
                                box -> {
                                    int[] a =
                                            WorkshopSketchPlacement.mapCell(
                                                    box.x0(), box.y0(), box.z0(), sx, sz, rotY,
                                                    mirror);
                                    int[] b =
                                            WorkshopSketchPlacement.mapCell(
                                                    box.x1() - 1,
                                                    box.y1() - 1,
                                                    box.z1() - 1,
                                                    sx,
                                                    sz,
                                                    rotY,
                                                    mirror);
                                    return new Box(
                                            Math.min(a[0], b[0]),
                                            Math.min(a[1], b[1]),
                                            Math.min(a[2], b[2]),
                                            Math.max(a[0], b[0]) + 1,
                                            Math.max(a[1], b[1]) + 1,
                                            Math.max(a[2], b[2]) + 1,
                                            AIR.contains(box.block()));
                                })
                        .toList();
        int fx = Math.clamp(focusX, -SIDE, SIDE),
                fy = Math.clamp(focusY, -SIDE, SIDE),
                fz = Math.clamp(focusZ, -SIDE, SIDE);
        // A total ordering keeps sparse-map iteration order out of the visible selection.
        Comparator<Cell> nearest =
                Comparator.comparingLong((Cell cell) -> distance(cell, fx, fy, fz))
                        .thenComparingInt(cell -> index(cell.x(), cell.y(), cell.z()));
        PriorityQueue<Cell> selected = new PriorityQueue<>(MAX_GHOSTS + 1, nearest.reversed());
        var erasing = new boolean[blocks.size()];
        for (int i = 0; i < erasing.length; i++) erasing[i] = AIR.contains(blocks.get(i));
        var entries = cells.int2IntEntrySet().fastIterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            int key = entry.getIntKey();
            int x = x(key), y = y(key), z = z(key);
            boolean erase = erasing[entry.getIntValue()];
            if (same(cells, erasing, x - 1, y, z, erase)
                    && same(cells, erasing, x + 1, y, z, erase)
                    && same(cells, erasing, x, y - 1, z, erase)
                    && same(cells, erasing, x, y + 1, z, erase)
                    && same(cells, erasing, x, y, z - 1, erase)
                    && same(cells, erasing, x, y, z + 1, erase)) continue;
            selected.add(
                    new Cell(
                            x,
                            y,
                            z,
                            erase,
                            blocks.get(entry.getIntValue()).equals("minecraft:water")));
            if (selected.size() > MAX_GHOSTS) selected.poll();
        }
        List<Cell> ghosts = new ArrayList<>(selected);
        ghosts.sort(nearest);
        return new WorkshopBlueprint(boxes, ghosts, cells.size(), blocks, cells);
    }

    /** Block index at a plan cell, or -1 outside the plan. */
    int block(int x, int y, int z) {
        return inside(x, y, z) ? cells.getOrDefault(index(x, y, z), -1) : -1;
    }

    private static long distance(Cell cell, int x, int y, int z) {
        long dx = cell.x() - x, dy = cell.y() - y, dz = cell.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean same(
            Int2IntOpenHashMap cells, boolean[] erasing, int x, int y, int z, boolean erase) {
        if (!inside(x, y, z)) return false;
        int block = cells.get(index(x, y, z));
        return block >= 0 && erasing[block] == erase;
    }

    private static boolean inside(int x, int y, int z) {
        return x >= 0 && x < SIDE && y >= 0 && y < SIDE && z >= 0 && z < SIDE;
    }

    static int index(int x, int y, int z) {
        return (y * SIDE + x) * SIDE + z;
    }

    static int x(int index) {
        return index / SIDE % SIDE;
    }

    static int y(int index) {
        return index / (SIDE * SIDE);
    }

    static int z(int index) {
        return index % SIDE;
    }
}
