package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.animation.RemotePaPresentation;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.compat.playeranimator.animation.AdjustmentYRotModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import java.util.Optional;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep the native facing formula, replacing only its live fixed-hand resource read. */
@Mixin(value = AdjustmentYRotModifier.class, remap = false)
public abstract class RemoteTaczAdjustmentMixin {
    @Shadow @Final private Player player;

    @Inject(
            method = "apply(Ljava/lang/String;)Ljava/util/Optional;",
            at = @At("HEAD"),
            cancellable = true)
    private void blockfield$fixedHand(
            String part, CallbackInfoReturnable<Optional<AdjustmentModifier.PartModifier>> ci) {
        if ((part.equals("leftArm") || part.equals("rightArm"))
                && Boolean.TRUE.equals(RemotePaPresentation.fixedHand(player)))
            ci.setReturnValue(Optional.empty());
    }

    @Redirect(
            method = "apply(Ljava/lang/String;)Ljava/util/Optional;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/tacz/guns/api/TimelessAPI;getGunDisplay(Lnet/minecraft/class_1799;)Ljava/util/Optional;"))
    private Optional<GunDisplayInstance> blockfield$retainedDisplay(ItemStack stack) {
        return RemotePaPresentation.driven(player)
                ? Optional.empty()
                : TimelessAPI.getGunDisplay(stack);
    }
}
