package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.core.ClientVestAmmo;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Клиентский гейт перезарядки TACZ (LocalPlayerReload.reload) смотрит только в инвентарь. Разрешаем
 * запрос перезарядки, если боеприпас есть в разгрузочном жилете и магазин неполон.
 */
@Mixin(AbstractGunItem.class)
public abstract class AbstractGunItemMixin {

    @Inject(method = "canReload", at = @At("RETURN"), cancellable = true, remap = false)
    private void blockfield$canReloadFromVest(
            LivingEntity shooter, ItemStack gunItem, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() || !(shooter instanceof LocalPlayer player)) return;
        int currentAmmoCount = ((AbstractGunItem) (Object) this).getCurrentAmmoCount(gunItem);
        int maxAmmoCount =
                TimelessAPI.getCommonGunIndex(((AbstractGunItem) (Object) this).getGunId(gunItem))
                        .map(
                                index ->
                                        AttachmentDataUtils.getAmmoCountWithAttachment(
                                                gunItem, index.getGunData()))
                        .orElse(0);
        if (currentAmmoCount >= maxAmmoCount) return;
        if (ClientVestAmmo.hasAmmoFor(player, gunItem)) {
            cir.setReturnValue(true);
        }
    }
}
