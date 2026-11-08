package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.combat.FirstShotDrawGuard;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Arms {@link FirstShotDrawGuard} after every local draw to a gun (see its javadoc). */
@Mixin(targets = "com.tacz.guns.client.gameplay.LocalPlayerDraw", remap = false)
public abstract class LocalPlayerDrawGuardMixin {

    @Inject(method = "draw", at = @At("RETURN"), remap = false)
    private void blockfield$armFirstShotGuard(CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        var mainHand = player.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(mainHand);
        if (gun == null) return;
        long drawMs =
                TimelessAPI.getClientGunIndex(gun.getGunId(mainHand))
                        .map(ClientGunIndex::getGunData)
                        .map(data -> (long) (data.getDrawTime() * 1000))
                        .orElse(700L);
        // Gun id + slot: only a different gun drops a tap queued for the previous one.
        FirstShotDrawGuard.onDrawToGun(
                System.nanoTime(),
                drawMs,
                gun.getGunId(mainHand) + "@" + player.getInventory().selected);
    }
}
