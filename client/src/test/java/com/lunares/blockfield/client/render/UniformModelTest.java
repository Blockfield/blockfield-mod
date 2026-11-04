package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.item.UniformItem;
import org.junit.jupiter.api.Test;

class UniformModelTest {
    @Test
    void everyKitHasItsOwnNonOverlappingIslandsInsideItsTexture() {
        for (var kit : UniformAtlasBaker.KITS) {
            var role =
                    UniformItem.Role.valueOf(
                            kit.substring(0, kit.lastIndexOf('_'))
                                    .toUpperCase(java.util.Locale.ROOT));
            var side =
                    UniformItem.Side.valueOf(
                            kit.substring(kit.lastIndexOf('_') + 1)
                                    .toUpperCase(java.util.Locale.ROOT));
            var layout = UniformModel.layout(kit);
            assertEquals(UniformModel.materials(role, side).keySet(), layout.uv().keySet(), kit);
            var used = new java.util.ArrayList<float[]>();
            UniformAtlasBaker.forEachCube(
                    new UniformModel(role, side),
                    (bone, path, pose, cube) -> {
                        int[] uv = layout.uv().get(path);
                        float w = cube.maxX - cube.minX,
                                h = cube.maxY - cube.minY,
                                d = cube.maxZ - cube.minZ;
                        float[] rect = {uv[0], uv[1], uv[0] + 2 * d + 2 * w, uv[1] + d + h};
                        assertTrue(rect[2] <= layout.width() && rect[3] <= layout.height(), path);
                        for (var other : used)
                            assertFalse(
                                    rect[0] < other[2]
                                            && other[0] < rect[2]
                                            && rect[1] < other[3]
                                            && other[1] < rect[3],
                                    path + " overlaps");
                        used.add(rect);
                    });
        }
    }

    @Test
    void outfitsBakeWithVisibleTeamMarkersAndTheirDistinctiveEquipment() {
        for (var kit : UniformAtlasBaker.KITS) {
            {
                var role =
                        UniformItem.Role.valueOf(
                                kit.substring(0, kit.lastIndexOf('_'))
                                        .toUpperCase(java.util.Locale.ROOT));
                var side =
                        UniformItem.Side.valueOf(
                                kit.substring(kit.lastIndexOf('_') + 1)
                                        .toUpperCase(java.util.Locale.ROOT));
                var model = new UniformModel(role, side);
                assertTrue(model.rightArm.hasChild("shoulder"));
                assertTrue(model.rightLeg.hasChild("team_patch_front"));
                assertTrue(model.head.hasChild("mask"));
                assertTrue(model.leftArm.hasChild("shoulder"));
                assertTrue(
                        model.body.hasChild(
                                role == UniformItem.Role.SNIPER ? "drag_bag" : "back_team"));
                assertTrue(model.rightLeg.hasChild("team_patch"));
                assertEquals(side == UniformItem.Side.BLUE, model.leftArm.hasChild("stripe_two"));
                assertEquals(
                        role == UniformItem.Role.ENGINEER, model.body.hasChild("wrench_handle"));
                assertEquals(role == UniformItem.Role.DRONE, model.body.hasChild("antenna_0"));
                assertEquals(role == UniformItem.Role.SNIPER, model.body.hasChild("bandolier"));
                if (role == UniformItem.Role.SNIPER) {
                    var strand = model.head.getChild("strand_side_0_0");
                    assertTrue(
                            Math.abs(strand.yRot) > 1,
                            "side strands must hang off the hood's side");
                    assertTrue(
                            model.head.getChild("scrim_top_0").xRot < -1.5F,
                            "crown tabs lie outward over the hood, not hanging");
                    assertTrue(
                            model.body.getChild("drag_bag").hasChild("bag_team"),
                            "team colour must show on the back");
                }
                assertEquals(role == UniformItem.Role.AIRBORNE, model.body.hasChild("harness_0"));
                for (var bone :
                        java.util.List.of(
                                model.head,
                                model.body,
                                model.leftArm,
                                model.rightArm,
                                model.leftLeg,
                                model.rightLeg)) {
                    bone.visit(
                            new com.mojang.blaze3d.vertex.PoseStack(),
                            (pose, path, index, cube) -> {
                                assertTrue(
                                        cube.maxX > cube.minX
                                                && cube.maxY > cube.minY
                                                && cube.maxZ > cube.minZ);
                                if (path.endsWith("/magazine_0")) {
                                    assertTrue(
                                            cube.maxZ - cube.minZ >= 2,
                                            "pouches need a visible side profile");
                                    assertTrue(
                                            cube.minZ <= -5.5F,
                                            "pouches must project beyond the chest plate");
                                }
                                if (path.endsWith("/knee")) assertTrue(cube.minZ <= -3);
                                if (path.endsWith("/goggle_frame_0"))
                                    assertTrue(cube.maxZ - cube.minZ >= 1.5F);
                            });
                }
            }
        }
    }

