package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.VanillaDrivenScope;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pins {@code AbstractClientPlayer.getDeltaMovementLerped} to the authoritative R velocity pair for
 * driven remotes (ADR4 §4.2). The native method lerps the client tick's previous/current velocity;
 * a remote client tick leaves whatever it happened to hold, so the driven scope substitutes the R
 * endpoints and p_R installed by the main-frame write. Elytra banking reads this getter; the raw
 * elytra limb-divisor getter is scoped separately by {@code CombatDrivenEntityInputsMixin}.
 * Undriven entities (including the local player) keep the native path untouched.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class CombatPlayerVelocityMixin {
    @Inject(method = "getDeltaMovementLerped", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$drivenVelocity(float partial, CallbackInfoReturnable<Vec3> cir) {
        Vec3 driven =
                VanillaDrivenScope.lerpedVelocity(((AbstractClientPlayer) (Object) this).getId());
        if (driven != null) cir.setReturnValue(driven);
    }
}
