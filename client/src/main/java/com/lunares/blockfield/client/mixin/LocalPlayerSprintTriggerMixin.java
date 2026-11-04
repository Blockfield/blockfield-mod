package com.lunares.blockfield.client.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hold-to-sprint (releases#14): vanilla double-tap W starts sprint through sprintTriggerTime
 * without the sprint key. SprintControl would stop it only after the tick, leaving a one-tick
 * sprint, FOV blip and a start/stop packet pair. With the window always closed, aiStep only starts
 * sprint from a held sprint key.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSprintTriggerMixin {
    @Shadow protected int sprintTriggerTime;

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void blockfield$noDoubleTapSprint(CallbackInfo ci) {
        this.sprintTriggerTime = 0;
    }
}
