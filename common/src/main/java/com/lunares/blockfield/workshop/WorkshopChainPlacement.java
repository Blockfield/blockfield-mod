package com.lunares.blockfield.workshop;

import net.minecraft.core.BlockPos;

/** Tile adjacency uses the full transformed footprint, including empty cells at its edges. */
public final class WorkshopChainPlacement {
    private WorkshopChainPlacement() {}

    public static BlockPos next(
            BlockPos origin, int sx, int sz, int rotation, double lookX, double lookZ) {
        int x = (rotation & 1) == 0 ? sx : sz, z = (rotation & 1) == 0 ? sz : sx;
        // A near-diagonal look prefers the module's long axis; a decisive look can extend either
        // axis.
        boolean alongX =
                Math.abs(lookX) > Math.abs(lookZ) * 1.25
                        || Math.abs(lookZ) <= Math.abs(lookX) * 1.25 && x >= z;
        return alongX
                ? origin.offset(lookX < 0 ? -x : x, 0, 0)
                : origin.offset(0, 0, lookZ < 0 ? -z : z);
    }

    public static boolean adjacent(BlockPos from, BlockPos to, int sx, int sz, int rotation) {
        int x = (rotation & 1) == 0 ? sx : sz, z = (rotation & 1) == 0 ? sz : sx;
        long dx = (long) to.getX() - from.getX(), dz = (long) to.getZ() - from.getZ();
        return to.getY() == from.getY()
                && (Math.abs(dx) == x && dz == 0 || Math.abs(dz) == z && dx == 0);
    }
}
