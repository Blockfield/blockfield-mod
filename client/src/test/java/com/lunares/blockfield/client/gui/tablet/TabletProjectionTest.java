package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class TabletProjectionTest {
    private static final int WIDTH = TabletScreenTarget.WIDTH;
    private static final int HEIGHT = TabletScreenTarget.HEIGHT;

    @Test
    void tiltedGlassRecoversClicksAndDragsAtEveryGuiScale() {
        Matrix4f clip =
                new Matrix4f()
                        .perspective((float) Math.toRadians(70), 16f / 9, 0.05f, 100)
                        .translate(0.1f, -0.12f, -1.4f)
                        .rotateXYZ(0.42f, -0.6f, 0.25f)
                        .translate(-0.45f, 0.3f, 0.005f)
                        .scale(0.9f / WIDTH, -0.6f / HEIGHT, 1);
        for (int[] gui : new int[][] {{1280, 720}, {640, 360}, {427, 240}}) {
            TabletProjection projection = TabletProjection.glass(clip, gui[0], gui[1]);
            assertNotNull(projection);
            for (Vector2f point :
                    new Vector2f[] {
                        new Vector2f(0, 0),
                        new Vector2f(WIDTH, 0),
                        new Vector2f(0, HEIGHT),
                        new Vector2f(WIDTH, HEIGHT),
                        new Vector2f(113, 87),
                        new Vector2f(450, 270),
                        new Vector2f(-60, 190)
                    }) {
                Vector2f screen = project(clip, point, gui[0], gui[1]);
                Vector2f recovered = projection.surface(screen.x, screen.y);
                assertNotNull(recovered);
                assertEquals(point.x, recovered.x, 0.002);
                assertEquals(point.y, recovered.y, 0.002);
            }
            Vector2f a = project(clip, new Vector2f(113, 87), gui[0], gui[1]);
            Vector2f b = project(clip, new Vector2f(145, 124), gui[0], gui[1]);
            Vector2f delta = projection.surface(b.x, b.y).sub(projection.surface(a.x, a.y));
            assertEquals(32, delta.x, 0.002);
            assertEquals(37, delta.y, 0.002);
            // Under perspective the projected centre is not the mean of the screen corners.
            Vector2f top = project(clip, new Vector2f(0, 0), gui[0], gui[1]);
            Vector2f bottom = project(clip, new Vector2f(WIDTH, HEIGHT), gui[0], gui[1]);
            Vector2f wrong = projection.surface((top.x + bottom.x) / 2, (top.y + bottom.y) / 2);
            assertTrue(Math.abs(wrong.x - WIDTH / 2f) > 5);
        }
    }

    @Test
    void glassCornersAndCardClicksKeepTheVertexPoseAcrossFlush() {
        float width = 10.55f * 1.35f / 16, height = 6.8f * 1.35f / 16, z = 0.005f;
        for (float fov : new float[] {50, 70, 90}) {
            Matrix4f camera =
                    new Matrix4f().perspective((float) Math.toRadians(fov), 16f / 9, 0.05f, 100);
            Matrix4f bone =
                    new Matrix4f().translate(0.1f, -0.12f, -1.4f).rotateXYZ(0.12f, -0.3f, 0.04f);
            Matrix4f rendered = new Matrix4f(camera).mul(bone);
            Matrix4f surface = TabletProjection.glassPose(bone, width, height, z);
            Matrix4f clip = new Matrix4f(camera).mul(surface);
            // Flushing may restore/reuse the mutable hand-pass matrices after vertex submission.
            camera.identity().setOrtho(0, 427, 240, 0, 1000, 21000);
            bone.identity();
            for (int[] gui : new int[][] {{1280, 720}, {640, 360}, {427, 240}}) {
                TabletProjection projection = TabletProjection.glass(clip, gui[0], gui[1]);
                assertNotNull(projection);
                for (Vector2f point :
                        new Vector2f[] {
                            new Vector2f(0, 0), new Vector2f(WIDTH, 0),
                            new Vector2f(WIDTH, HEIGHT), new Vector2f(0, HEIGHT),
                            new Vector2f(150, 200), new Vector2f(520, 330)
                        }) {
                    // Independent physical quad coordinates, with the model's flipped U and V.
                    Vector4f vertex =
                            rendered.transform(
                                    new Vector4f(
                                            width / 2 - point.x * width / WIDTH,
                                            -height / 2 + point.y * height / HEIGHT,
                                            z,
                                            1));
                    Vector2f mouse =
                            new Vector2f(
                                    (vertex.x / vertex.w + 1) * gui[0] / 2,
                                    (1 - vertex.y / vertex.w) * gui[1] / 2);
                    Vector2f recovered = projection.surface(mouse.x, mouse.y);
                    assertNotNull(recovered);
                    assertEquals(point.x, recovered.x, 0.002);
                    assertEquals(point.y, recovered.y, 0.002);
                    Vector4f submitted = clip.transform(new Vector4f(point.x, point.y, 0, 1));
                    assertEquals(vertex.x / vertex.w, submitted.x / submitted.w, 0.000001);
                    assertEquals(vertex.y / vertex.w, submitted.y / submitted.w, 0.000001);
                }
            }
        }
    }

    @Test
    void rejectsInvisibleAndDegenerateGlassAndNonFinitePointers() {
        assertNull(TabletProjection.glass(new Matrix4f().m00(0), 427, 240));
        assertNull(TabletProjection.glass(new Matrix4f().m33(-1), 427, 240));
        assertNull(TabletProjection.glass(new Matrix4f().m00(Float.NaN), 427, 240));
        TabletProjection projection = TabletProjection.flat(30, 45, 295, 190);
        assertNull(projection.surface(Double.NaN, 50));
        assertFalse(TabletProjection.contains(projection.surface(20, 50)));
        Vector2f centre = projection.surface(177.5, 140);
        assertTrue(TabletProjection.contains(centre));
        assertEquals(WIDTH / 2f, centre.x);
        assertEquals(HEIGHT / 2f, centre.y);
    }

    private static Vector2f project(Matrix4f clip, Vector2f point, int width, int height) {
        Vector4f p = clip.transform(new Vector4f(point.x, point.y, 0, 1));
        return new Vector2f((p.x / p.w + 1) * width / 2, (1 - p.y / p.w) * height / 2);
    }
}
