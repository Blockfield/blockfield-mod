package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.WeaponHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WeaponHud draws the whole bottom of the screen: hotbar, health, plates, stamina and the held
 * item. Vanilla's hotbar, health rows (hearts, armour, food, air), vehicle health, held-item name
 * and XP, which Blockfield has no use for, stay off. The spectator menu is not the item hotbar and
 * still shows. The workshop keeps all of vanilla's, as WeaponHud stays off there.
 */
@Mixin(Gui.class)
public abstract class VanillaBottomHudMixin {
    @Inject(method = "renderItemHotbar", at = @At("HEAD"), cancellable = true)
    private void blockfield$hideHotbar(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (!WeaponHud.inWorkshop()) ci.cancel();
    }

    @Inject(method = "renderPlayerHealth", at = @At("HEAD"), cancellable = true)
    private void blockfield$hideHealth(GuiGraphics graphics, CallbackInfo ci) {
        if (!WeaponHud.inWorkshop()) ci.cancel();
    }

    @Inject(method = "renderVehicleHealth", at = @At("HEAD"), cancellable = true)
    private void blockfield$hideVehicleHealth(GuiGraphics graphics, CallbackInfo ci) {
        if (!WeaponHud.inWorkshop()) ci.cancel();
    }

    @Inject(method = "renderSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void blockfield$hideHeldItemName(GuiGraphics graphics, CallbackInfo ci) {
        if (!WeaponHud.inWorkshop()) ci.cancel();
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void blockfield$hideExperienceBar(GuiGraphics graphics, int x, CallbackInfo ci) {
        if (!WeaponHud.inWorkshop()) ci.cancel();
    }

    @Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
    private void blockfield$hideExperienceLevel(
            GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (!WeaponHud.inWorkshop()) ci.cancel();
    }
}
