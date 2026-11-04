package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.CombatChannels;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.ishland.raknetify.fabric.common.connection.RakNetMultiChannel", remap = false)
public abstract class CombatRaknetChannelMixin {
    @Inject(
            method = "getPacketChannelOverride(Ljava/lang/Class;Z)I",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            remap = false)
    private static void blockfield$combatChannel(
            Class<?> packetClass, boolean suppressWarning, CallbackInfoReturnable<Integer> cir) {
        if (CombatChannels.rawCombatClass(packetClass)) cir.setReturnValue(CombatChannels.CHANNEL);
    }
}
