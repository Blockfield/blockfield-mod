package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkshopControlsTest {
    @Test
    void defaultsDoNotStealCreativeVanillaKeysAndRareActionsAreUnbound() {
        var used = new HashSet<String>();
        Set<String> forbidden =
                Set.of(
                        "e",
                        "q",
                        "f",
                        "x",
                        "c",
                        "left.shift",
                        "left.control",
                        "t",
                        "tab",
                        "w",
                        "a",
                        "s",
                        "d",
                        "space",
                        "m",
                        "1",
                        "2",
                        "3",
                        "4",
                        "5",
                        "6",
                        "7",
                        "8",
                        "9");
        for (var binding : WorkshopControls.BINDINGS) {
            assertEquals(WorkshopControls.CATEGORY, binding.key().getCategory());
            String key = binding.workshop().getName();
            if (!binding.action().equals("rotate"))
                assertTrue(binding.key().getDefaultKey().getValue() == -1, binding.action());
            if (key.equals("key.keyboard.unknown")) continue;
            assertTrue(used.add(key), binding.action());
            assertFalse(forbidden.contains(key.replace("key.keyboard.", "")), binding.action());
            assertFalse(key.startsWith("key.mouse"));
        }
        assertEquals(
                "key.keyboard.z",
                WorkshopControls.workshopDefault("key.blockfield.workshop.radial").getName());
        assertNull(WorkshopControls.workshopDefault("key.blockfield.workshop.submit"));
        assertEquals("key.keyboard.r", WorkshopControls.key("rotate").getDefaultKey().getName());
        for (String rare : new String[] {"library", "ghost", "maps", "submit", "keys"})
            assertTrue(WorkshopControls.key(rare).isUnbound());
    }

    @Test
    void stageModeAndPanelRoutingIsExhaustive() {
        for (String stage :
                new String[] {
                    "idle",
                    "held",
                    "preview",
                    "planning",
                    "awaiting",
                    "applying",
                    "undoing",
                    "uploading",
                    "transcribing",
                    "complete",
                    "cancelled",
                    "error",
                    "disabled"
                })
            for (boolean selection : new boolean[] {false, true})
                for (boolean hand : new boolean[] {false, true})
                    for (boolean preview : new boolean[] {false, true})
                        for (boolean panel : new boolean[] {false, true}) {
                            assertEquals(
                                    "ai",
                                    WorkshopControls.route(
                                            "ai", stage, selection, hand, preview, panel));
                            assertEquals(
                                    "library",
                                    WorkshopControls.route(
                                            "library", stage, selection, hand, preview, panel));
                            for (var binding : WorkshopControls.BINDINGS)
                                assertNotNull(
                                        WorkshopControls.route(
                                                binding.action(),
                                                stage,
                                                selection,
                                                hand,
                                                preview,
                                                panel));
                            assertEquals(
                                    panel
                                            ? "blocked"
                                            : hand && !selection
                                                    ? "sketch:rotate"
                                                    : "workshop:tool:rotate",
                                    WorkshopControls.route(
                                            "rotate", stage, selection, hand, preview, panel));
                            // N is smart fill on the selection, so selection mode never blocks it;
                            // the renamed edge-to-edge copy keeps its old placed-sketch gate.
                            assertEquals(
                                    panel ? "blocked" : "fill",
                                    WorkshopControls.route(
                                            "extend", stage, selection, hand, preview, panel));
                            assertEquals(
                                    !panel && !selection && (preview || stage.equals("complete"))
                                            ? "sketch:chain"
                                            : "blocked",
                                    WorkshopControls.route(
                                            "chain", stage, selection, hand, preview, panel));
                            assertEquals(
                                    panel ? "blocked" : "selection",
                                    WorkshopControls.route(
                                            "selection", stage, selection, hand, preview, panel));
                            assertEquals(
                                    panel ? "blocked" : "workshop:tool:undo",
                                    WorkshopControls.route(
                                            "undo", stage, selection, hand, preview, panel));
                        }
    }

    @Test
    void fillHintIgnoresTheAlreadyGeneratedDraft() {
        assertEquals("", WorkshopAiClient.fillHint("замок", "замок"));
        assertEquals("", WorkshopAiClient.fillHint("  замок ", "замок"));
        assertEquals("в конце башня", WorkshopAiClient.fillHint("в конце башня", "замок"));
        assertEquals("", WorkshopAiClient.fillHint("", ""));
    }

    @Test
    void nKeepsItsDefaultAndTheCopyGetsKNotTheChestRigJ() {
        for (var binding : WorkshopControls.BINDINGS) {
            if (binding.action().equals("extend"))
                assertEquals(org.lwjgl.glfw.GLFW.GLFW_KEY_N, binding.workshop().getValue());
            if (binding.action().equals("chain"))
                assertEquals(org.lwjgl.glfw.GLFW.GLFW_KEY_K, binding.workshop().getValue());
        }
    }
}
