package com.lunares.blockfield.client.render;

import com.lunares.blockfield.common.item.UniformItem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Writes baked quads for dev/uniforms/preview.py; runs only with BF_UNIFORM_DUMP=<dir>. */
class UniformPreviewDump {
    @Test
    void dump() throws Exception {
        String dir = System.getenv("BF_UNIFORM_DUMP");
        Assumptions.assumeTrue(dir != null);
        var polygons = ModelPart.Cube.class.getDeclaredField("polygons");
        polygons.setAccessible(true);
        var skin = new StringBuilder();
        var player = PlayerModel.createMesh(CubeDeformation.NONE, false);
        var baked =
                net.minecraft.client.model.geom.builders.LayerDefinition.create(player, 64, 64)
                        .bakeRoot();
        for (var name : List.of("head", "body", "left_arm", "right_arm", "left_leg", "right_leg"))
            write(baked.getChild(name), polygons, skin);
        Files.writeString(Path.of(dir, "skin.quads"), skin);
        for (var kit : UniformAtlasBaker.KITS) {
            var model =
                    new UniformModel(
                            UniformItem.Role.valueOf(
                                    kit.substring(0, kit.lastIndexOf('_'))
                                            .toUpperCase(Locale.ROOT)),
                            UniformItem.Side.valueOf(
                                    kit.substring(kit.lastIndexOf('_') + 1)
                                            .toUpperCase(Locale.ROOT)));
            var out = new StringBuilder();
            for (var bone :
                    List.of(
                            model.head,
                            model.body,
                            model.leftArm,
                            model.rightArm,
                            model.leftLeg,
                            model.rightLeg)) write(bone, polygons, out);
            Files.writeString(Path.of(dir, kit + ".quads"), out);
        }
    }

    private static void write(ModelPart bone, java.lang.reflect.Field polygons, StringBuilder out) {
        bone.visit(
                new PoseStack(),
                (pose, path, index, cube) -> {
                    try {
                        for (var polygon : (ModelPart.Polygon[]) polygons.get(cube)) {
                            for (var vertex : polygon.vertices) {
                                var p =
                                        pose.pose()
                                                .transformPosition(
                                                        vertex.pos.x() / 16,
                                                        vertex.pos.y() / 16,
                                                        vertex.pos.z() / 16,
                                                        new Vector3f());
                                out.append(
                                        String.format(
                                                Locale.ROOT,
                                                "%.5f %.5f %.5f %.6f %.6f ",
                                                p.x,
                                                p.y,
                                                p.z,
                                                vertex.u,
                                                vertex.v));
                            }
                            out.append('\n');
                        }
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                });
    }
}
