package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.ClientCombatScene;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class CombatSnapshotLifetimeMixin {
    @Inject(method = "addEntity", at = @At("RETURN"), require = 1)
    private void blockfield$add(Entity entity, CallbackInfo ci) {
        ClientCombatScene.add(entity);
    }

    @Inject(method = "removeEntity", at = @At("HEAD"), require = 1)
    private void blockfield$remove(int id, Entity.RemovalReason reason, CallbackInfo ci) {
        ClientCombatScene.remove(id);
    }
}
