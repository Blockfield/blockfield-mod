package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lunares.blockfield.client.combat.ClientCombatScene;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(
        targets = "com.atsuishio.superbwarfare.network.message.receive.ClientMotionSyncMessage",
        remap = false)
public abstract class CombatSbwMotionMixin {
    @WrapOperation(
            method = "handler",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/Entity;lerpMotion(DDD)V",
                            remap = true),
            require = 1)
    private void blockfield$remoteMotion(
            Entity entity, double x, double y, double z, Operation<Void> original) {
        if (!ClientCombatScene.driven(entity)) original.call(entity, x, y, z);
    }
}
