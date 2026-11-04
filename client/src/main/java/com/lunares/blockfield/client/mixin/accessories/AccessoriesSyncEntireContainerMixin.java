package com.lunares.blockfield.client.mixin.accessories;

import com.lunares.blockfield.client.accessories.AccessoriesSyncDefer;
import io.wispforest.accessories.networking.client.SyncEntireContainer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replays the full-container sync once the client capability exists instead of dropping it. */
@Mixin(SyncEntireContainer.class)
public abstract class AccessoriesSyncEntireContainerMixin {
    @Inject(method = "handlePacket", at = @At("HEAD"), cancellable = true, require = 1)
    private static void blockfield$deferUntilCapability(
            SyncEntireContainer packet, Player player, CallbackInfo ci) {
        if (AccessoriesSyncDefer.deferIfNoCapability(packet.entityId(), packet)) ci.cancel();
    }
}
