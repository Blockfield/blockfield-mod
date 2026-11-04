package com.lunares.blockfield.common.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The effective eye: a fixed point on the animated head. The anchor is the head capture box clamped
 * to the vanilla eye (inside the box in ordinary poses, so standing aim is exactly the vanilla eye;
 * on the box surface when the body tumbles away from it). Projection onto a convex box is
 * continuous, so the anchor never snaps at action start/end or stance transitions.
 */
public final class EffectiveEye {
    /** The anchor never rests inside a solid block: it stops this far before the first hit. */
    public static final double WALL_MARGIN = 0.05;

    private EffectiveEye() {}

    /**
     * Closest point on the oriented head box to the eye: the eye itself when inside. The box is one
     * {@code BodyGeometry} head cuboid in world space: {@code center}, three unit axes and three
     * half extents, all metres.
     */
    public static Vec3 closestPointOnHeadBox(
            Vec3 center, Vec3 ux, Vec3 uy, Vec3 uz, double hx, double hy, double hz, Vec3 eye) {
        double dx = eye.x - center.x, dy = eye.y - center.y, dz = eye.z - center.z;
        double tx = clamp(dx * ux.x + dy * ux.y + dz * ux.z, -hx, hx);
        double ty = clamp(dx * uy.x + dy * uy.y + dz * uy.z, -hy, hy);
        double tz = clamp(dx * uz.x + dy * uz.y + dz * uz.z, -hz, hz);
        return new Vec3(
                center.x + ux.x * tx + uy.x * ty + uz.x * tz,
                center.y + ux.y * tx + uy.y * ty + uz.y * tz,
                center.z + ux.z * tx + uy.z * ty + uz.z * tz);
    }

