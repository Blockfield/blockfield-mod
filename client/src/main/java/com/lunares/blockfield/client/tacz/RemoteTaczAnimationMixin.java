package com.lunares.blockfield.client.tacz;

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
 * Every TaCZ PlayerAnimator geometry change (render-time loop/rotation selection, fire/reload/
 * melee once actors, draw/stop fades) ends in these four leaves. For a driven remote they come from
 * the authoritative graph instead; the listeners carry no sound or particle side effects.
 */
@Mixin(value = AnimationManager.class, remap = false)
public abstract class RemoteTaczAnimationMixin {
    @Inject(method = "playLoopAnimation", at = @At("HEAD"), cancellable = true)
    private static void blockfield$drivenLoop(
            AbstractClientPlayer player,
            GunDisplayInstance display,
            ResourceLocation layer,
            String name,
            CallbackInfo ci) {
        if (RemotePaPresentation.driven(player)) ci.cancel();
    }

    @Inject(method = "playOnceAnimation", at = @At("HEAD"), cancellable = true)
    private static void blockfield$drivenOnce(
            AbstractClientPlayer player,
            GunDisplayInstance display,
            ResourceLocation layer,
            String name,
            CallbackInfo ci) {
        if (RemotePaPresentation.driven(player)) ci.cancel();
    }

    @Inject(method = "playRotationAnimation", at = @At("HEAD"), cancellable = true)
    private static void blockfield$drivenRotation(
            AbstractClientPlayer player, GunDisplayInstance display, CallbackInfo ci) {
        if (RemotePaPresentation.driven(player)) ci.cancel();
    }

    @Inject(method = "stopAnimation", at = @At("HEAD"), cancellable = true)
    private static void blockfield$drivenStop(
            AbstractClientPlayer player, ResourceLocation layer, int fade, CallbackInfo ci) {
        if (RemotePaPresentation.driven(player)) ci.cancel();
    }
}
