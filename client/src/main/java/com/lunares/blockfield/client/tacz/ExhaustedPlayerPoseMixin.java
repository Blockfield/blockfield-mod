package com.lunares.blockfield.client.tacz;

import com.alrex.parcool.api.Stamina;
import com.lunares.blockfield.client.ExhaustedReloadAnimation;
import com.lunares.blockfield.client.animation.RemotePaPresentation;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.compat.playeranimator.animation.AnimationManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Поза перезарядки от третьего лица идёт в 1.5 раза медленнее, если выдохся тот, кто перезаряжается
 * (стамина других игроков приходит рассылкой ParCool) — в пару к серверному ExhaustedReloadMixin.
 * Здесь только флаг на время публичного playOnceAnimation; саму позу оборачивает
 * ExhaustedModifierLayerMixin, чтобы не зависеть от сгенерированного имени лямбды TACZ.
 */
@Mixin(value = AnimationManager.class, remap = false)
public abstract class ExhaustedPlayerPoseMixin {
    @Inject(method = "playOnceAnimation", at = @At("HEAD"))
    private static void blockfield$markExhaustedReload(
            AbstractClientPlayer player,
            GunDisplayInstance display,
            ResourceLocation dataId,
            String animationName,
            CallbackInfo ci) {
        // A driven remote never reaches here (RemoteTaczAnimationMixin cancels its leaves; the
        // authoritative exhausted bit is graph topology). Never arm the flag for it: a cancelled
        // call would skip the RETURN reset below.
        ExhaustedReloadAnimation.slowPose =
                !RemotePaPresentation.driven(player)
                        && animationName.contains("reload")
                        && Stamina.get(player).isExhausted();
    }

    @Inject(method = "playOnceAnimation", at = @At("RETURN"))
    private static void blockfield$clearExhaustedReload(
            AbstractClientPlayer player,
            GunDisplayInstance display,
            ResourceLocation dataId,
            String animationName,
            CallbackInfo ci) {
        ExhaustedReloadAnimation.slowPose = false;
    }
}
