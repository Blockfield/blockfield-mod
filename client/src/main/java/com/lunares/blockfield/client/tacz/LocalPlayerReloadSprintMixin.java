package com.lunares.blockfield.client.tacz;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.tacz.guns.api.entity.IGunOperator;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reload stops sprint (releases#14). With ParCool FastRun in Auto, TACZ's tick gate kept returning
 * "may sprint" during a synced TACTICAL_RELOAD (rig jdb: every setSprinting came from TACZ
 * onTickClientSide with true), so the player ran through reloads. Block the start and end a running
 * sprint while the reload is synced; blocking both keeps the key-held aiStep from restarting sprint
 * every tick.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerReloadSprintMixin {

    @ModifyReturnValue(method = "canStartSprinting", at = @At("RETURN"))
    private boolean blockfield$noSprintStartWhileReloading(boolean original) {
        return original && !blockfield$reloading();
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void blockfield$stopSprintWhileReloading(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (self.isSprinting() && blockfield$reloading()) self.setSprinting(false);
    }

    @Unique
    private boolean blockfield$reloading() {
        return IGunOperator.fromLivingEntity((LocalPlayer) (Object) this)
                .getSynReloadState()
                .getStateType()
                .isReloading();
    }
}
