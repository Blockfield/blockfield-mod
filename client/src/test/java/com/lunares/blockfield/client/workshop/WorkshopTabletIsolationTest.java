package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.gui.ClientEventHandler;
import com.lunares.blockfield.client.gui.tablet.Tablet;
import com.lunares.blockfield.client.render.WeaponHud;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.junit.jupiter.api.Test;

class WorkshopTabletIsolationTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    // The game path opens through TabletAnimations, whose ModItems init needs live registries;
    // the workshop path must return before touching it at all.
    @Test
    void tabletKeyIsDrainedWithoutOpeningInWorkshop() throws Exception {
        var mc = mock(Minecraft.class);
        mc.player = mock(LocalPlayer.class);
        var key = Tablet.class.getDeclaredField("key");
        key.setAccessible(true);
        Object original = key.get(null);
        var clicks = KeyMapping.class.getDeclaredField("clickCount");
        clicks.setAccessible(true);
        var tick = Tablet.class.getDeclaredMethod("onTick", Minecraft.class);
        tick.setAccessible(true);
        try (var hud = mockStatic(WeaponHud.class)) {
            key.set(null, ClientEventHandler.TABLET_KEY);
            Tablet.onLogout();
            hud.when(WeaponHud::inWorkshop).thenReturn(true);
            clicks.setInt(ClientEventHandler.TABLET_KEY, 2);
            tick.invoke(null, mc);
            assertFalse(Tablet.isOpen());
            assertFalse(Tablet.isShown());
            assertEquals(0, clicks.getInt(ClientEventHandler.TABLET_KEY));
            verify(mc, never()).setScreen(any());
        } finally {
            Tablet.onLogout();
            key.set(null, original);
            clicks.setInt(ClientEventHandler.TABLET_KEY, 0);
        }
    }
}
