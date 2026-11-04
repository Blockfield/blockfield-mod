package com.lunares.blockfield.client.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Writes the private native A-domain pairs of a driven remote ({@code DrivenPresentation}). */
@Mixin(LivingEntity.class)
public interface CombatDrivenLivingAccessor {
    @Accessor("swimAmount")
    void blockfield$setSwimAmount(float value);

    @Accessor("swimAmountO")
    void blockfield$setSwimAmountO(float value);

    @Accessor("fallFlyTicks")
    void blockfield$setFallFlyTicks(int value);
}
