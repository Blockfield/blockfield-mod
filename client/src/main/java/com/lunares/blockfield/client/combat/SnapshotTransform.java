package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.AnimationState;
import java.util.List;

/**
 * Immutable decoded endpoint, independent of mutable packet arrays and entity state. Every record
 * keeps its stamped identity, flags, lossless placement and (players) the complete decoded
 * checkpoint.
 */
public record SnapshotTransform(
        long tick,
        int kind,
        double x,
        double y,
        double z,
        List<Integer> angles,
        long epoch,
        long seat,
        long teleport,
        AnimationState.Identity identity,
        int flags,
        AnimationState.Placement placement,
        AnimationState checkpoint) {
    /** One decoded ADR4 record of the batch at {@code tick}. */
    public static SnapshotTransform exact(
            long tick,
            AnimationState.Identity identity,
            int flags,
            AnimationState.Placement placement,
            AnimationState checkpoint) {
        var origin = placement.origin();
        return new SnapshotTransform(
                tick,
                identity.kind(),
                origin.x(),
                origin.y(),
                origin.z(),
                List.of(),
                identity.targetEpoch(),
                identity.seatGeneration(),
                identity.teleportGeneration(),
                identity,
                flags,
                placement,
                checkpoint);
    }

    public boolean exact() {
        return placement != null;
    }
}
