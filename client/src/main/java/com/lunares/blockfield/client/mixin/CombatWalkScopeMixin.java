package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.DrivenPresentation;
import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A driven remote's limb walk is the authoritative R triple sampled at p_R (ADR4 §4.2): it replaces
 * native integration and ignores the renderer's partial. Native otherwise.
 */
@Mixin(WalkAnimationState.class)
public abstract class CombatWalkScopeMixin {
    @Inject(method = "position(F)F", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$drivenPosition(float partial, CallbackInfoReturnable<Float> cir) {
        Float driven = DrivenPresentation.walkPosition((WalkAnimationState) (Object) this);
        if (driven != null) cir.setReturnValue(driven);
    }

    @Inject(method = "speed(F)F", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$drivenSpeed(float partial, CallbackInfoReturnable<Float> cir) {
        Float driven = DrivenPresentation.walkSpeed((WalkAnimationState) (Object) this);
        if (driven != null) cir.setReturnValue(driven);
    }
}
