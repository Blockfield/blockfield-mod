package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.gui.tablet.Tablet;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * С открытым планшетом стрельба и удар заблокированы -- курсор занят его кнопками, а не боем. Так
 * же и пока игрок лечится с брошенной аптечки: оружие опущено (MedkitPickup).
 */
@Mixin(Minecraft.class)
public abstract class TabletInputMixin {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void blockfield$tabletBlocksAttack(CallbackInfoReturnable<Boolean> cir) {
        if (Tablet.isOpen()
                || com.lunares.blockfield.client.combat.PreMatchCombat.noAttacks()
                || com.lunares.blockfield.client.workshop.WorkshopToolsClient.attack(
                        Minecraft.getInstance())
                || com.lunares.blockfield.client.workshop.WorkshopToolsClient.attackWand(
                        Minecraft.getInstance())) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void blockfield$tabletBlocksContinueAttack(boolean leftClick, CallbackInfo ci) {
        if (Tablet.isOpen()
                || com.lunares.blockfield.client.combat.PreMatchCombat.noAttacks()
                || com.lunares.blockfield.client.workshop.WorkshopToolsClient.holdsAi(
                        Minecraft.getInstance())
                || com.lunares.blockfield.client.workshop.WorkshopToolsClient.holdsWand(
                        Minecraft.getInstance())) ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void blockfield$tabletBlocksUse(CallbackInfo ci) {
        if (Tablet.isOpen()
                || com.lunares.blockfield.client.combat.MedkitPickup.channeling()
                || com.lunares.blockfield.client.workshop.WorkshopToolsClient.useSketch(
                        Minecraft.getInstance())
                || com.lunares.blockfield.client.workshop.WorkshopToolsClient.useWand(
                        Minecraft.getInstance())) ci.cancel();
    }
}
