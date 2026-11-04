package com.lunares.blockfield.client.gui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class ClassSelectionLayoutTest {
    @Test
    void longVariantsWrapInsteadOfGrowingTheRow() {
        List<List<Integer>> lines = ClassSelectionScreen.wrapVariants(List.of(100, 100, 100), 210);
        assertEquals(2, lines.size());
        assertEquals(List.of(100, 100), lines.get(0));
        assertEquals(List.of(100), lines.get(1));
    }

    @Test
    void shortVariantsStayOnOneLine() {
        List<List<Integer>> lines = ClassSelectionScreen.wrapVariants(List.of(60, 60, 60), 300);
        assertEquals(1, lines.size());
        assertEquals(3, lines.get(0).size());
    }

    @Test
    void sevenShortRowsFitWithoutPages() {
        // 691x400 — обычный список из 7 классов помещается целиком.
        Map<String, Integer> rowH = new HashMap<>();
        for (int i = 0; i < 7; i++) rowH.put("c" + i, 36);
        List<String> ids = rowH.keySet().stream().sorted().toList();
        List<List<String>> pages = ClassSelectionScreen.splitPages(ids, rowH, 400 - 56);
        assertEquals(1, pages.size());
        assertEquals(7, pages.get(0).size());
    }

    @Test
    void twelveRowsNeedTwoPages() {
        Map<String, Integer> rowH = new HashMap<>();
        for (int i = 0; i < 12; i++) rowH.put("c" + i, 36);
        List<String> ids = rowH.keySet().stream().sorted().toList();
        List<List<String>> pages = ClassSelectionScreen.splitPages(ids, rowH, 400 - 56);
        assertTrue(pages.size() >= 2);
        int total = pages.stream().mapToInt(List::size).sum();
        assertEquals(12, total);
    }

    @Test
    void oversizedClassRowsReserveThePagerAndMatchActions() {
        int maxLines = ClassSelectionScreen.variantLinesPerRow(400 - 56 - 20 - 8);
        assertEquals(12, maxLines);
        int rowHeight = maxLines * 20 + 16;
        assertTrue(60 + rowHeight <= 400 - 56 - 20 - 8);
    }

    @Test
    void tabCyclesForwardAndBack() {
        List<String> order = List.of("a:0", "b:1", "page:next");
        assertEquals("a:0", ClassSelectionScreen.cycleFocus(order, null, true));
        assertEquals("b:1", ClassSelectionScreen.cycleFocus(order, "a:0", true));
        assertEquals("a:0", ClassSelectionScreen.cycleFocus(order, "b:1", false));
        assertEquals("page:next", ClassSelectionScreen.cycleFocus(order, "a:0", false));
    }

    @Test
    void nullClassEntryHasNoVariants() {
        assertTrue(ClassSelectionScreen.variantNames(null).isEmpty());
    }

    @Test
    void wrongTypedDeploymentCostReadsAsFree() {
        com.google.gson.JsonObject classes = new com.google.gson.JsonObject();
        com.google.gson.JsonObject kit = new com.google.gson.JsonObject();
        kit.addProperty("deploymentCost", "expensive");
        classes.add("assault", kit);
        assertFalse(ClassSelectionScreen.hasPaidClasses(classes));
    }
}
