package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BodyPartHitboxSceneTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void reset() {
        BodyPartHitboxRenderer.resetForTests();
    }

    private static Player player(int id, Vec3 feet) {
        var p = mock(Player.class);
        when(p.getId()).thenReturn(id);
        when(p.getX()).thenReturn(feet.x);
        when(p.getY()).thenReturn(feet.y);
        when(p.getZ()).thenReturn(feet.z);
        when(p.level()).thenReturn(mock(Level.class));
        when(p.getBoundingBox())
                .thenReturn(
                        new AABB(
                                feet.x - .3,
                                feet.y,
                                feet.z - .3,
                                feet.x + .3,
                                feet.y + 1.8,
                                feet.z + .3));
        when(p.getViewVector(.5f)).thenReturn(new Vec3(0, 0, 1));
        return p;
    }

    /**
     * The native renderer's pose at {@code renderToBuffer}: the dispatcher translation (camera at
     * the feet, plus the renderer offset), then the 1.21.1 standing player chain.
     */
    private static RenderCapture render(Player p, Vec3 feet, Vec3 offset) {
        var root =
                LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64)
                        .bakeRoot();
        var model = new PlayerModel<Player>(root, false);
        var poses = new PoseStack();
        poses.translate(offset.x, offset.y, offset.z);
        var entry = new Matrix4f(poses.last().pose());
        poses.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
        poses.scale(-1, -1, 1);
        poses.translate(0, -1.501f, 0);
        return BodyPartHitboxRenderer.capture(
                p,
                model,
                entry,
                poses.last().pose(),
                RenderCapture.Pass.MAIN,
                1,
                feet,
                offset,
                feet);
    }

    @Test
    void f3ReadsPosedNativeModelAndDrivenFeetWithoutAServerBoneStream() {
        BodyPartHitboxRenderer.frameEntry();
        BodyPartHitboxRenderer.levelPass(true);
        var feet = new Vec3(10, 64, -3);
        var p = player(7, feet);
        render(p, feet, Vec3.ZERO);
        var vertex = mock(VertexConsumer.class, RETURNS_SELF);
        assertTrue(BodyPartHitboxRenderer.handle(p, new PoseStack(), vertex, .5f));
        var snapshot = BodyPartHitboxRenderer.debugSnapshot(7);
        assertEquals("rendered_entity", snapshot.get("source"));
        @SuppressWarnings("unchecked")
        var boxes = (java.util.Map<String, List<List<Double>>>) snapshot.get("boxes");
        assertEquals(6, boxes.size());
        for (var part : boxes.values()) assertEquals(1, part.size());
        // Pinned 1.21.1 wide inner head: 8px cube, root Y=1.501, no scale here.
        var head = boxes.get("head").getFirst();
        for (int i = 0; i < 8; i++) {
            assertEquals(.25, Math.abs(head.get(i * 3) - 10), 2e-6);
            assertEquals(.25, Math.abs(head.get(i * 3 + 2) + 3), 2e-6);
            assertTrue(
                    Math.abs(head.get(i * 3 + 1) - 65.501) < 1e-5
                            || Math.abs(head.get(i * 3 + 1) - 66.001) < 1e-5);
        }
    }

    @Test
    void crouchRenderOffsetMovesTheCapturedBoxesWithTheDrawnModel() {
        BodyPartHitboxRenderer.frameEntry();
        var feet = new Vec3(0, 64, 0);
        var p = player(8, feet);
        // Pinned 1.21.1 PlayerRenderer.getRenderOffset: (0, -0.125, 0) while crouching.
        var capture = render(p, feet, new Vec3(0, -.125, 0));
        assertTrue(capture.rebaseExact());
        var head = BodyPartHitboxRenderer.boxesFor(capture).getFirst().corners().getFirst();
        for (int i = 0; i < 8; i++)
            assertTrue(
                    Math.abs(head[i * 3 + 1] - 65.376) < 1e-5
                            || Math.abs(head[i * 3 + 1] - 65.876) < 1e-5);
    }
}
