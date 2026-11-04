package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.ExhaustedReloadAnimation;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.SpeedModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Замедляет позу, которую TACZ ставит внутри playOnceAnimation при флаге ExhaustedPlayerPoseMixin.
 * Только двухаргументная перегрузка: она однократно делегирует в трёхаргументную, двойной обёртки
 * нет.
 */
@Mixin(value = ModifierLayer.class, remap = false)
public abstract class ExhaustedModifierLayerMixin {
    @ModifyVariable(
            method =
                    "replaceAnimationWithFade(Ldev/kosmx/playerAnim/api/layered/modifier/AbstractFadeModifier;Ldev/kosmx/playerAnim/api/layered/IAnimation;)V",
            at = @At("HEAD"),
            argsOnly = true)
    private IAnimation blockfield$slowPose(IAnimation animation) {
        if (!ExhaustedReloadAnimation.slowPose || animation == null) return animation;
        // PA 2.0.4's varargs constructor leaves modifiers unhosted and the wrapper inactive.
        var wrapper = new ModifierLayer<>(animation);
        wrapper.addModifierLast(new SpeedModifier(2f / 3));
        return wrapper;
    }
}
