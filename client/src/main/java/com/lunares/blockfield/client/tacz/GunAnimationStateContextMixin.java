package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.core.ClientVestAmmo;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.animation.statemachine.GunAnimationStateContext;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shell-by-shell reload scripts otherwise exit immediately when ammunition is only in the vest. */
@Mixin(GunAnimationStateContext.class)
public abstract class GunAnimationStateContextMixin {

    @Shadow(remap = false)
    private ItemStack currentGunItem;

    @Shadow(remap = false)
    private IGun iGun;

    @Shadow(remap = false)
    private GunData gunData;

    // The context outlives data sync. Resolve its data without restarting the animation machine.
    @Redirect(
            method = "*",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lcom/tacz/guns/client/animation/statemachine/GunAnimationStateContext;gunData:Lcom/tacz/guns/resource/pojo/data/gun/GunData;",
                            opcode = Opcodes.GETFIELD),
            remap = false)
    private GunData blockfield$currentBalance(GunAnimationStateContext context) {
        return iGun == null || currentGunItem == null
                ? gunData
                : TimelessAPI.getClientGunIndex(iGun.getGunId(currentGunItem))
                        .map(ClientGunIndex::getGunData)
                        .orElse(gunData);
    }

    @Inject(method = "hasAmmoToConsume", at = @At("RETURN"), cancellable = true, remap = false)
    private void blockfield$includeVestAmmo(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()
                || iGun == null
                || currentGunItem == null
                || iGun.useDummyAmmo(currentGunItem)) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.getCameraEntity() != player) return;
        ItemStack held = player.getMainHandItem();
        IGun heldGun = IGun.getIGunOrNull(held);
        // The renderer can retain the previous stack until the next tick after ammo
        // synchronization.
        if (heldGun == null || !heldGun.getGunId(held).equals(iGun.getGunId(currentGunItem)))
            return;
        if (ClientVestAmmo.hasAmmoFor(player, currentGunItem)) {
            cir.setReturnValue(true);
        }
    }
}
