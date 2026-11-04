package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class WorkshopRadialScreenTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static WorkshopRadialScreen screen(Minecraft mc) throws Exception {
        var constructor = WorkshopRadialScreen.class.getDeclaredConstructor(boolean.class);
        constructor.setAccessible(true);
        var screen = constructor.newInstance(false);
        var field = Screen.class.getDeclaredField("minecraft");
        field.setAccessible(true);
        field.set(screen, mc);
        return screen;
    }

    @Test
    void escapeAndDeadZoneCloseWithoutSendingAndDisabledEntriesCannotRun() throws Exception {
        var mc = mock(Minecraft.class);
        var screen = screen(mc);
        assertTrue(screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        verify(mc).setScreen(null);
        reset(mc);
        assertTrue(screen.mouseClicked(0, 0, 0));
        verify(mc).setScreen(null);
        reset(mc);
        JsonArray entries = new JsonArray();
        JsonObject disabled = new JsonObject();
        disabled.addProperty("label", "No access");
        disabled.addProperty("action", "workshop:hub");
        disabled.addProperty("enabled", false);
        entries.add(disabled);
        var field = WorkshopRadialScreen.class.getDeclaredField("entries");
        field.setAccessible(true);
        field.set(screen, entries);
        var activate = WorkshopRadialScreen.class.getDeclaredMethod("activate", int.class);
        activate.setAccessible(true);
        try (var networking = mockStatic(ClientPlayNetworking.class)) {
            activate.invoke(screen, 0);
            verifyNoInteractions(mc);
            networking.verifyNoInteractions();
        }
    }
}
