package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class MapSnapshotsSwapTest {
    @Test
    void refreshClosesReplacedTextureAndReleaseFreesBoth() {
        var mc = mock(Minecraft.class);
        var manager = mock(TextureManager.class);
        when(mc.getTextureManager()).thenReturn(manager);
        var first = mock(DynamicTexture.class);
        var second = mock(DynamicTexture.class);
        try (MockedStatic<Minecraft> app = mockStatic(Minecraft.class)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            var id = ResourceLocation.fromNamespaceAndPath("blockfield", "map_snapshot");
            // Before the fix every refresh registered over the old id without closing it (leak).
            MapSnapshots.swap(id, first);
            MapSnapshots.swap(id, second);
            verify(first).close();
            verify(second, never()).close();
            verify(manager, times(2)).register(eq(id), any());
            MapSnapshots.releaseTextures(manager);
            verify(second).close();
            verify(manager).release(id);
            verify(manager)
                    .release(
                            ResourceLocation.fromNamespaceAndPath(
                                    "blockfield", "minimap_snapshot"));
        }
    }
}