    @Test
    void goldenMeshIsUnchanged() throws Exception {
        String actual = goldenJson();
        String dump = System.getenv("BF_DUMP_GOLDEN");
        if (dump != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(dump), actual);
            return;
        }
        var url = UniformModelTest.class.getResource("/uniform-mesh-golden.json");
        assertNotNull(url, "missing golden file; rerun with BF_DUMP_GOLDEN set");
        String expected = java.nio.file.Files.readString(java.nio.file.Path.of(url.toURI()));
        assertEquals(expected, actual);
    }

    /**
     * Canonical mesh dump for all 15 kits: root bone poses, box emission order with materials,
     * every intermediate child pose and every cube, all keyed by part path. Children and boxes are
     * sorted (baked maps do not preserve insertion order); the materials list keeps the build
     * emission order.
     */
    static String goldenJson() {
        var bonesAll =
                java.util.List.of(
                        "head", "hat", "body", "right_arm", "left_arm", "right_leg", "left_leg");
        var out = new StringBuilder("{\"kits\":{");
        boolean firstKit = true;
        for (var kit : UniformAtlasBaker.KITS) {
            var role =
                    UniformItem.Role.valueOf(
                            kit.substring(0, kit.lastIndexOf('_'))
                                    .toUpperCase(java.util.Locale.ROOT));
            var side =
                    UniformItem.Side.valueOf(
                            kit.substring(kit.lastIndexOf('_') + 1)
                                    .toUpperCase(java.util.Locale.ROOT));
            var model = new UniformModel(role, side);
            var roots =
                    java.util.Map.of(
                            "head", model.head,
                            "hat", model.hat,
                            "body", model.body,
                            "right_arm", model.rightArm,
                            "left_arm", model.leftArm,
                            "right_leg", model.rightLeg,
                            "left_leg", model.leftLeg);
            var materials = UniformModel.materials(role, side);
            var cubes = new java.util.TreeMap<String, float[]>();
            UniformAtlasBaker.forEachCube(
                    model,
                    (bone, path, pose, cube) ->
                            cubes.put(
                                    path,
                                    new float[] {
                                        cube.minX,
                                        cube.minY,
                                        cube.minZ,
                                        cube.maxX - cube.minX,
                                        cube.maxY - cube.minY,
                                        cube.maxZ - cube.minZ
                                    }));
            assertEquals(materials.keySet(), cubes.keySet(), kit);
            var children = new java.util.TreeMap<String, float[]>();
            for (var path : cubes.keySet()) {
                var segs = path.split("/");
                var part = roots.get(segs[0]);
                assertNotNull(part, path);
                String prefix = segs[0];
                for (int i = 1; i < segs.length - 1; i++) {
                    prefix += "/" + segs[i];
                    assertTrue(part.hasChild(segs[i]), prefix);
                    part = part.getChild(segs[i]);
                    float[] pose = {part.x, part.y, part.z, part.xRot, part.yRot, part.zRot};
                    if (children.putIfAbsent(prefix, pose) != null)
                        assertArrayEquals(children.get(prefix), pose, 0, prefix);
                }
                var box = part.getChild(segs[segs.length - 1]);
                assertArrayEquals(
                        new float[6],
                        new float[] {box.x, box.y, box.z, box.xRot, box.yRot, box.zRot},
                        0,
                        path);
            }
            if (!firstKit) out.append(',');
            firstKit = false;
            out.append('"').append(kit).append("\":{\"bones\":{");
            boolean first = true;
            for (var bone : bonesAll) {
                if (!first) out.append(',');
                first = false;
                var part = roots.get(bone);
                out.append('"')
                        .append(bone)
                        .append("\":[")
                        .append(joint(part.x, part.y, part.z, part.xRot, part.yRot, part.zRot))
                        .append(']');
            }
            out.append("},\"materials\":[");
            first = true;
            for (var entry : materials.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append("[\"")
                        .append(entry.getKey())
                        .append("\",")
                        .append(entry.getValue())
                        .append(']');
            }
            out.append("],\"children\":{");
            first = true;
            for (var entry : children.entrySet()) {
                if (!first) out.append(',');
                first = false;
                var p = entry.getValue();
                out.append('"')
                        .append(entry.getKey())
                        .append("\":[")
                        .append(joint(p[0], p[1], p[2], p[3], p[4], p[5]))
                        .append(']');
            }
            out.append("},\"boxes\":{");
            first = true;
            for (var entry : cubes.entrySet()) {
                if (!first) out.append(',');
                first = false;
                var c = entry.getValue();
                out.append('"')
                        .append(entry.getKey())
                        .append("\":{\"cube\":[")
                        .append(joint(c[0], c[1], c[2], c[3], c[4], c[5]))
                        .append("],\"mat\":")
                        .append(materials.get(entry.getKey()))
                        .append('}');
            }
            out.append("}}");
        }
        return out.append("}}\n").toString();
    }

    private static String joint(float... values) {
        var out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(Float.toString(values[i]));
        }
        return out.toString();
    }
}
