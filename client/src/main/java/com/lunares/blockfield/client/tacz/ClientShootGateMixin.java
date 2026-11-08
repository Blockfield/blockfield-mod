package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.combat.FirstShotDrawGuard;
import com.lunares.blockfield.client.gui.tablet.Tablet;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side gates in front of TaCZ shooting: an open tablet / ground-medkit treatment, and the
 * first shot after a draw (held locally until the server draw is observed, see FirstShotDrawGuard).
 * Гасим именно клиентский запрос: TACZ читает кнопку с сырого ввода GLFW, а сервер стреляет только
 * по пакету клиента — без запроса не будет ни выстрела, ни «призрачного» предсказания.
 */
@Mixin(targets = "com.tacz.guns.client.gameplay.LocalPlayerShoot", remap = false)
public abstract class ClientShootGateMixin {

    @Inject(method = "shoot", at = @At("HEAD"), cancellable = true, remap = false)
    private void blockfield$gateShoot(CallbackInfoReturnable<ShootResult> cir) {
        if (Tablet.isOpen() || com.lunares.blockfield.client.combat.PreMatchCombat.noAttacks())
            cir.setReturnValue(ShootResult.UNKNOWN_FAIL);
        // First shot after a draw: hold until the server draw is observed, never predict into a
        // certain IS_DRAWING refusal (see FirstShotDrawGuard).
        else if (FirstShotDrawGuard.shouldBlockShoot(System.nanoTime(), synDrawCoolDown())) {
            FirstShotDrawGuard.noteFireAttempt(System.nanoTime());
            cir.setReturnValue(ShootResult.IS_DRAWING);
        }
    }

    @Inject(method = "chargeShoot", at = @At("HEAD"), cancellable = true, remap = false)
    private void blockfield$gateChargeShoot(boolean start, CallbackInfoReturnable<Boolean> cir) {
        if (Tablet.isOpen() || com.lunares.blockfield.client.combat.PreMatchCombat.noAttacks())
            cir.setReturnValue(false);
        // Only a real press (trigger down) queues a replay. Idle per-tick polls with a released
        // trigger must not queue a shot the user never asked for; they fall through to TaCZ.
        else if (start
                && FirstShotDrawGuard.shouldBlockShoot(System.nanoTime(), synDrawCoolDown())) {
            FirstShotDrawGuard.noteFireAttempt(System.nanoTime());
            cir.setReturnValue(false);
        }
    }

    private static long synDrawCoolDown() {
        var player = Minecraft.getInstance().player;
        if (player == null) return 0;
        return IGunOperator.fromLivingEntity(player).getSynDrawCoolDown();
    }
}
