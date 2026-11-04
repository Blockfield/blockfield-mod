package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.BodyGeometry;
import com.lunares.blockfield.common.combat.BodyRig;
import com.lunares.blockfield.common.item.UniformItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class BodyGeometryNativeTest {
    /** Spec bone order: head, torso, left arm, right arm, left leg, right leg. */
    private static final String[] BONES = {
        "head", "body", "left_arm", "right_arm", "left_leg", "right_leg"
    };

    @Test
    void skinTablesMatchVanilla() {
        // Hit geometry is the six wide inner cubes for every player (owner decision 2026-10-13):
        // the oracle is the wide PlayerModel.createMesh(NONE, false) inner cubes only. Slim Arms
        // differ on the visible model (3 px) but take the same wide hit boxes.
        var root =
                LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64)
                        .bakeRoot();
        var vanilla = new TreeMap<String, float[]>();
        for (var entry :
                Map.ofEntries(
                                Map.entry("head", "skin:inner"),
                                Map.entry("body", "skin:inner"),
                                Map.entry("right_arm", "skin:inner"),
                                Map.entry("left_arm", "skin:inner"),
                                Map.entry("right_leg", "skin:inner"),
                                Map.entry("left_leg", "skin:inner"))
                        .entrySet()) {
            var cubes = new ArrayList<ModelPart.Cube>();
            root.getChild(entry.getKey())
                    .visit(new PoseStack(), (pose, path, index, cube) -> cubes.add(cube));
            assertEquals(1, cubes.size(), entry.getKey());
            // Cube.min/max exclude the deformation; the polygons carry it, so read the
            // rendered bounds the hit geometry must match.
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (var pos : polygonVertices(cubes.get(0))) {
                minX = Math.min(minX, pos.x());
                minY = Math.min(minY, pos.y());
                minZ = Math.min(minZ, pos.z());
                maxX = Math.max(maxX, pos.x());
                maxY = Math.max(maxY, pos.y());
                maxZ = Math.max(maxZ, pos.z());
            }
            vanilla.put(
                    entry.getValue() + "@" + entry.getKey(),
                    new float[] {minX, minY, minZ, maxX - minX, maxY - minY, maxZ - minZ});
        }
        var mine = new TreeMap<String, float[]>();
        for (var box : BodyGeometry.skin()) {
            String part =
                    switch (box.bone()) {
                        case BodyGeometry.HEAD -> "head";
                        case BodyGeometry.TORSO -> "body";
                        case BodyGeometry.RIGHT_ARM -> "right_arm";
                        case BodyGeometry.LEFT_ARM -> "left_arm";
                        case BodyGeometry.RIGHT_LEG -> "right_leg";
                        case BodyGeometry.LEFT_LEG -> "left_leg";
                        default -> throw new IllegalArgumentException(box.id());
                    };
            mine.put(
                    box.id() + "@" + part,
                    new float[] {box.x(), box.y(), box.z(), box.w(), box.h(), box.d()});
        }
        assertEquals(vanilla.keySet(), mine.keySet());
        for (var key : vanilla.keySet()) assertArrayEquals(vanilla.get(key), mine.get(key), 0, key);
        assertEquals(6, BodyGeometry.skin().size());
    }

    @Test
    void slimByDefaultMatchesVanilla() {
        var random = new java.util.Random(20261011);
        for (int i = 0; i < 10_000; i++) {
            var id = new java.util.UUID(random.nextLong(), random.nextLong());
            assertEquals(
                    DefaultPlayerSkin.get(id).model() == PlayerSkin.Model.SLIM,
                    BodyGeometry.slimByDefault(id),
                    id.toString());
        }
    }

    @Test
    void digestIsPinned() {
        assertEquals(
                "95989a54f4a729c94a3ef7e9bcb51545fa13f48aa205b8a357292467a773d712",
                BodyGeometry.DIGEST);
    }

    private static final class Recorder implements VertexConsumer {
        final List<Vector3f> vertices = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices.add(new Vector3f(x, y, z));
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
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

    /**
     * Native oracle: random bones copied into the wide player model (hit geometry) and the uniform
     * model (rendering), rendered into a capturing consumer; every emitted box must match {@link
     * BodyRig} corners within 1e-5 m with an equal id set. Slim players take the same wide hit
     * boxes, so the skin oracle is wide-only.
     */
    @Test
    void nativeOracleMatchesRig() {
        var kits = new ArrayList<>(UniformAtlasBaker.KITS);
        kits.add("none");
        var random = new java.util.Random(20261011);
        int poseCount = 0;
        for (String kit : kits) {
            boolean none = kit.equals("none");
            UniformItem.Role role = null;
            UniformItem.Side side = null;
            if (!none) {
                role =
                        UniformItem.Role.valueOf(
                                kit.substring(0, kit.lastIndexOf('_'))
                                        .toUpperCase(java.util.Locale.ROOT));
                side =
                        UniformItem.Side.valueOf(
                                kit.substring(kit.lastIndexOf('_') + 1)
                                        .toUpperCase(java.util.Locale.ROOT));
            }
            var source =
                    new PlayerModel<>(
                            LayerDefinition.create(
                                            PlayerModel.createMesh(CubeDeformation.NONE, false),
                                            64,
                                            64)
                                    .bakeRoot(),
                            false);
            UniformModel uniform = none ? null : new UniformModel(role, side);
            Map<String, BodyGeometry.Box> expected = new HashMap<>();
            for (var box : BodyGeometry.skin()) expected.put(key(box), box);
            if (!none) for (var box : BodyGeometry.uniform(role, side)) expected.put(box.id(), box);
            for (int pose = 0; pose < 25; pose++) {
                poseCount++;
                ModelPart[] sourceParts = {
                    source.head, source.body, source.leftArm,
                    source.rightArm, source.leftLeg, source.rightLeg
                };
                float[] params = new float[36];
                for (int i = 0; i < 6; i++) {
                    float px = (random.nextFloat() * 2 - 1) * 16;
                    float py = (random.nextFloat() * 2 - 1) * 16;
                    float pz = (random.nextFloat() * 2 - 1) * 16;
                    float xr = (random.nextFloat() * 2 - 1) * (float) Math.PI;
                    float yr = (random.nextFloat() * 2 - 1) * (float) Math.PI;
                    float zr = (random.nextFloat() * 2 - 1) * (float) Math.PI;
                    sourceParts[i].setPos(px, py, pz);
                    sourceParts[i].setRotation(xr, yr, zr);
                    params[i * 6] = px;
                    params[i * 6 + 1] = py;
                    params[i * 6 + 2] = pz;
                    params[i * 6 + 3] = xr;
                    params[i * 6 + 4] = yr;
                    params[i * 6 + 5] = zr;
                }
                // Outer layers follow their bones, as PlayerModel/HumanoidModel.setupAnim
                // does.
                source.hat.copyFrom(source.head);
                source.jacket.copyFrom(source.body);
                source.leftSleeve.copyFrom(source.leftArm);
                source.rightSleeve.copyFrom(source.rightArm);
                source.leftPants.copyFrom(source.leftLeg);
                source.rightPants.copyFrom(source.rightLeg);
                var stack = new PoseStack();
                stack.mulPose(
                        new Quaternionf()
                                .rotationZYX(
                                        (random.nextFloat() * 2 - 1) * (float) Math.PI,
                                        (random.nextFloat() * 2 - 1) * (float) Math.PI,
                                        (random.nextFloat() * 2 - 1) * (float) Math.PI));
                stack.translate(
                        random.nextFloat() * 2 - 1,
                        random.nextFloat() * 2 - 1,
                        random.nextFloat() * 2 - 1);
                var root = new Matrix4f(stack.last().pose());
                var recorder = new Recorder();
                var ids = new ArrayList<String>();
                if (none) {
                    ModelPart[] skinParts = {
                        source.head,
                        source.body,
                        source.leftArm,
                        source.rightArm,
                        source.leftLeg,
                        source.rightLeg
                    };
                    int[] skinBones = {0, 1, 2, 3, 4, 5};
                    for (int i = 0; i < skinParts.length; i++) {
                        int before = recorder.vertices.size();
                        skinParts[i].render(stack, recorder, 0, 0);
                        assertEquals(24, recorder.vertices.size() - before, kit + " skin:inner");
                        ids.add("skin:inner#" + skinBones[i]);
                    }
                } else {
                    source.copyPropertiesTo(uniform);
                    ModelPart[] skinInner = {
                        source.head, source.body, source.leftArm,
                        source.rightArm, source.leftLeg, source.rightLeg
                    };
                    ModelPart[] uniformParts = {
                        uniform.head, uniform.body, uniform.leftArm,
                        uniform.rightArm, uniform.leftLeg, uniform.rightLeg
                    };
                    for (int i = 0; i < 6; i++) {
                        int before = recorder.vertices.size();
                        skinInner[i].render(stack, recorder, 0, 0);
                        assertEquals(
                                24, recorder.vertices.size() - before, kit + " inner " + BONES[i]);
                        ids.add("skin:inner#" + i);
                        before = recorder.vertices.size();
                        uniformParts[i].render(stack, recorder, 0, 0);
                        int emitted = (recorder.vertices.size() - before) / 24;
                        var paths = visitPaths(uniformParts[i]);
                        assertEquals(paths.size(), emitted, kit + " " + BONES[i]);
                        for (String path : paths) ids.add("uniform:" + BONES[i] + path);
                        ModelPart part = uniformParts[i];
                        params[i * 6] = part.x;
                        params[i * 6 + 1] = part.y;
                        params[i * 6 + 2] = part.z;
                        params[i * 6 + 3] = part.xRot;
                        params[i * 6 + 4] = part.yRot;
                        params[i * 6 + 5] = part.zRot;
                    }
                }
                assertEquals(expected.keySet(), new java.util.HashSet<>(ids), kit);
                var boneMats = new Matrix4f[6];
                for (int i = 0; i < 6; i++) boneMats[i] = new Matrix4f();
                BodyRig.bones(root, params, boneMats);
                int cursor = 0;
                for (String id : ids) {
                    BodyGeometry.Box box = expected.get(id);
                    assertNotNull(box, id);
                    int bone =
                            id.startsWith("uniform:")
                                    ? boneOf(id.substring("uniform:".length()))
                                    : Integer.parseInt(id.substring(id.indexOf('#') + 1));
                    var want = new Vector3f[8];
                    for (int i = 0; i < 8; i++) want[i] = new Vector3f();
                    BodyRig.corners(boneMats[bone], box, want);
                    var got = new ArrayList<Vector3f>();
                    for (int v = 0; v < 24; v++) got.add(recorder.vertices.get(cursor + v));
                    cursor += 24;
                    assertCorners(want, got, 1e-5f, kit + " " + id);
                }
                assertEquals(cursor, recorder.vertices.size());
            }
        }
        assertEquals(kits.size() * 25, poseCount);
    }

    private static String key(BodyGeometry.Box box) {
        return box.id().startsWith("uniform:") ? box.id() : box.id() + "#" + box.bone();
    }

    private static int boneOf(String path) {
        String top = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        return switch (top) {
            case "head" -> BodyGeometry.HEAD;
            case "body" -> BodyGeometry.TORSO;
            case "left_arm" -> BodyGeometry.LEFT_ARM;
            case "right_arm" -> BodyGeometry.RIGHT_ARM;
            case "left_leg" -> BodyGeometry.LEFT_LEG;
            default -> BodyGeometry.RIGHT_LEG;
        };
    }

    private static List<String> visitPaths(ModelPart part) {
        var paths = new ArrayList<String>();
        part.visit(
                new PoseStack(),
                (pose, path, index, cube) -> {
                    if (index == 0) paths.add(path);
                });
        return paths;
    }

    private static void assertCorners(
            Vector3f[] want, List<Vector3f> got24, float eps, String what) {
        var wantSorted = new ArrayList<>(List.of(want));
        var gotDistinct = new ArrayList<Vector3f>();
        outer:
        for (Vector3f v : got24) {
            for (Vector3f seen : gotDistinct) if (seen.distance(v) < 1e-9f) continue outer;
            gotDistinct.add(new Vector3f(v));
        }
        assertEquals(8, gotDistinct.size(), what);
        wantSorted.sort(BodyGeometryNativeTest::compareVec);
        gotDistinct.sort(BodyGeometryNativeTest::compareVec);
        float max = 0;
        for (int i = 0; i < 8; i++) {
            float d = wantSorted.get(i).distance(gotDistinct.get(i));
            if (d > max) max = d;
        }
        assertTrue(max <= eps, what + " corner error " + max);
    }

    /** Rendered vertex positions of the cube (polygons carry the deformation). */
    private static List<Vector3f> polygonVertices(ModelPart.Cube cube) {
        try {
            var polygons = (Object[]) polygonsField(ModelPart.Cube.class, "polygons").get(cube);
            var out = new ArrayList<Vector3f>();
            for (Object polygon : polygons) {
                var vertices =
                        (Object[]) polygonsField(polygon.getClass(), "vertices").get(polygon);
                for (Object vertex : vertices)
                    out.add((Vector3f) polygonsField(vertex.getClass(), "pos").get(vertex));
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.lang.reflect.Field polygonsField(Class<?> owner, String name)
            throws NoSuchFieldException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static int compareVec(Vector3f a, Vector3f b) {
        if (a.x != b.x) return Float.compare(a.x, b.x);
        if (a.y != b.y) return Float.compare(a.y, b.y);
        return Float.compare(a.z, b.z);
    }
}
