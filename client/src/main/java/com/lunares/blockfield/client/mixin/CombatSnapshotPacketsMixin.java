package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lunares.blockfield.client.combat.ClientCombatScene;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class CombatSnapshotPacketsMixin {
    @WrapOperation(
            method = {"handleMoveEntity", "handleTeleportEntity"},
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/Entity;lerpTo(DDDFFI)V"),
            require = 3)
    private void blockfield$transformOnly(
            Entity entity,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            int steps,
            Operation<Void> original) {
        if (!ClientCombatScene.driven(entity)) original.call(entity, x, y, z, yaw, pitch, steps);
    }

    @WrapOperation(
            method = "handleRotateMob",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/Entity;lerpHeadTo(FI)V"),
            require = 1)
    private void blockfield$headOnly(
            Entity entity, float yaw, int steps, Operation<Void> original) {
        if (!ClientCombatScene.driven(entity)) original.call(entity, yaw, steps);
    }

    @WrapOperation(
            method = "handleSetEntityMotion",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/Entity;lerpMotion(DDD)V"),
            require = 1)
    private void blockfield$motionOnly(
            Entity entity, double x, double y, double z, Operation<Void> original) {
        if (!ClientCombatScene.driven(entity)) original.call(entity, x, y, z);
    }

    @Inject(method = "handleSetEntityPassengersPacket", at = @At("RETURN"), require = 1)
    private void blockfield$seats(ClientboundSetPassengersPacket packet, CallbackInfo ci) {
        ClientCombatScene.linkageChanged(packet.getVehicle());
    }

    @Inject(method = "handleRespawn", at = @At("RETURN"), require = 1)
    private void blockfield$newScene(ClientboundRespawnPacket packet, CallbackInfo ci) {
        ClientCombatScene.sceneReset();
    }
}
