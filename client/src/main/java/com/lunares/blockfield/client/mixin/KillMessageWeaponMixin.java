package com.lunares.blockfield.client.mixin;

import com.atsuishio.superbwarfare.network.message.receive.LivingGunKillMessage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The kill packet carries the death event's weapon, but the record builder reads the killer's
 * current held item instead: switching weapons after the shot rewrites the entry. Resolve the
 * record stack from the event weapon; when the hand still holds the same item, keep its stack so
 * gun-specific data (TaCZ gun id) survives for the silhouette.
 */
@Mixin(LivingGunKillMessage.class)
public abstract class KillMessageWeaponMixin {
    @Redirect(
            method = "handler",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/LivingEntity;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack blockfield$deathEventWeapon(LivingEntity attacker) {
        LivingGunKillMessage self = (LivingGunKillMessage) (Object) this;
        ItemStack event = new ItemStack(BuiltInRegistries.ITEM.get(self.getWeapon()));
        if (event.isEmpty()) return attacker.getMainHandItem();
        ItemStack hand = attacker.getMainHandItem();
        if (ItemStack.isSameItem(hand, event)) return hand.copy();
        return event;
    }
}
