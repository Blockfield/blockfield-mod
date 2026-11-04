package com.lunares.blockfield.client.gui.tablet;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;

/** Inverts the rendered screen plane, retaining perspective division for pointer input. */
public final class TabletProjection {
    private final Matrix3f inverse;

    private TabletProjection(Matrix3f inverse) {
        this.inverse = inverse;
    }

    /** One layout-pixel pose for both the textured vertices and their pointer projection. */
    public static Matrix4f glassPose(Matrix4fc bone, float width, float height, float z) {
        return new Matrix4f(bone)
                .translate(width / 2, -height / 2, z)
                .scale(-width / TabletScreenTarget.WIDTH, height / TabletScreenTarget.HEIGHT, 1f);
    }

    /** The clip matrix maps tablet layout coordinates to the exact textured glass plane. */
    public static TabletProjection glass(Matrix4fc clip, int guiWidth, int guiHeight) {
        for (int x : new int[] {0, TabletScreenTarget.WIDTH}) {
            for (int y : new int[] {0, TabletScreenTarget.HEIGHT}) {
                float w = clip.m03() * x + clip.m13() * y + clip.m33();
                if (!Float.isFinite(w) || w <= 0.000001f) return null;
            }
        }
        Matrix3f plane =
                new Matrix3f(
                        clip.m00(),
                        clip.m01(),
                        clip.m03(),
                        clip.m10(),
                        clip.m11(),
                        clip.m13(),
                        clip.m30(),
                        clip.m31(),
                        clip.m33());
        Matrix3f viewport =
                new Matrix3f()
                        .m00(guiWidth / 2f)
                        .m11(-guiHeight / 2f)
                        .m20(guiWidth / 2f)
                        .m21(guiHeight / 2f);
        viewport.mul(plane);
        float determinant = viewport.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 0.000001f) return null;
        return new TabletProjection(viewport.invert());
    }

    static TabletProjection flat(float left, float top, float width, float height) {
        float sx = TabletScreenTarget.WIDTH / width, sy = TabletScreenTarget.HEIGHT / height;
        return new TabletProjection(new Matrix3f().m00(sx).m11(sy).m20(-left * sx).m21(-top * sy));
    }

    /** Points outside the glass remain usable for a map drag started inside it. */
    Vector2f surface(double mouseX, double mouseY) {
        Vector3f p = inverse.transform(new Vector3f((float) mouseX, (float) mouseY, 1));
        if (!Float.isFinite(p.z) || p.z <= 0.000001f) return null;
        float x = p.x / p.z, y = p.y / p.z;
        return Float.isFinite(x) && Float.isFinite(y) ? new Vector2f(x, y) : null;
    }

    static boolean contains(Vector2f p) {
        return p != null
                && p.x >= 0
                && p.y >= 0
                && p.x < TabletScreenTarget.WIDTH
                && p.y < TabletScreenTarget.HEIGHT;
    }
}
