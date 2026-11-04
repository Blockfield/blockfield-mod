package com.lunares.blockfield.client.tacz;

import com.tacz.guns.client.gui.overlay.GunHudOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The magazine size and reserve TaCZ's overlay refreshes every 50 ms (GunHudOverlayMixin). */
@Mixin(GunHudOverlay.class)
public interface GunHudCache {
    @Accessor(value = "cacheInventoryAmmoCount", remap = false)
    static int reserve() {
        throw new AssertionError();
    }

    @Accessor(value = "cacheMaxAmmoCount", remap = false)
    static int magazine() {
        throw new AssertionError();
    }
}
