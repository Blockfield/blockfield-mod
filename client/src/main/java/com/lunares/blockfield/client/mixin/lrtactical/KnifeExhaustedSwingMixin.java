package com.lunares.blockfield.client.mixin.lrtactical;

import com.alrex.parcool.api.Stamina;
import com.lunares.blockfield.common.item.KitKnife;
import com.lunares.blockfield.network.packet.KnifeAimC2SPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The client half of the server's exhaustion gate ({@code KnifeCombat.swing}). Every LR swing,
 * pressed or auto-repeated, animates only after {@code preAttack} returns true and sends its packet
 * from inside it, so refusing here drops the animation, the sound and the packet together. Without
 * it the swing animated and the server then dropped the hit.
 *
 * <p>A knife swing that passes also sends the crosshair target first ({@link KnifeAimC2SPacket}),
 * so it reaches the server before LR's packet.
 */
@Pseudo
@Mixin(targets = "me.xjqsh.lrtactical.capability.CombatProperties")
public abstract class KnifeExhaustedSwingMixin {
    @Shadow(remap = false)
    @Final
    private Player player;

    @Inject(method = "preAttack", remap = false, at = @At("HEAD"), cancellable = true)
    private void blockfield$noSwingWhenExhausted(
            @Coerce Object action,
            Vec3 origin,
            Vec3 direction,
            CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof LocalPlayer
                && (com.lunares.blockfield.client.combat.PreMatchCombat.noAttacks()
                        || (KitKnife.is(player.getMainHandItem())
                                && Stamina.get(player).isExhausted()))) {
            cir.setReturnValue(false);
        } else if (player instanceof LocalPlayer
                && KitKnife.is(player.getMainHandItem())
                && ClientPlayNetworking.canSend(KnifeAimC2SPacket.TYPE)) {
            ClientPlayNetworking.send(
                    new KnifeAimC2SPacket(
                            Minecraft.getInstance().hitResult instanceof EntityHitResult hit
                                            && hit.getEntity() instanceof LivingEntity
                                    ? hit.getEntity().getId()
                                    : KnifeAimC2SPacket.NONE));
        }
    }
}
