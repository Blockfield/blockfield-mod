package com.lunares.blockfield.client.tacz;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lunares.blockfield.client.TacticalItems;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The "[E] interact" hint follows the key: it shows with class items too
 * (InteractKeyTacticalMixin). Which blocks and entities count is the tacz interact_key tags alone.
 */
@Mixin(targets = "com.tacz.guns.client.gui.overlay.InteractKeyTextOverlay", remap = false)
public abstract class InteractKeyTextOverlayMixin {
    // Intermediary descriptor (class_1309 = LivingEntity): TACZ in the game is compiled against
    // intermediary.
    @WrapOperation(
            method = {"renderBlockText", "renderEntityText"},
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/tacz/guns/api/item/IGun;mainHandHoldGun(Lnet/minecraft/class_1309;)Z"))
    private static boolean blockfield$tacticalItems(
            LivingEntity entity, Operation<Boolean> original) {
        return original.call(entity) || TacticalItems.holding(Minecraft.getInstance());
    }
}
