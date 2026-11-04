package com.lunares.blockfield.client.tacz;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.client.event.TickAnimationEvent;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the movement input in walk/idle while an action is pending or reloading. This only affects
 * animation input; TaCZ Tweaks retains control over actual sprinting. Vest-backed shell reloads
 * also need GunAnimationStateContextMixin's ammo predicate.
 */
@Mixin(TickAnimationEvent.class)
public abstract class TickAnimationEventMixin {

    @ModifyExpressionValue(
            method = "lambda$tickAnimation$0",
            remap = false,
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/player/LocalPlayer;isSprinting()Z",
                            remap = true))
    private static boolean blockfield$noRunAnimationWhileReloading(
            boolean sprinting, LocalPlayer player) {
        // The local lock covers the interval before the server acknowledges the reload.
        return sprinting
                && !IClientPlayerGunOperator.fromLocalPlayer(player).getDataHolder().clientStateLock
                && !IGunOperator.fromLivingEntity(player)
                        .getSynReloadState()
                        .getStateType()
                        .isReloading();
    }
}
