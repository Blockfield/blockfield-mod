package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.common.combat.BodyGeometry;
import com.lunares.blockfield.common.combat.BodyRig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR4 §8.3 capture contract: the six {@code BodyGeometry} boxes through {@code BodyRig} from the
 * feet-relative root and the posed parts, double origin outside every float matrix, per-frame MAIN
 * validity ({@code not_rendered} after a skipped main draw), shadows never replacing MAIN, and F3+B
 * drawing exactly the current capture. Geometry truth against the server is the transformed
 * mixed-renderer oracle, not this test.
 */
class BodyPartHitboxRendererTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Entry-to-model chain of an unscaled standing player facing yaw 30 (LivingEntityRenderer). */
    private static Matrix4f chain(Matrix4f entry) {
        return entry.rotateY((float) Math.toRadians(180 - 30))
                .scale(-1, -1, 1)
                .scale(.9375f)
                .translate(0, -1.501f, 0);
    }

    private static PlayerModel<Player> model(float swing) {
        var root =
                LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64)
                        .bakeRoot();
        var model = new PlayerModel<Player>(root, false);
        model.rightArm.xRot = swing;
        model.leftLeg.xRot = -swing * .7f;
        model.head.yRot = swing * .3f;
        return model;
    }

    private static <T extends LivingEntity> T entity(Class<T> type, int id) {
        T entity = mock(type);
        when(entity.getId()).thenReturn(id);
        return entity;
    }

    /** One MAIN capture with the camera at the feet: the entry is the renderer offset only. */
    private static RenderCapture capture(LivingEntity entity, Vec3 origin, float swing) {
        return capture(entity, origin, swing, RenderCapture.Pass.MAIN, origin);
    }

    private static RenderCapture capture(
            LivingEntity entity, Vec3 origin, float swing, RenderCapture.Pass pass, Vec3 camera) {
        var offset = Vec3.ZERO;
        var entry =
                new Matrix4f()
                        .translation(
                                (float) (origin.x - camera.x + offset.x),
                                (float) (origin.y - camera.y + offset.y),
                                (float) (origin.z - camera.z + offset.z));
        var pose = chain(new Matrix4f(entry));
        return BodyPartHitboxRenderer.capture(
                entity, model(swing), entry, pose, pass, 1, origin, offset, camera);
    }

    /** Records the abstract calls every default {@code addVertex}/{@code setColor} funnels into. */
    private static final class RecordingConsumer implements VertexConsumer {
        final List<float[]> vertices = new ArrayList<>();
        final List<int[]> colors = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices.add(new float[] {x, y, z});
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            colors.add(new int[] {r, g, b, a});
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    @BeforeEach
    void frame() {
        BodyPartHitboxRenderer.resetForTests();
        BodyPartHitboxRenderer.frameEntry();
    }

    @AfterEach
    void reset() {
        BodyPartHitboxRenderer.resetForTests();
    }

    @Test
    void capturedCornersAreTheSixInnerCubesOfTheFeetRelativeRoot() {
        var origin = new Vec3(10.25, 64, -3.5);
        var capture = capture(entity(Player.class, 7), origin, .8f);
        var expectedRoot = chain(new Matrix4f());
        assertArrayEquals(expectedRoot.get(new float[16]), capture.root(), "root at camera=feet");
        var bones = new Matrix4f[6];
        for (int i = 0; i < 6; i++) bones[i] = new Matrix4f();
        BodyRig.bones(expectedRoot, capture.parts(), bones);
        var corners = new Vector3f[8];
        for (int i = 0; i < 8; i++) corners[i] = new Vector3f();
        var boxes = BodyGeometry.skin();
        assertEquals(6, boxes.size());
        assertEquals(6, capture.boxIds().size());
        float[] local = capture.corners();
        double[] world = capture.worldCorners();
        for (int b = 0; b < boxes.size(); b++) {
            BodyRig.corners(bones[boxes.get(b).bone()], boxes.get(b), corners);
            for (int c = 0; c < 8; c++) {
                assertEquals(corners[c].x, local[b * 24 + c * 3], 0);
                assertEquals(corners[c].y, local[b * 24 + c * 3 + 1], 0);
                assertEquals(corners[c].z, local[b * 24 + c * 3 + 2], 0);
                assertEquals(origin.x + (double) corners[c].x, world[b * 24 + c * 3], 0);
            }
        }
        assertTrue(capture.rebaseExact());
    }

    @Test
    void localCaptureIsIndependentOfTheWorldOriginUpToTheWorldBorder() {
        var near = capture(entity(Player.class, 7), new Vec3(0, 64, 0), .4f);
        var far = new Vec3(-29_999_984.5, 64.125, 29_999_983.75);
        var border = capture(entity(Player.class, 7), far, .4f);
        // A float world matrix would lose ~2 m here; local floats stay bit-identical.
        assertArrayEquals(near.corners(), border.corners());
        assertArrayEquals(near.root(), border.root());
        double[] world = border.worldCorners();
        float[] local = border.corners();
        for (int i = 0; i < local.length; i++)
            assertEquals(border.origin()[i % 3] + (double) local[i], world[i], 0);
    }

    @Test
    void aDistantCameraKeepsTheRotationBitsAndOnlyRebasesTranslation() {
        var origin = new Vec3(100.5, 70, -40.25);
        var atFeet = capture(entity(Player.class, 7), origin, .6f);
        var away =
                capture(
                        entity(Player.class, 7),
                        origin,
                        .6f,
                        RenderCapture.Pass.MAIN,
                        new Vec3(90.125, 68, -33));
        assertTrue(away.rebaseExact());
        float[] a = atFeet.root(), b = away.root();
        for (int i = 0; i < 12; i++) assertEquals(a[i], b[i], 0, "rotation/scale element " + i);
        for (int i = 12; i < 15; i++) assertEquals(a[i], b[i], 1e-5, "translation " + i);
    }

    @Test
    void aSkippedMainDrawIsNotRenderedInsteadOfTheLastFrame() {
        capture(entity(Player.class, 7), new Vec3(0, 64, 0), .5f);
        assertNotNull(BodyPartHitboxRenderer.mainCapture(7));
        assertNull(BodyPartHitboxRenderer.statusFor(7));
        BodyPartHitboxRenderer.frameEntry();
        assertNull(BodyPartHitboxRenderer.mainCapture(7), "culled frame reuses no older capture");
        assertEquals(BodyPartHitboxRenderer.NO_FRAME_TEXT, BodyPartHitboxRenderer.statusFor(7));
        assertNull(BodyPartHitboxRenderer.debugSnapshot(7), "legacy reader also expires");
        var snapshot = BodyPartHitboxRenderer.captureSnapshot(7);
        assertEquals(BodyPartHitboxRenderer.NOT_RENDERED, snapshot.get("status"));
        assertEquals(BodyPartHitboxRenderer.renderFrame(), snapshot.get("renderFrame"));
    }

    @Test
    void shadowAndOtherPassesNeverReplaceTheMainCapture() {
        var main = capture(entity(Player.class, 7), new Vec3(0, 64, 0), .5f);
        var shadow =
                capture(
                        entity(Player.class, 7),
                        new Vec3(0, 64, 0),
                        1.5f,
                        RenderCapture.Pass.SHADOW,
                        new Vec3(0, 64, 0));
        capture(
                entity(Player.class, 7),
                new Vec3(0, 64, 0),
                -1f,
                RenderCapture.Pass.OTHER,
                new Vec3(0, 64, 0));
        assertSame(main, BodyPartHitboxRenderer.mainCapture(7));
        assertSame(shadow, BodyPartHitboxRenderer.shadowCapture(7));
        assertFalse(java.util.Arrays.equals(main.corners(), shadow.corners()));
        assertEquals("MAIN", BodyPartHitboxRenderer.captureSnapshot(7).get("pass"));
    }

    @Test
    void everyPartHasItsOwnColour() {
        var seen = new HashSet<String>();
        for (float[] color : BodyPartHitboxRenderer.PART_COLORS)
            assertTrue(seen.add(color[0] + "," + color[1] + "," + color[2]));
        assertEquals(6, BodyPartHitboxRenderer.PART_COLORS.length);
    }

    @Test
    void playersSuppressTheVanillaAabb() {
        assertTrue(BodyPartHitboxRenderer.isPlayerOverlay(Player.class));
        assertTrue(
                BodyPartHitboxRenderer.isPlayerOverlay(
                        net.minecraft.client.player.AbstractClientPlayer.class));
        assertFalse(BodyPartHitboxRenderer.isPlayerOverlay(Entity.class));
        assertFalse(BodyPartHitboxRenderer.isPlayerOverlay(ItemEntity.class));
        assertFalse(BodyPartHitboxRenderer.isPlayerOverlay(Husk.class));
    }

    @Test
    void capturedNamedHusksTakeTheOverlayWhileOthersKeepTheAabb() {
        Husk named = entity(Husk.class, 7);
        when(named.hasCustomName()).thenReturn(true);
        capture(named, new Vec3(0, 64, 0), .5f);
        assertTrue(BodyPartHitboxRenderer.isRangeOverlay(named));
        Husk nameTagged = entity(Husk.class, 8);
        when(nameTagged.hasCustomName()).thenReturn(true);
        assertFalse(BodyPartHitboxRenderer.isRangeOverlay(nameTagged));
        Husk plain = entity(Husk.class, 7);
        when(plain.hasCustomName()).thenReturn(false);
        assertFalse(BodyPartHitboxRenderer.isRangeOverlay(plain));
        assertFalse(BodyPartHitboxRenderer.isRangeOverlay(mock(ItemEntity.class)));
        BodyPartHitboxRenderer.frameEntry();
        assertFalse(BodyPartHitboxRenderer.isRangeOverlay(named), "no capture this frame");
    }

    @Test
    void forgetPrunesEveryPass() {
        capture(entity(Player.class, 7), new Vec3(0, 64, 0), .5f);
        BodyPartHitboxRenderer.forget(7);
        assertEquals(BodyPartHitboxRenderer.NO_FRAME_TEXT, BodyPartHitboxRenderer.statusFor(7));
        assertNull(BodyPartHitboxRenderer.debugSnapshot(7));
    }

    @Test
    void f3bDrawsTheMainCaptureBoxEdgesEyeLineAndViewVector() {
        var player = entity(Player.class, 7);
        var origin = new Vec3(10, 64, -3);
        var capture = capture(player, origin, .5f);
        when(player.getEyeHeight()).thenReturn(1.62f);
        when(player.getViewVector(.5f)).thenReturn(new Vec3(0, 0, 1));
        when(player.getBoundingBox()).thenReturn(new AABB(9.7, 64, -3.3, 10.3, 65.8, -2.7));
        when(player.getX()).thenReturn(10.0);
        when(player.getY()).thenReturn(64.0);
        when(player.getZ()).thenReturn(-3.0);
        var level = mock(Level.class);
        when(player.level()).thenReturn(level);
        when(level.getGameTime()).thenReturn(5L);
        var consumer = new RecordingConsumer();
        BodyPartHitboxRenderer.levelPass(true);
        assertTrue(BodyPartHitboxRenderer.handle(player, new PoseStack(), consumer, .5f));
        // 12 edges of 2 vertices per box, the 12-edge eye line box, and the 2-vertex view vector.
        assertEquals(6 * 24 + 24 + 2, consumer.vertices.size());
        var drawn = new HashSet<String>();
        for (float[] vertex : consumer.vertices) drawn.add(key(vertex));
        float[] local = capture.corners();
        for (int i = 0; i < local.length; i += 3)
            assertTrue(drawn.contains(key(new float[] {local[i], local[i + 1], local[i + 2]})));
        assertArrayEquals(new int[] {255, 255, 0, 255}, consumer.colors.get(0));

        Map<String, Object> snapshot = BodyPartHitboxRenderer.debugSnapshot(7);
        assertEquals(true, snapshot.get("drawn"));
        assertEquals(5L, snapshot.get("tick"));
        assertEquals("six", snapshot.get("shapeKey"));
        @SuppressWarnings("unchecked")
        var boxes = (Map<String, List<List<Double>>>) snapshot.get("boxes");
        assertEquals(
                Set.of("head", "torso", "left_arm", "right_arm", "left_leg", "right_leg"),
                boxes.keySet());
        double[] world = capture.worldCorners();
        var head = boxes.get("head").getFirst();
        for (int i = 0; i < 24; i++) assertEquals(world[i], head.get(i), 0);
        @SuppressWarnings("unchecked")
        var described = (Map<String, Object>) snapshot.get("capture");
        assertEquals("MAIN", described.get("pass"));
        assertEquals(capture.invocation(), described.get("invocation"));
        assertEquals(List.of(10.0, 64.0, -3.0), described.get("origin"));
        BodyPartHitboxRenderer.frameEntry();
        assertNull(
                BodyPartHitboxRenderer.debugSnapshot(7),
                "DRAWN cannot survive a culled next frame");
        assertEquals(
                BodyPartHitboxRenderer.NOT_RENDERED,
                BodyPartHitboxRenderer.captureSnapshot(7).get("status"));
    }

    @Test
    void outsideTheMainPassF3bDrawsNothingButStillHidesTheAabb() {
        var player = entity(Player.class, 7);
        capture(player, new Vec3(0, 64, 0), .5f);
        var consumer = new RecordingConsumer();
        assertTrue(BodyPartHitboxRenderer.handle(player, new PoseStack(), consumer, .5f));
        assertTrue(consumer.vertices.isEmpty());
    }

    @Test
    void f3bEyeMarkerSitsOnTheAnimatedHead() {
        var player = entity(Player.class, 7);
        var origin = new Vec3(10, 64, -3);
        var capture = capture(player, origin, .5f);
        when(player.getEyePosition()).thenReturn(new Vec3(10, 65.62, -3));
        when(player.position()).thenReturn(origin);
        Vec3 rel = BodyPartHitboxRenderer.headAnchorFeetRelative(player, capture);
        assertEquals(0, rel.x, 0.02);
        assertEquals(1.62, rel.y, 0.02);
        assertEquals(0, rel.z, 0.02);
    }

    @Test
    void anUndrawnCaptureIsStillTheCurrentEvidence() {
        capture(entity(Player.class, 9), new Vec3(1, 2, 3), .5f);
        var snapshot = BodyPartHitboxRenderer.debugSnapshot(9);
        assertEquals(false, snapshot.get("drawn"));
        assertEquals("rendered", snapshot.get("status"));
    }

    @Test
    void theCaptureCarriesThePresentationIdentityOfItsReceipt() throws Exception {
        var digest = new com.lunares.blockfield.common.combat.AnimationResources.Digest(1, 2, 3, 4);
        var identity =
                new com.lunares.blockfield.common.combat.AnimationState.Identity(
                        11, 12, 13, 7, 0, 14, 15, 16, 17, 18, 19, digest, digest);
        var before =
                new com.lunares.blockfield.client.combat.SnapshotTransform(
                        100, 0, 0, 64, 0, List.of(), 15, 16, 17, identity, 0, null, null);
        var after =
                new com.lunares.blockfield.client.combat.SnapshotTransform(
                        101, 0, 0, 64, 0, List.of(), 15, 16, 17, identity, 0, null, null);
        var sample =
                new com.lunares.blockfield.client.combat.SnapshotResolver.Sample(
                        before, after, .25, "interpolated", 0, 64, 0, List.of(0d, 0d, 0d, 0d));
        var row =
                new com.lunares.blockfield.common.combat.AnimationStartReport.Victim(
                        7, 15, 16, 17, 14, 900, 905, digest, List.of(), List.of(), digest);
        var a = new com.lunares.blockfield.common.combat.AnimationTypes.Time(102, 0x8000);
        var receipt =
                new com.lunares.blockfield.client.combat.CombatRenderReceipt(
                        12,
                        103,
                        100,
                        0x4000,
                        Map.of(7, sample),
                        new com.lunares.blockfield.client.combat.CombatRenderReceipt.Exact(
                                11,
                                13,
                                40,
                                41,
                                a,
                                18,
                                19,
                                digest,
                                new com.lunares.blockfield.common.combat.AnimationStartReport(
                                        List.of(row)),
                                null));
        var field =
                com.lunares.blockfield.client.combat.ClientCombatScene.class.getDeclaredField(
                        "receipt");
        field.setAccessible(true);
        field.set(null, receipt);
        try {
            var id = capture(entity(Player.class, 7), new Vec3(0, 64, 0), .5f).identity();
            assertEquals(11, id.connectionId());
            assertEquals(12, id.observerStreamEpoch());
            assertEquals(13, id.sceneGeneration());
            assertEquals(40, id.frameId());
            assertEquals(41, id.revision());
            assertEquals(103, id.high());
            assertEquals(100, id.rTick());
            assertEquals(0x4000, id.rAlpha());
            assertEquals(102, id.aTick());
            assertEquals(0x8000, id.aAlpha());
            assertEquals(15, id.targetEpoch());
            assertEquals(14, id.subscriptionGeneration());
            assertEquals(18, id.resourceGeneration());
            assertEquals(19, id.policyGeneration());
            assertEquals(100, id.placementBefore());
            assertEquals(101, id.placementAfter());
            assertEquals(900, id.seedRef());
            assertEquals(905, id.appliedEventSequence());
            assertEquals(64, id.presentationDigest().length());
            assertFalse(id.driven(), "a mock remote has no installed presentation");
        } finally {
            field.set(null, null);
        }
    }

    private static String key(float[] vertex) {
        return vertex[0] + "," + vertex[1] + "," + vertex[2];
    }
}
