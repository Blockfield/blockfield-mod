package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.core.ClientVestAmmo;
import com.tacz.guns.client.gui.overlay.GunHudOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WeaponHud draws the gun cluster. TaCZ's overlay still runs up to its first draw, past its gates
 * and the 50 ms refresh of the magazine size and reserve it caches (GunHudCache reads them), and
 * stops there. Its reserve counted only the inventory, so ammo in the vest is added.
 */
@Mixin(GunHudOverlay.class)
public abstract class GunHudOverlayMixin {
    @Shadow(remap = false)
    private static int cacheInventoryAmmoCount;

    @Inject(
            method = "render",
            remap = false,
            cancellable = true,
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V",
                            ordinal = 0,
                            remap = true))
    private void blockfield$drawnByWeaponHud(
            GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "handleInventoryAmmo", at = @At("TAIL"), remap = false)
    private static void blockfield$addVestAmmo(
            ItemStack stack, Inventory inventory, CallbackInfo ci) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return;
        int vest = ClientVestAmmo.countAmmoFor(player, stack);
        if (vest < 0) {
            cacheInventoryAmmoCount = 9999;
        } else {
            cacheInventoryAmmoCount = Math.min(9999, cacheInventoryAmmoCount + vest);
        }
    }
}
