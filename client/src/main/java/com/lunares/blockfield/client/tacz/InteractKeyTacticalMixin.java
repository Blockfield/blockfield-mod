package com.lunares.blockfield.client.tacz;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.lunares.blockfield.client.TacticalItems;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The TACZ interact key works with a gun only. Grenades, medkits and armor plates (TacticalItems)
 * never touch the block under the crosshair with the right button, so doors, buttons and levers
 * need the same key with them too. TACZ then calls startUseItem, which for these items goes
 * straight to use (ThrowableUseMixin, GrenadeInputMixin): here the key does only the block or
 * entity part of a vanilla right click, never the item's own use.
 */
@Mixin(targets = "com.tacz.guns.client.input.InteractKey", remap = false)
public abstract class InteractKeyTacticalMixin {
    // Intermediary descriptor (class_1309 = LivingEntity): TACZ in the game is compiled against
    // intermediary.
    @WrapOperation(
            method = {"doInteractLogic", "onInteractControllerPress"},
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/tacz/guns/api/item/IGun;mainHandHoldGun(Lnet/minecraft/class_1309;)Z"))
    private static boolean blockfield$tacticalItems(
            LivingEntity entity, Operation<Boolean> original) {
        return original.call(entity) || TacticalItems.holding(Minecraft.getInstance());
    }

    @WrapOperation(
            method = "interactBlock",
            at =
                    @At(
                            value = "INVOKE",
                            remap = true,
                            target = "Lnet/minecraft/client/Minecraft;startUseItem()V"))
    private static void blockfield$useBlock(
            Minecraft mc, Operation<Void> original, @Local(argsOnly = true) BlockHitResult hit) {
        if (!TacticalItems.holding(mc) || mc.gameMode == null || mc.player == null) {
            original.call(mc);
            return;
        }
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        if (result.shouldSwing()) mc.player.swing(InteractionHand.MAIN_HAND);
    }

    @WrapOperation(
            method = "interactEntity",
            at =
                    @At(
                            value = "INVOKE",
                            remap = true,
                            target = "Lnet/minecraft/client/Minecraft;startUseItem()V"))
    private static void blockfield$useEntity(
            Minecraft mc, Operation<Void> original, @Local(argsOnly = true) EntityHitResult hit) {
        if (!TacticalItems.holding(mc) || mc.gameMode == null || mc.player == null) {
            original.call(mc);
            return;
        }
        InteractionResult result =
                mc.gameMode.interactAt(mc.player, hit.getEntity(), hit, InteractionHand.MAIN_HAND);
        if (!result.consumesAction())
            result = mc.gameMode.interact(mc.player, hit.getEntity(), InteractionHand.MAIN_HAND);
        if (result.shouldSwing()) mc.player.swing(InteractionHand.MAIN_HAND);
    }
}
