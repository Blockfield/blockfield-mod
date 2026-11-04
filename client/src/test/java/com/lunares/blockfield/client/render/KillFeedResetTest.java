package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.atsuishio.superbwarfare.event.KillMessageHandler;
import com.atsuishio.superbwarfare.tools.LivingKillRecord;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.Test;

class KillFeedResetTest {
    @Test
    void preparationClearsOldKillsButDelayedActiveHudPreservesNewKills() throws Exception {
        var loader = mock(FabricLoader.class);
        when(loader.isModLoaded("superbwarfare")).thenReturn(true);
        try (var loaders = mockStatic(FabricLoader.class)) {
            loaders.when(FabricLoader::getInstance).thenReturn(loader);
            var queue = KillMessageHandler.INSTANCE.getQUEUE();
            try {
                HudRenderer.updateHUDData("{\"tabActive\":true}");
                queue.offer(mock(LivingKillRecord.class));
                HudRenderer.updateHUDData("{\"tabActive\":true,\"rT\":490,\"bT\":500}");
                assertEquals(1, queue.size());
                HudRenderer.updateHUDData("{\"tabActive\":false,\"rT\":500,\"bT\":500}");
                assertTrue(queue.isEmpty(), "previous match must not survive the countdown");
                var currentKill = mock(LivingKillRecord.class);
                queue.offer(currentKill);
                HudRenderer.updateHUDData("{\"tabActive\":true}");
                assertEquals(1, queue.size(), "delayed active HUD must keep the new match's kill");
                assertSame(currentKill, queue.peek());
                queue.offer(mock(LivingKillRecord.class));
                var levelChange =
                        com.lunares.blockfield.client.mixin.KillFeedLevelMixin.class
                                .getDeclaredMethod(
                                        "blockfield$clearKillFeed",
                                        net.minecraft.client.multiplayer.ClientLevel.class,
                                        net.minecraft.client.gui.screens.ReceivingLevelScreen.Reason
                                                .class,
                                        org.spongepowered.asm.mixin.injection.callback.CallbackInfo
                                                .class);
                levelChange.setAccessible(true);
                levelChange.invoke(
                        new com.lunares.blockfield.client.mixin.KillFeedLevelMixin() {},
                        null,
                        null,
                        new org.spongepowered.asm.mixin.injection.callback.CallbackInfo(
                                "setLevel", false));
                assertTrue(queue.isEmpty(), "dimension change must clear kills");
                queue.offer(mock(LivingKillRecord.class));
                var logout = HudRenderer.class.getDeclaredMethod("onClientLogout");
                logout.setAccessible(true);
                logout.invoke(null);
                assertTrue(queue.isEmpty(), "disconnect/proxy join must clear kills");
            } finally {
                queue.clear();
            }
        }
    }
}
