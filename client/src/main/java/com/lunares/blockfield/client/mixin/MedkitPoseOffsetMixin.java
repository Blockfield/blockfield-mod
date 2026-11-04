package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.MedkitPickup;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The crouch of MedkitPoseMixin is drawn lowered like a real crouch, feet on the ground. */
@Mixin(PlayerRenderer.class)
public abstract class MedkitPoseOffsetMixin {
    @Inject(method = "getRenderOffset", at = @At("HEAD"), cancellable = true)
    private void blockfield$medkitCrouchOffset(
            AbstractClientPlayer player, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        // A driven remote poses from its checkpoint (as MedkitPoseMixin does), never live state.
        Boolean driven = com.lunares.blockfield.client.combat.DrivenPresentation.medkit(player);
        if (driven != null ? driven : MedkitPickup.posing(player.getId()))
            cir.setReturnValue(new Vec3(0, -0.125, 0));
    }
}
