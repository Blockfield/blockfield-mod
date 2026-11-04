package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.workshop.WorkshopGhostClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla writes {@code noPhysics = isSpectator()} at the top of every player tick; a ghost is
 * creative, so the flag is re-asserted right after that write, before movement runs. Local player
 * only: remote players keep vanilla physics.
 */
@Mixin(Player.class)
public abstract class WorkshopGhostPhysicsMixin {
    @Inject(
            method = "tick",
            at =
                    @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z",
                            shift = At.Shift.AFTER))
    private void blockfield$ghostNoPhysics(CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().player && WorkshopGhostClient.inGhost())
            ((Player) (Object) this).noPhysics = true;
    }
}
