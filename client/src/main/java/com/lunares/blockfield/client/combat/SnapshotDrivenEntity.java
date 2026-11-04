package com.lunares.blockfield.client.combat;

/** Pinned type-specific state; no cast of a vehicle to LivingEntity. */
public interface SnapshotDrivenEntity {
    void blockfield$clearInterpolation();

    default void blockfield$clearMotionInterpolation() {}

    default void blockfield$sampleAngles(SnapshotResolver.Sample sample) {}
}
