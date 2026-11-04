package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.TacticalItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Аптечка и бронепластина (TacticalItems) работают через use предмета, но ваниль сначала уводит ПКМ
 * в gameMode.useItemOn, и если ту ветку кто-то из модов забирает себе (SUCCESS/FAIL), до use дело
 * не доходит: предмет работает только «в небо», а при прицеле в блок или траву — нет. Этим
 * предметам взаимодействие с блоком не нужно, поэтому для них идём в use сразу. Ручные гранаты сюда
 * не входят: их ЛКМ/ПКМ сам запускает GrenadeInputMixin (тоже мимо useItemOn). Двери и кнопки с
 * этими предметами -- клавишей взаимодействия TACZ, как с оружием (InteractKeyTacticalMixin).
 */
@Mixin(Minecraft.class)
public abstract class ThrowableUseMixin {

    @Shadow @Nullable public LocalPlayer player;
    @Shadow @Nullable public MultiPlayerGameMode gameMode;
    @Shadow @Nullable public HitResult hitResult;
    @Shadow private int rightClickDelay;

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void blockfield$handheldItemSkipsBlockUse(CallbackInfo ci) {
        if (this.player == null || this.gameMode == null || this.gameMode.isDestroying()) return;
        if (this.hitResult == null || this.hitResult.getType() == HitResult.Type.MISS) return;
        if (this.player.isUsingItem() || this.player.isHandsBusy()) return;

        ItemStack stack = this.player.getMainHandItem();
        if (!TacticalItems.directUse(stack)) return;

        this.rightClickDelay = 4;
        InteractionResult result = this.gameMode.useItem(this.player, InteractionHand.MAIN_HAND);
        if (result.consumesAction() && result.shouldSwing())
            this.player.swing(InteractionHand.MAIN_HAND);
        ci.cancel();
    }
}
