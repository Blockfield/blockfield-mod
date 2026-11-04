package com.lunares.blockfield.client.mixin.parcool;

import com.alrex.parcool.client.animation.Animator;
import com.alrex.parcool.common.attachment.client.Animation;
import com.alrex.parcool.common.attachment.common.Parkourability;
import com.alrex.parcool.fabric.RenderFrameEvent;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lunares.blockfield.client.animation.RemoteParCoolPresentation;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Animation.class, remap = false)
public abstract class ParCoolAnimationMixin {
    @Inject(method = "animatePre", at = @At("HEAD"), require = 1)
    private void blockfield$modelPre(
            Player player,
            com.alrex.parcool.client.animation.PlayerModelTransformer transformer,
            CallbackInfoReturnable<Boolean> cir) {
        RemoteParCoolPresentation.requireScope(player);
    }

    @Inject(method = "animatePost", at = @At("HEAD"), require = 1)
    private void blockfield$modelPost(
            Player player,
            com.alrex.parcool.client.animation.PlayerModelTransformer transformer,
            CallbackInfo ci) {
        RemoteParCoolPresentation.requireScope(player);
    }

    @Inject(method = "rotatePre", at = @At("HEAD"), require = 1)
    private void blockfield$rootPre(
            AbstractClientPlayer player,
            com.alrex.parcool.client.animation.PlayerModelRotator rotator,
            CallbackInfoReturnable<Boolean> cir) {
        RemoteParCoolPresentation.requireScope(player);
    }

    @Inject(method = "rotatePost", at = @At("HEAD"), require = 1)
    private void blockfield$rootPost(
            AbstractClientPlayer player,
            com.alrex.parcool.client.animation.PlayerModelRotator rotator,
            CallbackInfo ci) {
        RemoteParCoolPresentation.requireScope(player);
    }

    @Inject(method = "get", at = @At("HEAD"), cancellable = true, require = 1)
    private static void blockfield$scratch(Player player, CallbackInfoReturnable<Animation> cir) {
        var scratch = RemoteParCoolPresentation.animation(player);
        if (scratch != null) cir.setReturnValue(scratch);
    }

    @Inject(method = "get", at = @At("RETURN"), require = 1)
    private static void blockfield$owner(Player player, CallbackInfoReturnable<Animation> cir) {
        RemoteParCoolPresentation.observeNative(player, cir.getReturnValue());
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$integerOwner(
            AbstractClientPlayer player, Parkourability parkour, CallbackInfo ci) {
        if (RemoteParCoolPresentation.driven(player)) ci.cancel();
    }

    @Inject(method = "onRenderTick", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$noFrameState(
            RenderFrameEvent event, Player player, Parkourability parkour, CallbackInfo ci) {
        if (RemoteParCoolPresentation.driven(player)) ci.cancel();
    }

    @Inject(method = "updateAnimationInfo", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$authoritativeOption(AbstractClientPlayer player, CallbackInfo ci) {
        if (RemoteParCoolPresentation.driven(player)) ci.cancel();
    }

    @Inject(
            method = {"setAnimator", "removeAnimator"},
            at = @At("HEAD"),
            cancellable = true,
            require = 2)
    private void blockfield$noClientSelection(CallbackInfo ci) {
        if (RemoteParCoolPresentation.drivenAnimation(this)) ci.cancel();
    }

    @WrapOperation(
            method = {"animatePre", "rotatePre"},
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/alrex/parcool/client/animation/Animator;shouldRemoved(Lnet/minecraft/class_1657;Lcom/alrex/parcool/common/attachment/common/Parkourability;)Z"),
            require = 2)
    private boolean blockfield$integerRemoval(
            Animator actor, Player player, Parkourability parkour, Operation<Boolean> original) {
        return !RemoteParCoolPresentation.driven(player) && original.call(actor, player, parkour);
    }
}
