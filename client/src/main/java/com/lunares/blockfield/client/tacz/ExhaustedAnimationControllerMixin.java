package com.lunares.blockfield.client.tacz;

import com.alrex.parcool.api.Stamina;
import com.lunares.blockfield.client.ExhaustedReloadAnimation;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.animation.AnimationController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Замедление перезарядки касается только контроллера ствола в руке локального игрока, не чужого
 * оружия.
 */
@Mixin(value = AnimationController.class, remap = false)
public abstract class ExhaustedAnimationControllerMixin {
    @Inject(method = "updateByTrack", at = @At("HEAD"))
    private void blockfield$markOwnGun(int track, boolean isSoundOnly, CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        ExhaustedReloadAnimation.slowOwnGun =
                player != null
                        && Stamina.get(player).isExhausted()
                        && TimelessAPI.getGunDisplay(player.getMainHandItem())
                                .map(display -> display.getAnimationStateMachine())
                                .filter(
                                        machine ->
                                                machine.getAnimationController() == (Object) this)
                                .isPresent();
    }

    @Inject(method = "updateByTrack", at = @At("RETURN"))
    private void blockfield$clearOwnGun(int track, boolean isSoundOnly, CallbackInfo ci) {
        ExhaustedReloadAnimation.slowOwnGun = false;
    }
}
