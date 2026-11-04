package com.lunares.blockfield.client.mixin.parcool;

import com.alrex.parcool.common.attachment.common.Parkourability;
import com.lunares.blockfield.client.animation.RemoteParCoolPresentation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Parkourability.class, remap = false)
public abstract class ParCoolAttachmentMixin {
    @Inject(method = "get", at = @At("HEAD"), cancellable = true, require = 1)
    private static void blockfield$animationScratch(
            Player player, CallbackInfoReturnable<Parkourability> cir) {
        var scratch = RemoteParCoolPresentation.attachment(player);
        if (scratch != null) cir.setReturnValue(scratch);
    }
}
