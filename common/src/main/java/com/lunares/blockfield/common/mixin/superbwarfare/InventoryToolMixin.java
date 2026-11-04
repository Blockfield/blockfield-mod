package com.lunares.blockfield.common.mixin.superbwarfare;

import com.lunares.blockfield.common.compat.SuperbWarfareRigAmmo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extend only the exact creative-box presence query; ordinary inventory operations stay untouched.
 */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.tools.InventoryTool", remap = false)
public abstract class InventoryToolMixin {
    // Only the Entity overload: the NonNullList one never has an owner. remap=false keeps the
    // selector
    // literal, so it carries the production intermediary names (class_1297 Entity, class_1792
    // Item).
    // A wildcard "hasItem" with @Coerce Object matched one overload of two and crashed the client
    // when
    // the SBW ammo HUD first loaded InventoryTool (releases#11 rig run).
    // hasCreativeAmmoBox(Entity) and its vehicle passenger path delegate to hasItem(Entity, Item),
    // so the upstream usePassengerCreativeAmmoBox setting and infinite-ammo behavior are retained.
    @Inject(
            method = "hasItem(Lnet/minecraft/class_1297;Lnet/minecraft/class_1792;)Z",
            at = @At("RETURN"),
            cancellable = true,
            remap = false)
    private static void blockfield$creativeBoxInRig(
            Entity owner, Item item, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && SuperbWarfareRigAmmo.hasItemInEquippedRig(owner, item)) {
            cir.setReturnValue(true);
        }
    }
}
