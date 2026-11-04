package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lunares.blockfield.client.render.DisplayBatcher;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public abstract class DisplayArrivalTasksMixin {
    @WrapOperation(
            method = "runTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
    private void blockfield$spreadArrivals(Minecraft client, Operation<Void> original) {
        long start = System.nanoTime();
        while (client.pollTask()) {
            // The respawn task itself changes the world, so check after each task. Other drains
            // (including managedBlock) remain unbounded so task completion waits cannot deadlock.
            if (DisplayBatcher.restoringArrivals() && System.nanoTime() - start >= 4_000_000L)
                break;
        }
    }
}
