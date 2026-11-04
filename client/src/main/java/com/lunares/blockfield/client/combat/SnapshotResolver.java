package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationTiming;
import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.common.combat.SnapshotWireMath;
import java.util.List;
import java.util.NavigableMap;
import net.minecraft.util.Mth;

/** The client half of p3 r8 §4.3(B); it never extrapolates. */
public final class SnapshotResolver {
    public record Sample(
            SnapshotTransform before,
            SnapshotTransform after,
            double fraction,
            String mode,
            double x,
            double y,
            double z,
            List<Double> angles) {}

    private SnapshotResolver() {}

    /**
     * ADR4 §2.4 placement at R: the record at {@code t = ceil(R)} sampled with its own lossless
     * old/current pair at {@code p = R - (t - 1)} (exact integer R selects {@code (R, 1)}), in the
     * native client getter order: plain lerp for view yaw/pitch, wrapped rotLerp for body/head yaw.
     * A missing endpoint is never stretched: the newest earlier record holds at its current value
     * ({@code hold}), or the first record before the run ({@code snap}).
     */
    public static Sample exact(NavigableMap<Long, SnapshotTransform> frames, Time r) {
        if (frames.isEmpty()) return null;
        var endpoint = AnimationTiming.endpoint(r);
        var record = frames.get(endpoint.tick());
        if (record != null && record.exact())
            return exact(record, endpoint.partial(), endpoint.partial() == 1 ? "hold" : "lerp");
        var earlier = frames.floorEntry(endpoint.tick());
        if (earlier == null) {
            var first = frames.firstEntry().getValue();
            return first.exact() ? exact(first, 1, "snap") : null;
        }
        return earlier.getValue().exact() ? exact(earlier.getValue(), 1, "hold") : null;
    }

    static Sample exact(SnapshotTransform record, float p, String mode) {
        var placement = record.placement();
        var origin = SnapshotWireMath.origin(placement, p);
        var a = placement.angles();
        var angles = new java.util.ArrayList<Double>();
        // Collapse raw pairs with the native renderer's lerp, including the p == 1 rounding.
        angles.add((double) Mth.lerp(p, a.yRotO(), a.yRot()));
        angles.add((double) Mth.lerp(p, a.xRotO(), a.xRot()));
        if (record.kind() == 0) {
            angles.add((double) Mth.rotLerp(p, a.yBodyRotO(), a.yBodyRot()));
            angles.add((double) Mth.rotLerp(p, a.yHeadRotO(), a.yHeadRot()));
        } else if (record.kind() == 2) {
            var v = placement.vehicleAngles();
            angles.add((double) Mth.lerp(p, v.prevRoll(), v.roll()));
            angles.add((double) Mth.lerp(p, v.turretYRotO(), v.turretYRot()));
            angles.add((double) Mth.lerp(p, v.turretXRotO(), v.turretXRot()));
            angles.add((double) Mth.lerp(p, v.gunYRotO(), v.gunYRot()));
            angles.add((double) Mth.lerp(p, v.gunXRotO(), v.gunXRot()));
        }
        return new Sample(
                record, record, p, mode, origin.x(), origin.y(), origin.z(), List.copyOf(angles));
    }

    /**
     * Eligible horizontal step in metres drawn while R crosses {@code tick}, or NaN when the tick
     * is not capped traversal. Only consecutive player records (kind 0) on the same
     * epoch/teleport/seat run without a SNAP/SEAT/RESET flag count; the server already snaps a
     * discontinuous run's previousOrigin onto its origin (zero step), and vehicle or seat
     * boundaries break continuity here. Horizontal only: gravity falls are continuous yet faster
     * than any locomotion cap.
     */
    public static double traverseStep(NavigableMap<Long, SnapshotTransform> frames, long tick) {
        var now = frames.get(tick);
        if (now == null || !now.exact() || now.kind() != 0) return Double.NaN;
        if ((now.flags()
                        & (AnimationRecipe.SnapshotRecord.FLAG_SNAP
                                | AnimationRecipe.SnapshotRecord.FLAG_SEAT
                                | AnimationRecipe.SnapshotRecord.FLAG_RESET))
                != 0) return Double.NaN;
        var prev = frames.get(tick - 1);
        if (prev == null || !prev.exact() || prev.kind() != now.kind()) return Double.NaN;
        if (now.epoch() != prev.epoch()
                || now.seat() != prev.seat()
                || now.teleport() != prev.teleport()) return Double.NaN;
        // The capped interval is the record's own pair; a mismatch against the previous origin
        // is a discontinuity outside any capped traversal.
        if (!now.placement().previousOrigin().equals(prev.placement().origin())) return Double.NaN;
        double dx = now.placement().origin().x() - now.placement().previousOrigin().x();
        double dz = now.placement().origin().z() - now.placement().previousOrigin().z();
        return Math.hypot(dx, dz);
    }
}
