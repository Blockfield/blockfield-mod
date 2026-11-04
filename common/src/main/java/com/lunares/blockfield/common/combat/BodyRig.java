package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.StanceTransition;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Bone matrices and box corners in metres; mirrors {@code ModelPart.translateAndRotate}. */
public final class BodyRig {
    private BodyRig() {}

    /** Explicit stance state keeps this correction out of unrelated pose fingerprints. */
    public static boolean stanceBasis(AnimationState.Stance stance) {
        return stance.eased()
                && (stance.crouch() != 0
                        || stance.flat() != 0
                        || stance.age() < StanceTransition.TICKS
                                && stance.fromFlat() != stance.toFlat());
    }

    /**
     * out[i] = root · T(pivot/16) · Rz·Ry·Rx; params = 6 × {x,y,z,xRot,yRot,zRot} in
     * pixels/radians. The rotation replays the native op sequence (translate, then one ZYX
     * quaternion rotate) so corners match the renderer to float noise. Per-part scale is not an
     * input (always 1).
     */
    public static void bones(org.joml.Matrix4fc root, float[] params, org.joml.Matrix4f[] out) {
        bones(root, params, out, false);
    }

    // shortcut: Euler math is stance-only; unify after broader pose baselines are reaccepted.
    public static void bones(
            org.joml.Matrix4fc root, float[] params, org.joml.Matrix4f[] out, boolean stanceBasis) {
        for (int i = 0; i < 6; i++) {
            out[i].set(root);
            out[i].translate(params[i * 6] / 16, params[i * 6 + 1] / 16, params[i * 6 + 2] / 16);
            float xRot = params[i * 6 + 3], yRot = params[i * 6 + 4], zRot = params[i * 6 + 5];
            if (xRot != 0 || yRot != 0 || zRot != 0) {
                if (stanceBasis) out[i].rotateZYX(zRot, yRot, xRot);
                else out[i].rotate(new Quaternionf().rotationZYX(zRot, yRot, xRot));
            }
        }
    }

    /**
     * World corners of the box: (bone · local) applied to the 8 (x..x+w, y..y+h, z..z+d)/16
     * corners.
     */
    public static void corners(
            org.joml.Matrix4fc bone, BodyGeometry.Box box, org.joml.Vector3f[] out8) {
        Matrix4f world = new Matrix4f(bone).mul(box.local());
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? box.x() : box.x() + box.w();
            float y = (i & 2) == 0 ? box.y() : box.y() + box.h();
            float z = (i & 4) == 0 ? box.z() : box.z() + box.d();
            world.transformPosition(x / 16, y / 16, z / 16, out8[i]);
        }
    }
}
