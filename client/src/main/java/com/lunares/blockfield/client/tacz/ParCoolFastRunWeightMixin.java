package com.lunares.blockfield.client.tacz;

import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.ActionProcessor;
import com.alrex.parcool.common.action.impl.FastRun;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.modifier.custom.WeightModifier;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Бег (FastRun на клавише спринта) с оружием в руках тратит стамину быстрее пропорционально весу
 * TACZ с учётом обвесов. Стамина ParCool живёт на клиенте, поэтому множитель только здесь.
 */
@Mixin(value = ActionProcessor.class, remap = false)
public abstract class ParCoolFastRunWeightMixin {
    /** +25% расхода за килограмм: пистолет ~1 кг +25%, пулемёт 6 кг x2.5. */
    @Unique private static final float BLOCKFIELD$COST_PER_KG = 0.25f;

    @Unique private float blockfield$fraction;

    // Цель в intermediary (class_1657 = Player): при remap = false строку никто не ремапит, а
    // ParCool в игре
    // скомпилирован в intermediary; named-дескриптор уронил клиент 1.12.0 на старте.
    @WrapOperation(
            method = "processAction",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/alrex/parcool/common/action/ActionProcessor;consumeStamina(Lnet/minecraft/class_1657;I)V"))
    private void blockfield$weightCost(
            ActionProcessor self,
            Player player,
            int value,
            Operation<Void> original,
            @Local(argsOnly = true) Action action) {
        if (!(action instanceof FastRun) || IGun.getIGunOrNull(player.getMainHandItem()) == null) {
            original.call(self, player, value);
            return;
        }
        var cache = IGunOperator.fromLivingEntity(player).getCacheProperty();
        Float weight = cache == null ? null : cache.<Float>getCache(WeightModifier.ID);
        // Стамина целочисленная: дробный остаток копим между тиками.
        blockfield$fraction +=
                value * (1 + Math.max(0, weight == null ? 0 : weight) * BLOCKFIELD$COST_PER_KG);
        int whole = (int) blockfield$fraction;
        blockfield$fraction -= whole;
        original.call(self, player, whole);
    }
}
