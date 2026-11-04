package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.workshop.WorkshopBlockStates;
import java.util.List;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorkshopShapedPreviewTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void mixedPreviewHasTheIndependentGoldenTextureBeforeAndAfterPlacement() {
        String json =
                "{\"version\":3,\"seed\":17,\"operations\":[{\"type\":\"cuboid\",\"from\":[0,0,0],\"to\":[7,0,7],\"block\":{\"mix\":[[\"minecraft:stone_bricks\",6],[\"minecraft:cracked_stone_bricks\",2],[\"minecraft:mossy_stone_bricks\",1]]},\"hollow\":false}]}";
        String oracle = "1020022202000000021001000010010010000000001000000011100111000200";
        var entries =
                List.of(
                        "minecraft:stone_bricks",
                        "minecraft:cracked_stone_bricks",
                        "minecraft:mossy_stone_bricks");
        for (int turn = 0; turn < 4; turn++)
            for (boolean mirror : List.of(false, true)) {
                var preview = WorkshopBlueprint.parse(json, 0, 0, 0, 8, 2, 8, turn, mirror);
                for (int x = 0; x < 8; x++)
                    for (int z = 0; z < 8; z++) {
                        int[] p = WorkshopSketchPlacement.mapCell(x, 0, z, 8, 8, turn, mirror);
                        assertEquals(
                                entries.get(oracle.charAt(x * 8 + z) - '0'),
                                preview.blocks().get(preview.block(p[0], p[1], p[2])));
                    }
            }
    }

    @Test
    void heldAndPreviewStatesFollowVanillaMirrorRepeatAndRotation() {
        for (String id :
                List.of(
                        "quartz_stairs[facing=west,half=top]",
                        "oak_slab[type=top]",
                        "stone_brick_wall[north=tall]",
                        "oak_fence[east=true]",
                        "glass_pane[south=true]",
                        "iron_bars[west=true]",
                        "oak_trapdoor[facing=south,half=top]",
                        "oak_log[axis=x]",
                        "chain[axis=z]",
                        "lantern[hanging=true]")) {
            String state = "minecraft:" + id;
            String json =
                    "{\"version\":3,\"operations\":[{\"type\":\"mirror\",\"axes\":\"xz\",\"operations\":[{\"type\":\"repeat\",\"count\":2,\"offset\":[0,1,0],\"operations\":[{\"type\":\"cuboid\",\"from\":[0,0,0],\"to\":[0,0,0],\"block\":\""
                            + state
                            + "\",\"hollow\":false}]}]}]}";
            for (int turn = 0; turn < 4; turn++)
                for (boolean playerMirror : List.of(false, true)) {
                    var preview =
                            WorkshopBlueprint.parse(json, 0, 0, 0, 4, 4, 4, turn, playerMirror);
                    assertEquals(8, preview.touched());
                    for (int x : new int[] {0, 3})
                        for (int z : new int[] {0, 3})
                            for (int y : new int[] {0, 1}) {
                                var expected = WorkshopBlockStates.parse(state);
                                if (x == 3) expected = expected.mirror(Mirror.FRONT_BACK);
                                if (z == 3) expected = expected.mirror(Mirror.LEFT_RIGHT);
                                if (playerMirror) expected = expected.mirror(Mirror.FRONT_BACK);
                                expected = expected.rotate(Rotation.values()[turn]);
                                int[] p =
                                        WorkshopSketchPlacement.mapCell(
                                                x, y, z, 4, 4, turn, playerMirror);
                                assertEquals(
                                        expected,
                                        WorkshopBlockStates.parse(
                                                preview.blocks()
                                                        .get(preview.block(p[0], p[1], p[2]))));
                            }
                }
        }
    }

    @Test
    void partialBlocksRetainTheirInternalFacesBesideTheSameState() {
        var preview =
                WorkshopBlueprint.parse(
                        "{\"version\":3,\"operations\":[{\"type\":\"cuboid\",\"from\":[0,0,0],\"to\":[1,0,0],\"block\":\"minecraft:quartz_slab[type=bottom]\",\"hollow\":false}]}",
                        0,
                        0,
                        0);
        assertEquals(
                12,
                WorkshopAiGhostMesh.faces(
                                preview,
                                new byte[] {WorkshopAiGhostMesh.PARTIAL},
                                (x, y, z) -> false,
                                0,
                                0,
                                0,
                                100)
                        .size());
    }
}
