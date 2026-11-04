package com.lunares.blockfield.client.mixin.parcool;

import com.alrex.parcool.client.animation.PlayerModelTransformer;
import com.lunares.blockfield.client.animation.RemoteParCoolPresentation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PlayerModelTransformer.class, remap = false)
public abstract class ParCoolTransformerMixin {
    @Shadow @Final private Player player;

    @Inject(method = "getPartialTick", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$animationFraction(CallbackInfoReturnable<Float> cir) {
        var fraction = RemoteParCoolPresentation.fractionA(player);
        if (fraction != null) cir.setReturnValue(fraction);
    }
}
