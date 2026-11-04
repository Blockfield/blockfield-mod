package com.lunares.blockfield.client.workshop;

import static com.lunares.blockfield.client.workshop.WorkshopAiGhostMesh.CLEAR;
import static com.lunares.blockfield.client.workshop.WorkshopAiGhostMesh.SKIP;
import static com.lunares.blockfield.client.workshop.WorkshopAiGhostMesh.SOLID;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkshopAiGhostMeshTest {
    private static final WorkshopAiGhostMesh.Occlusion EMPTY = (x, y, z) -> false;

    private static WorkshopBlueprint plan(String... operations) {
        return WorkshopBlueprint.parse(
                "{\"version\":1,\"operations\":[" + String.join(",", operations) + "]}", 0, 0, 0);
    }

    private static String box(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        return "{\"from\":[%d,%d,%d],\"to\":[%d,%d,%d],\"block\":\"%s\",\"hollow\":false}"
                .formatted(x0, y0, z0, x1, y1, z1, block);
    }

    @Test
    void bakeCompletesAcrossFramesWithoutExceedingCellBudgetOrRepeating() {
        var cells = new AtomicInteger();
        var loop = new WorkshopAiGhostMesh.BakeLoop(() -> cells.incrementAndGet() < 8720);
        int frames = 0;
        while (!loop.done() && frames++ < 100) {
            int before = cells.get();
            int consumed = loop.advance(256, Long.MAX_VALUE, () -> 0);
            assertTrue(consumed <= 256);
            assertEquals(consumed, cells.get() - before);
        }
        assertTrue(loop.done());
        assertEquals(8720, cells.get());
        assertEquals(0, loop.advance(256, Long.MAX_VALUE, () -> 0));
    }

    @Test
    void bakeYieldsAtDeadlineAndCancelledPlanNeverRunsAgain() {
        var time = new AtomicLong();
        var cells = new AtomicInteger();
        var oldPlan =
                new WorkshopAiGhostMesh.BakeLoop(
                        () -> {
                            cells.incrementAndGet();
                            time.addAndGet(2_000_000);
                            return true;
                        });
        assertEquals(3, oldPlan.advance(256, 6_000_000, time::get));
        oldPlan.cancel();
        assertEquals(0, oldPlan.advance(256, Long.MAX_VALUE, time::get));
        assertEquals(3, cells.get());
        var newPlan = new WorkshopAiGhostMesh.BakeLoop(() -> false);
        assertEquals(1, newPlan.advance(256, Long.MAX_VALUE, time::get));
        assertTrue(newPlan.done());
    }

    @Test
    void solidCubeShowsOnlyItsShellAndWorldNeighboursHideFaces() {
        var cube = plan(box(0, 0, 0, 2, 2, 2, "minecraft:stone"));
        byte[] kinds = {SOLID};
        assertEquals(54, WorkshopAiGhostMesh.faces(cube, kinds, EMPTY, 0, 0, 0, 1000).size());
        WorkshopAiGhostMesh.Occlusion wall = (x, y, z) -> x == -1 && y == 1 && z == 1;
        assertEquals(53, WorkshopAiGhostMesh.faces(cube, kinds, wall, 0, 0, 0, 1000).size());
    }

    @Test
    void eraseOnlyAndUnknownBlocksBakeNoTexturedFaces() {
        var erase = plan(box(0, 0, 0, 2, 2, 2, "minecraft:air"));
        assertEquals("minecraft:air", erase.blocks().getFirst());
        assertTrue(erase.erase(0));
        assertEquals(
                0,
                WorkshopAiGhostMesh.faces(erase, new byte[] {SKIP}, EMPTY, 0, 0, 0, 1000).size());

        // An id this client cannot resolve is skipped; the stone face toward it stays visible.
        var mixed =
                plan(box(0, 0, 0, 0, 0, 0, "minecraft:stone"), box(1, 0, 0, 1, 0, 0, "other:gone"));
        var faces =
                WorkshopAiGhostMesh.faces(mixed, new byte[] {SOLID, SKIP}, EMPTY, 0, 0, 0, 1000);
        assertEquals(6, faces.size());
        assertTrue(faces.stream().allMatch(face -> face.x() == 0 && face.block() == 0));
    }

    @Test
    void seeThroughBlocksCullOnlyAgainstThemselvesOrSolidNeighbours() {
        var wall =
                plan(
                        box(0, 0, 0, 0, 0, 0, "minecraft:stone"),
                        box(1, 0, 0, 2, 0, 0, "minecraft:glass"));
        var faces =
                WorkshopAiGhostMesh.faces(wall, new byte[] {SOLID, CLEAR}, EMPTY, 0, 0, 0, 1000);
        // Stone keeps its face toward glass; glass hides the faces toward stone and each other.
        assertEquals(6 + 4 + 5, faces.size());
    }

    @Test
    void quadCapKeepsFacesNearestToTheViewer() {
        var floor = plan(box(0, 0, 0, 63, 0, 63, "minecraft:stone"));
        var all = WorkshopAiGhostMesh.faces(floor, new byte[] {SOLID}, EMPTY, 40, 0, 40, 100_000);
        var capped = WorkshopAiGhostMesh.faces(floor, new byte[] {SOLID}, EMPTY, 40, 0, 40, 500);
        assertEquals(64 * 64 * 2 + 64 * 4, all.size());
        assertEquals(500, capped.size());
        assertEquals(all.subList(0, 500), capped);
        var first = capped.getFirst();
        assertTrue(Math.abs(first.x() - 40) <= 8 && Math.abs(first.z() - 40) <= 8);
        long farthest = 0;
        for (var face : capped) {
            long dx = face.x() - 40, dz = face.z() - 40;
            farthest = Math.max(farthest, dx * dx + dz * dz);
        }
        assertTrue(farthest < 20 * 20);
    }
}
