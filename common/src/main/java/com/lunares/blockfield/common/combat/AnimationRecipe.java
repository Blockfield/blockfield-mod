package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.List;
import java.util.Objects;

/**
 * Complete decoded inputs selected by the common resolver, before native scratch materialization.
 */
public record AnimationRecipe(
        AnimationState.Identity identity,
        long high,
        Time r,
        Time a,
        long placementTick,
        float placementPartial,
        long animationTick,
        float animationPartial,
        AnimationState.Placement placement,
        AnimationState checkpoint,
        AnimationEvent.ObserverSeed seed,
        AnimationGraphState graph,
        AnimationStartReport.Victim starts,
        Digest decodedDigest) {
    public AnimationRecipe {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(r);
        Objects.requireNonNull(a);
        Objects.requireNonNull(placement);
        Objects.requireNonNull(checkpoint);
        Objects.requireNonNull(seed);
        Objects.requireNonNull(graph);
        Objects.requireNonNull(starts);
        Objects.requireNonNull(decodedDigest);
    }

    public record SnapshotRecord(
            AnimationState.Identity identity,
            int flags,
            AnimationState.Placement placement,
            @AnimationSchema.Nullable AnimationState checkpoint) {
        /** Complete state/dependencies for the record tick; discontinuity bits require it. */
        public static final int FLAG_FULL = 1;

        /** Teleport snap discontinuity. */
        public static final int FLAG_SNAP = 2;

        /** Seat change discontinuity. */
        public static final int FLAG_SEAT = 4;

        /** Life/dimension reset discontinuity. */
        public static final int FLAG_RESET = 8;

        public SnapshotRecord {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(placement);
            if (identity.kind() < 0
                    || identity.kind() > 2
                    || (flags & ~15) != 0
                    || (flags & (FLAG_SNAP | FLAG_SEAT | FLAG_RESET)) != 0
                            && (flags & FLAG_FULL) == 0)
                throw new IllegalArgumentException("Invalid snapshot kind/flags");
            if (identity.kind() == 0 && checkpoint == null)
                throw new IllegalArgumentException("Player requires complete checkpoint");
            if (checkpoint != null
                    && (!identity.sameGeneration(checkpoint.identity())
                            || !placement.equals(checkpoint.placement())))
                throw new IllegalArgumentException(
                        "Snapshot checkpoint identity/placement mismatch");
        }
    }

    public record Batch(
            long connectionId,
            long sceneGeneration,
            List<SnapshotRecord> records,
            List<Definition> definitions,
            List<AnimationEvent.ObserverSeed> seeds) {
        public Batch {
            AnimationSchema.nonnegative(connectionId, "connection id");
            AnimationSchema.nonnegative(sceneGeneration, "scene generation");
            records = List.copyOf(records);
            definitions = List.copyOf(definitions);
            seeds = List.copyOf(seeds);
            AnimationSchema.count(records.size(), AnimationSchema.MAX_VICTIMS, "snapshot records");
            for (int i = 0; i < records.size(); i++) {
                var id = records.get(i).identity();
                if (id.connectionId() != connectionId || id.sceneGeneration() != sceneGeneration)
                    throw new IllegalArgumentException("Snapshot connection/scene mismatch");
                if (i > 0 && records.get(i - 1).identity().entityId() >= id.entityId())
                    throw new IllegalArgumentException("Snapshot ids must increase");
            }
        }
    }
}
