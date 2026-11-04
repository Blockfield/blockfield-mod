package com.lunares.blockfield.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class WorkshopKeyBindingsTest {
    @AfterEach
    void restore() {
        WorkshopKeyBindings.leave();
    }

    private static KeyMapping mapping(String name, int key) {
        return new KeyMapping(name, key, "key.categories.misc");
    }

    @Test
    void standardBuilderControlsRestoreAndNeverReplaceACustomBinding() {
        KeyMapping inventory = mapping("key.inventory", 73);
        KeyMapping sneak = mapping("key.sneak", 341);
        KeyMapping sprint = mapping("key.sprint", 340);
        KeyMapping custom = mapping("key.drop", 85);
        KeyMapping parkour = mapping("key.parcool.Vault", 32);
        WorkshopKeyBindings.enter(new KeyMapping[] {inventory, sneak, sprint, custom, parkour});
        assertEquals("key.keyboard.e", inventory.saveString());
        assertEquals("key.keyboard.left.shift", sneak.saveString());
        assertEquals("key.keyboard.left.control", sprint.saveString());
        assertEquals("key.keyboard.u", custom.saveString());
        assertEquals(InputConstants.UNKNOWN.getName(), parkour.saveString());
        WorkshopKeyBindings.leave();
        assertEquals("key.keyboard.i", inventory.saveString());
        assertEquals("key.keyboard.left.control", sneak.saveString());
        assertEquals("key.keyboard.left.shift", sprint.saveString());
        assertEquals("key.keyboard.u", custom.saveString());
        assertEquals("key.keyboard.space", parkour.saveString());
    }

    @Test
    void optionsSerializationUsesOriginalBindingsAndResumesEvenWhenSavingFails() {
        KeyMapping inventory = mapping("key.inventory", 73);
        WorkshopKeyBindings.enter(new KeyMapping[] {inventory});
        RuntimeException failure = new IllegalStateException("fixture save failure");
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                WorkshopKeyBindings.withPersistentBindings(
                                        () -> {
                                            assertEquals("key.keyboard.i", inventory.saveString());
                                            throw failure;
                                        })));
        assertEquals("key.keyboard.e", inventory.saveString());
    }

    @Test
    void changesMadeByThePlayerInTheWorkshopSurviveSaveAndDeparture() {
        KeyMapping inventory = mapping("key.inventory", 73);
        WorkshopKeyBindings.enter(new KeyMapping[] {inventory});
        inventory.setKey(InputConstants.Type.KEYSYM.getOrCreate(85));
        WorkshopKeyBindings.withPersistentBindings(
                () -> assertEquals("key.keyboard.u", inventory.saveString()));
        WorkshopKeyBindings.leave();
        assertEquals("key.keyboard.u", inventory.saveString());
    }

    @Test
    void nativeTaczReloadAndPackZAreDisabledOnlyWhileWorkshopIsActive() {
        var reload = mapping("key.tacz.reload.desc", GLFW.GLFW_KEY_R);
        var crawl = mapping("key.parcool.Crawl", GLFW.GLFW_KEY_Z);
        var custom = mapping("key.tacz.zoom.desc", GLFW.GLFW_KEY_U);
        WorkshopKeyBindings.enter(new KeyMapping[] {reload, crawl, custom});
        assertTrue(reload.isUnbound());
        assertTrue(crawl.isUnbound());
        assertEquals("key.keyboard.u", custom.saveString());
        WorkshopKeyBindings.leave();
        assertEquals("key.keyboard.r", reload.saveString());
        assertEquals("key.keyboard.z", crawl.saveString());
    }

    @Test
    void workshopActionKeysAreBoundOnlyInsideTheWorkshopAndCustomKeysWin() {
        var radial = com.lunares.blockfield.client.workshop.WorkshopControls.key("radial");
        var ai = com.lunares.blockfield.client.workshop.WorkshopControls.key("ai");
        var submit = com.lunares.blockfield.client.workshop.WorkshopControls.key("submit");
        try {
            assertTrue(radial.isUnbound());
            ai.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K));
            WorkshopKeyBindings.enter(new KeyMapping[] {radial, ai, submit});
            assertEquals("key.keyboard.z", radial.saveString());
            assertEquals("key.keyboard.k", ai.saveString());
            assertTrue(submit.isUnbound());
            WorkshopKeyBindings.withPersistentBindings(() -> assertTrue(radial.isUnbound()));
            WorkshopKeyBindings.leave();
            assertTrue(radial.isUnbound());
            assertEquals("key.keyboard.k", ai.saveString());
        } finally {
            ai.setKey(InputConstants.UNKNOWN);
        }
    }

    @Test
    void sketchRotateKeyDefaultsToRInTheBlockfieldCategory() {
        assertEquals(
                "key.blockfield.workshop_sketch.rotate",
                com.lunares.blockfield.client.workshop.WorkshopToolsClient.SKETCH_ROTATE_KEY
                        .getName());
        assertEquals(
                "key.categories.blockfield.workshop",
                com.lunares.blockfield.client.workshop.WorkshopToolsClient.SKETCH_ROTATE_KEY
                        .getCategory());
        assertEquals(
                InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_R),
                com.lunares.blockfield.client.workshop.WorkshopToolsClient.SKETCH_ROTATE_KEY
                        .getDefaultKey());
    }

    @Test
    void workshopRemapLeavesAiKeysAloneAndNullsUntouchedDismount() {
        KeyMapping rotate =
                new KeyMapping(
                        "key.blockfield.workshop_sketch.rotate",
                        GLFW.GLFW_KEY_R,
                        "key.categories.blockfield");
        KeyMapping dismount = mapping("key.superbwarfare.dismount", GLFW.GLFW_KEY_G);
        WorkshopKeyBindings.enter(new KeyMapping[] {rotate, dismount});
        assertEquals("key.keyboard.r", rotate.saveString());
        assertEquals(InputConstants.UNKNOWN.getName(), dismount.saveString());
        WorkshopKeyBindings.leave();
        assertEquals("key.keyboard.r", rotate.saveString());
        assertEquals("key.keyboard.g", dismount.saveString());
    }
}
