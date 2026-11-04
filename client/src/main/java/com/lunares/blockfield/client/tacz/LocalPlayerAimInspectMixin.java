package com.lunares.blockfield.client.tacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.animation.AnimationController;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ADS interrupts weapon inspection (releases#13). TACZ fires, reloads and draws through the gun
 * state machine, but LocalPlayerAim.aim sends it no input, so an inspect clip kept playing while
 * aiming. Only tracks whose running clip is an inspect animation are removed; reload/draw tracks
 * stay untouched.
 */
@Mixin(targets = "com.tacz.guns.client.gameplay.LocalPlayerAim", remap = false)
public abstract class LocalPlayerAimInspectMixin {
    @Shadow @Final private LocalPlayer player;

    @Inject(method = "aim", at = @At("HEAD"), remap = false)
    private void blockfield$aimStopsInspect(boolean isAim, CallbackInfo ci) {
        if (!isAim) return;
        TimelessAPI.getGunDisplay(player.getMainHandItem())
                .ifPresent(
                        display -> {
                            var machine = display.getAnimationStateMachine();
                            if (machine == null) return;
                            blockfield$stopInspect(machine.getAnimationController());
                        });
    }

    @Unique
    private static void blockfield$stopInspect(AnimationController controller) {
        Iterable<Integer> tracks = controller.getUpdatingTrackArray();
        // TACZ uses all allocated tracks when no custom update order is configured.
        if (tracks == null) {
            int count =
                    ((AnimationControllerAccessor) controller)
                            .blockfield$getCurrentRunners()
                            .size();
            tracks = () -> java.util.stream.IntStream.range(0, count).iterator();
        }
        for (int track : tracks) {
            ObjectAnimationRunner runner = controller.getAnimation(track);
            if (runner != null && runner.getAnimation().name.startsWith("inspect"))
                controller.removeAnimation(track);
        }
    }
}