    /**
     * Recovers the head box frame from eight world corners (corner order irrelevant): the centre is
     * the mean, the axes are the three edges out of the corner nearest the origin, normalised.
     * Rigid capture transforms keep the edges orthogonal; float noise is ~1e-7. The head box is
     * always the 8x8x8 cube ({@code BodyGeometry.skin}), so the three shortest non-collinear
     * corners are exactly its edges.
     */
    public static HeadBox boxFromCorners(double[] corners) {
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < 8; i++) {
            cx += corners[i * 3];
            cy += corners[i * 3 + 1];
            cz += corners[i * 3 + 2];
        }
        cx /= 8;
        cy /= 8;
        cz /= 8;
        int root = 0;
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            double d =
                    (corners[i * 3] - cx) * (corners[i * 3] - cx)
                            + (corners[i * 3 + 1] - cy) * (corners[i * 3 + 1] - cy)
                            + (corners[i * 3 + 2] - cz) * (corners[i * 3 + 2] - cz);
            if (d < nearest) {
                nearest = d;
                root = i;
            }
        }
        double[][] edge = new double[3][];
        boolean[] used = new boolean[8];
        used[root] = true;
        for (int e = 0; e < 3; e++) {
            int best = -1;
            double bestDist = Double.POSITIVE_INFINITY;
            for (int i = 0; i < 8; i++) {
                if (used[i]) continue;
                double d = dist2(corners, root, corners, i);
                if (d < bestDist && !collinear(edge, e, corners, root, i)) {
                    bestDist = d;
                    best = i;
                }
            }
            used[best] = true;
            edge[e] =
                    new double[] {
                        corners[best * 3] - corners[root * 3],
                        corners[best * 3 + 1] - corners[root * 3 + 1],
                        corners[best * 3 + 2] - corners[root * 3 + 2]
                    };
        }
        return new HeadBox(
                new Vec3(cx, cy, cz),
                unit(edge[0]),
                unit(edge[1]),
                unit(edge[2]),
                len(edge[0]) / 2,
                len(edge[1]) / 2,
                len(edge[2]) / 2);
    }

    /** Head box frame: world centre, three unit axes, three half extents (metres). */
    public record HeadBox(Vec3 center, Vec3 ux, Vec3 uy, Vec3 uz, double hx, double hy, double hz) {
        /** Closest point on this box to the eye (the eye itself when inside). */
        public Vec3 anchor(Vec3 eye) {
            return closestPointOnHeadBox(center, ux, uy, uz, hx, hy, hz, eye);
        }
    }

    /**
     * Body-yaw-local (right/up/forward) form of a world vector for entity {@code yawDeg}. The
     * streamed head offset travels in this frame so the client can rotate it by its own current
     * body yaw: a world-space offset goes stale whenever the body turns between the server tick and
     * the render (prone transitions carry up to ~0.3 m of offset, so tens of degrees of turn put
     * the camera outside the head box). Rotation preserves length, so standing (zero offset) stays
     * exactly vanilla. Convention: forward = (-sin yaw, 0, cos yaw), right = forward x up.
     */
    public static Vec3 yawToLocal(float yawDeg, Vec3 world) {
        double c = Math.cos(Math.toRadians(yawDeg)), s = Math.sin(Math.toRadians(yawDeg));
        return new Vec3(-c * world.x - s * world.z, world.y, -s * world.x + c * world.z);
    }

    /** Inverse of {@link #yawToLocal}: a body-local vector back into world space. */
    public static Vec3 yawToWorld(float yawDeg, Vec3 local) {
        double c = Math.cos(Math.toRadians(yawDeg)), s = Math.sin(Math.toRadians(yawDeg));
        return new Vec3(-c * local.x - s * local.z, local.y, -s * local.x + c * local.z);
    }

    /**
     * Clamps the anchor along the segment from the vanilla eye (always collision-safe) toward it,
     * stopping {@link #WALL_MARGIN} before the first solid block. Same rule on both sides.
     */
    public static Vec3 clampToSight(Level level, Entity entity, Vec3 vanillaEye, Vec3 anchor) {
        if (level == null) return anchor;
        HitResult hit =
                level.clip(
                        new ClipContext(
                                vanillaEye,
                                anchor,
                                ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE,
                                entity));
        if (hit.getType() == HitResult.Type.MISS) return anchor;
        return slideBack(vanillaEye, hit.getLocation(), WALL_MARGIN);
    }

    /** Linear blend of two keyframes (client camera chase and its offline simulation share it). */
    public static Vec3 lerp(Vec3 a, Vec3 b, float t) {
        float k = Math.min(1, Math.max(0, t));
        return new Vec3(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.z + (b.z - a.z) * k);
    }

    /** Latest hit pulled back toward the safe point by the margin (never past it). */
    static Vec3 slideBack(Vec3 safe, Vec3 hit, double margin) {
        double dx = hit.x - safe.x, dy = hit.y - safe.y, dz = hit.z - safe.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist <= margin) return safe;
        double k = (dist - margin) / dist;
        return new Vec3(safe.x + dx * k, safe.y + dy * k, safe.z + dz * k);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static double dist2(double[] a, int i, double[] b, int j) {
        double dx = a[i * 3] - b[j * 3],
                dy = a[i * 3 + 1] - b[j * 3 + 1],
                dz = a[i * 3 + 2] - b[j * 3 + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static double len(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static Vec3 unit(double[] v) {
        double l = len(v);
        return new Vec3(v[0] / l, v[1] / l, v[2] / l);
    }

    /** True when the candidate corner continues an independent box edge (not a face diagonal). */
    private static boolean collinear(double[][] edge, int count, double[] c, int root, int cand) {
        double[] v =
                new double[] {
                    c[cand * 3] - c[root * 3],
                    c[cand * 3 + 1] - c[root * 3 + 1],
                    c[cand * 3 + 2] - c[root * 3 + 2]
                };
        double l = len(v);
        if (l < 1e-9) return true;
        for (int e = 0; e < count; e++) {
            double dot =
                    Math.abs(
                            (v[0] * edge[e][0] + v[1] * edge[e][1] + v[2] * edge[e][2])
                                    / l
                                    / len(edge[e]));
            if (dot > 0.99) return true;
        }
        return false;
    }
}
