package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.lunares.blockfield.client.combat.MedkitPickup;
import com.lunares.blockfield.common.StanceTransition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lowers the drawn body while the stance eases: vanilla snaps the -.125 render offset with the pose
 * tick, ahead of the eased box. Only while the live stance runs; settled poses and medkit posing
 * keep their existing paths.
 */
@Mixin(PlayerRenderer.class)
public abstract class StanceRenderOffsetMixin {
    @Inject(method = "getRenderOffset", at = @At("HEAD"), cancellable = true)
    private void blockfield$easeCrouchOffset(
            AbstractClientPlayer player, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        if (!(player instanceof StanceTransition.Access access)) return;
        StanceTransition stance = access.blockfield$stance();
        if (stance == null || !stance.eases() || !stance.changing()) return;
        Boolean driven = DrivenPresentation.medkit(player);
        if (driven != null ? driven : MedkitPickup.posing(player.getId())) return;
        cir.setReturnValue(new Vec3(0, -2.0f * player.getScale() / 16.0 * stance.crouchness(), 0));
    }
}
