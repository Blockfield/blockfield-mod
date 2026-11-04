package com.lunares.blockfield.client.mixin.lrtactical;

import com.lunares.blockfield.client.animation.RemoteLrCompat;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PlayerAnimator ticks a player's stack at {@code Player.tick} HEAD; the driven remote's stack tick
 * is suppressed, so its LR compatibility view advances here on the same per-player schedule.
 */
@Mixin(Player.class)
public abstract class RemoteLrCompatTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void blockfield$compatTick(CallbackInfo ci) {
        RemoteLrCompat.tick((Player) (Object) this);
    }
}
