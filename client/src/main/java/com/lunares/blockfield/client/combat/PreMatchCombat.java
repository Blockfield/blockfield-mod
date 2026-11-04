package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.grenade.GrenadeThrowSupport;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionResultHolder;

/** Client prediction of the weapon gate during ground-medkit treatment. */
public final class PreMatchCombat {
    private PreMatchCombat() {}

    public static void register() {
        UseItemCallback.EVENT.register(
                (player, world, hand) -> {
                    var stack = player.getItemInHand(hand);
                    return noAttacks()
                                    && world.isClientSide()
                                    && GrenadeThrowSupport.kind(stack) != null
                            ? InteractionResultHolder.fail(stack)
                            : InteractionResultHolder.pass(stack);
                });
    }

    public static boolean noAttacks() {
        return MedkitPickup.channeling();
    }
}
