package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.ExhaustedReloadAnimation;
import com.tacz.guns.api.client.animation.ObjectAnimation;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Пара к серверному ExhaustedReloadMixin: при пустой стамине анимация и звуки перезарядки своего
 * ствола идут в 1.5 раза медленнее, иначе анимация заканчивается раньше, чем сервер выдаёт патроны.
 * Какой ствол свой, решает ExhaustedAnimationControllerMixin.
 */
@Mixin(value = ObjectAnimationRunner.class, remap = false)
public abstract class ExhaustedReloadAnimationMixin {
    @Shadow @Final private ObjectAnimation animation;

    @ModifyVariable(method = "updateProgress", at = @At("HEAD"), argsOnly = true)
    private long blockfield$slowReload(long alphaProgress) {
        return ExhaustedReloadAnimation.slowOwnGun && animation.name.contains("reload")
                ? alphaProgress * 2 / 3
                : alphaProgress;
    }
}
