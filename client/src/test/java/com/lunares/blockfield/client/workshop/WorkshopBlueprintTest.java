package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkshopBlueprintTest {
    private static String box(int side, boolean hollow, String block) {
        return box(0, 0, 0, side - 1, side - 1, side - 1, hollow, block);
    }

    private static String box(
            int x0, int y0, int z0, int x1, int y1, int z1, boolean hollow, String block) {
        return "{\"from\":["
                + x0
                + ","
                + y0
                + ","
                + z0
                + "],\"to\":["
                + x1
                + ","
                + y1
                + ","
                + z1
                + "],\"block\":\""
                + block
                + "\",\"hollow\":"
                + hollow
                + "}";
    }

    @Test
    void versionThreePreviewShowsRoundVolumeCurvedWaterAndRepeatedMotifs() {
        String json =
                """
            {"version":3,"operations":[
              {"type":"ellipsoid","from":[0,0,0],"to":[4,4,4],"block":"minecraft:stone","hollow":false},
              {"type":"tube","path":[[8,8,8],[12,8,8],[12,12,12]],"curve":"polyline","radiusStart":2,"radiusEnd":1,"block":"minecraft:water","hollow":false},
              {"type":"repeat","count":3,"offset":[4,0,0],"operations":[
                {"type":"cuboid","from":[1,0,16],"to":[2,0,17],"block":"minecraft:stone","hollow":false}]}]}
            """;
        var blueprint = WorkshopBlueprint.parse(json, 8, 8, 8);
        assertEquals(-1, blueprint.block(0, 0, 0));
        assertTrue(blueprint.block(2, 2, 2) >= 0);
        assertEquals("minecraft:water", blueprint.blocks().get(blueprint.block(8, 10, 8)));
        assertEquals("minecraft:water", blueprint.blocks().get(blueprint.block(12, 12, 12)));
        assertTrue(blueprint.ghosts().stream().anyMatch(WorkshopBlueprint.Cell::water));
        for (int x : new int[] {1, 5, 9}) assertTrue(blueprint.block(x, 0, 16) >= 0);
        assertEquals(3, blueprint.boxes().size());
        assertEquals(blueprint, WorkshopBlueprint.parse(json, 8, 8, 8));
    }

    @Test
    void twentyFloorHighriseRetainsHighGeometryWithinTheVisualCap() {
        StringBuilder operations =
                new StringBuilder(
                        String.join(
                                ",",
                                box(0, 0, 0, 0, 79, 15, false, "minecraft:stone"),
                                box(15, 0, 0, 15, 79, 15, false, "minecraft:stone"),
                                box(0, 0, 0, 15, 79, 0, false, "minecraft:stone"),
                                box(0, 0, 15, 15, 79, 15, false, "minecraft:stone")));
        for (int y = 0; y < 80; y += 4) {
            operations.append(',').append(box(0, y, 0, 15, y, 15, false, "minecraft:stone"));
        }
        var blueprint = WorkshopBlueprint.parse(plan(operations.toString()), 8, 64, 8);
        assertEquals(8_720, blueprint.touched());
        assertEquals(24, blueprint.boxes().size());
        assertEquals(80, blueprint.boxes().getFirst().y1());
        assertEquals(512, blueprint.ghosts().size());
        assertEquals(new WorkshopBlueprint.Cell(8, 64, 8, false), blueprint.ghosts().getFirst());
        assertTrue(blueprint.ghosts().stream().anyMatch(cell -> cell.y() > 64));
        assertTrue(blueprint.ghosts().stream().allMatch(cell -> cell.y() < 80));
    }

    @Test
    void sparseFarCornerAndFullBudgetAtTheAxisBoundaryAreAccepted() {
        var sparse =
                WorkshopBlueprint.parse(
                        plan(box(127, 127, 127, 127, 127, 127, false, "minecraft:stone")),
                        Integer.MAX_VALUE,
                        Integer.MIN_VALUE,
                        Integer.MAX_VALUE);
        assertEquals(1, sparse.touched());
        assertEquals(new WorkshopBlueprint.Cell(127, 127, 127, false), sparse.ghosts().getFirst());
        assertEquals(
                new WorkshopBlueprint.Box(127, 127, 127, 128, 128, 128, false),
                sparse.boxes().getFirst());

        String fullBudget = box(96, 96, 96, 127, 127, 127, false, "minecraft:stone");
        var solid = WorkshopBlueprint.parse(plan(fullBudget), 127, 127, 127);
        assertEquals(32_768, solid.touched());
        assertEquals(512, solid.ghosts().size());
        assertEquals(new WorkshopBlueprint.Cell(127, 127, 127, false), solid.ghosts().getFirst());
        var overlapping =
                WorkshopBlueprint.parse(
                        plan(
                                fullBudget
                                        + ","
                                        + box(
                                                127,
                                                127,
                                                127,
                                                127,
                                                127,
                                                127,
                                                false,
                                                "minecraft:air")),
                        127,
                        127,
                        127);
        assertEquals(32768, overlapping.touched());
        assertTrue(overlapping.erase(overlapping.block(127, 127, 127)));
    }

    @Test
    void nearestSelectionUsesGeometricOrderRegardlessOfSparseInsertionOrder() {
        String lower = box(0, 32, 0, 15, 32, 15, false, "minecraft:stone");
        String middle = box(0, 64, 0, 15, 64, 15, false, "minecraft:stone");
        String upper = box(0, 96, 0, 15, 96, 15, false, "minecraft:stone");
        var forward = WorkshopBlueprint.parse(plan(lower + "," + middle + "," + upper), 0, 64, 0);
        var reverse = WorkshopBlueprint.parse(plan(upper + "," + middle + "," + lower), 0, 64, 0);
        var expected = new ArrayList<WorkshopBlueprint.Cell>();
        for (int y : new int[] {32, 64, 96}) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    expected.add(new WorkshopBlueprint.Cell(x, y, z, false));
                }
            }
        }
        expected.sort(
                Comparator.comparingLong(
                                (WorkshopBlueprint.Cell cell) ->
                                        (long) cell.x() * cell.x()
                                                + (long) (cell.y() - 64) * (cell.y() - 64)
                                                + (long) cell.z() * cell.z())
                        .thenComparingInt(WorkshopBlueprint.Cell::y)
                        .thenComparingInt(WorkshopBlueprint.Cell::x)
                        .thenComparingInt(WorkshopBlueprint.Cell::z));
        assertEquals(768, forward.touched());
        assertEquals(expected.subList(0, 512), forward.ghosts());
        assertEquals(forward.ghosts(), reverse.ghosts());
    }

    private static String plan(String operations) {
        return "{\"version\":1,\"operations\":[" + operations + "]}";
    }

    @Test
    void hollowCuboidsAndLaterAirWritesMatchTheFinalPlan() {
        var blueprint =
                WorkshopBlueprint.parse(
                        plan(
                                box(3, true, "minecraft:stone")
                                        + ","
                                        + box(1, false, "minecraft:air")),
                        0,
                        0,
                        0);
        assertEquals(26, blueprint.touched());
        assertEquals(26, blueprint.ghosts().size());
        assertEquals(1, blueprint.ghosts().stream().filter(WorkshopBlueprint.Cell::erase).count());
        assertFalse(
                blueprint.ghosts().stream()
                        .anyMatch(cell -> cell.x() == 1 && cell.y() == 1 && cell.z() == 1));
    }

    @Test
    void largePlansKeepOnlyBoundedNearestExposedCellsAndAreDeterministic() {
        String json = plan(box(32, false, "minecraft:stone"));
        var first = WorkshopBlueprint.parse(json, 0, 0, 0);
        var second = WorkshopBlueprint.parse(json, 0, 0, 0);
        assertEquals(32_768, first.touched());
        assertEquals(512, first.ghosts().size());
        assertEquals(first, second);
        for (var cell : first.ghosts())
            assertTrue(
                    cell.x() == 0
                            || cell.y() == 0
                            || cell.z() == 0
                            || cell.x() == 31
                            || cell.y() == 31
                            || cell.z() == 31);
        assertEquals(new WorkshopBlueprint.Cell(0, 0, 0, false), first.ghosts().getFirst());
        assertEquals(
                32768,
                WorkshopBlueprint.parse(
                                plan(
                                        box(32, false, "minecraft:stone")
                                                + ","
                                                + box(1, false, "minecraft:stone")),
                                0,
                                0,
                                0)
                        .touched());
    }

    @Test
    void rejectsUnknownFieldsUnboundedShapesAndCoercedTypes() {
        String valid = plan(box(1, false, "minecraft:stone"));
        for (String invalid :
                new String[] {
                    valid.replace("\"version\":1", "\"version\":1,\"command\":\"op\""),
                    valid.replace("\"hollow\":false", "\"hollow\":\"false\""),
                    valid.replace("[0,0,0]", "[0.5,0,0]"),
                    valid.replace("[0,0,0]", "[\"0\",0,0]"),
                    valid.replace("[0,0,0]", "[true,0,0]"),
                    valid.replace("[0,0,0]", "[0,0]"),
                    valid.replace("\"version\":1", "\"version\":\"1\""),
                    plan(box(128, 0, 0, 128, 0, 0, false, "minecraft:stone")),
                    plan(box(0, 128, 0, 0, 128, 0, false, "minecraft:stone")),
                    plan(box(0, 0, 128, 0, 0, 128, false, "minecraft:stone")),
                    plan(box(-1, 0, 0, 0, 0, 0, false, "minecraft:stone")),
                    plan(box(0, 0, 0, Integer.MAX_VALUE, 0, 0, false, "minecraft:stone")),
                    plan(box(128, true, "minecraft:stone")),
                    plan(
                            (box(1, false, "minecraft:stone") + ",").repeat(128)
                                    + box(1, false, "minecraft:stone")),
                    plan(""),
                    plan(box(33, false, "minecraft:stone")),
                    " ".repeat(65_537)
                })
            assertThrows(RuntimeException.class, () -> WorkshopBlueprint.parse(invalid, 0, 0, 0));
    }

    @Test
    void cellsCarryTheFinalBlockOfEveryOverlappingOperation() {
        var blueprint =
                WorkshopBlueprint.parse(
                        plan(
                                box(2, false, "minecraft:stone")
                                        + ","
                                        + box(1, 0, 0, 1, 1, 1, false, "minecraft:glass")),
                        0,
                        0,
                        0);
        assertEquals(List.of("minecraft:stone", "minecraft:glass"), blueprint.blocks());
        assertEquals(1, blueprint.block(1, 0, 0));
        assertEquals(0, blueprint.block(0, 1, 1));
        assertEquals(-1, blueprint.block(2, 0, 0));
        assertEquals(-1, blueprint.block(-1, 0, 0));
        assertFalse(blueprint.erase(0));
    }

    @Test
    void versionTwoMatchFollowsServerSequentialSemantics() {
        String v1 = plan(box(3, true, "minecraft:stone") + "," + box(1, false, "minecraft:air"));
        String v2 = v1.replace("\"version\":1", "\"version\":2");
        var expected = WorkshopBlueprint.parse(v1, 0, 0, 0);
        // Default air matches resolve from the empty world exactly like the server snapshot.
        assertEquals(expected, WorkshopBlueprint.parse(v2, 0, 0, 0));
        assertEquals(expected, WorkshopBlueprint.parse(withMatch(v2, "\"air\""), 0, 0, 0));
        assertEquals(expected, WorkshopBlueprint.parse(withMatch(v2, "\"any\""), 0, 0, 0));
        // A failing block-id match drops the cell like the build would, instead of overwriting.
        var failed = WorkshopBlueprint.parse(withMatch(v2, "\"minecraft:grass_block\""), 0, 0, 0);
        assertEquals(-1, failed.block(1, 0, 0));
        assertTrue(failed.block(0, 0, 0) >= 0);
        assertTrue(failed.erase(failed.block(0, 0, 0)));
        for (String invalid :
                new String[] {
                    withMatch(v1, "\"any\""),
                    withMatch(v1.replace("\"version\":1", "\"version\":3"), "\"any\""),
                    withMatch(v2, "\"some\""),
                    withMatch(v2, "true"),
                    withMatch(v2, "\"#minecraft:logs\"")
                })
            assertThrows(RuntimeException.class, () -> WorkshopBlueprint.parse(invalid, 0, 0, 0));
    }

    @Test
    void failedMatchKeepsEarlierPlannedValueLikeTheServerSnapshot() {
        // Cross-side with WorkshopAiPlan: stone with the default match, then glass matching
        // dirt at the same cell, must project stone from the empty world.
        String json =
                """
            {"version":3,"operations":[
              {"type":"cuboid","from":[0,0,0],"to":[0,0,0],"block":"minecraft:stone","hollow":false},
              {"type":"cuboid","from":[0,0,0],"to":[0,0,0],"block":"minecraft:glass","hollow":false,"match":"minecraft:dirt"}]}
            """;
        var blueprint = WorkshopBlueprint.parse(json, 0, 0, 0);
        assertEquals("minecraft:stone", blueprint.blocks().get(blueprint.block(0, 0, 0)));
        // A failed air cut leaves no cell, and a successful replacement shows the later block.
        String cut =
                """
            {"version":2,"operations":[
              {"from":[0,0,0],"to":[0,0,0],"block":"minecraft:air","hollow":false,"match":"minecraft:dirt"}]}
            """;
        var empty = WorkshopBlueprint.parse(cut, 0, 0, 0);
        assertEquals(-1, empty.block(0, 0, 0));
        assertEquals(0, empty.touched());
        String replace =
                """
            {"version":2,"operations":[
              {"from":[0,0,0],"to":[0,0,0],"block":"minecraft:stone","hollow":false,"match":"any"},
              {"from":[0,0,0],"to":[0,0,0],"block":"minecraft:glass","hollow":false,"match":"minecraft:stone"}]}
            """;
        var replaced = WorkshopBlueprint.parse(replace, 0, 0, 0);
        assertEquals("minecraft:glass", replaced.blocks().get(replaced.block(0, 0, 0)));
    }

    private static String withMatch(String plan, String match) {
        return plan.replace("\"hollow\":true}", "\"hollow\":true,\"match\":" + match + "}");
    }
}
