package com.lunares.blockfield.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.RecipeBookType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenRecipesMixin {
    @Inject(method = "init", at = @At("HEAD"))
    private void blockfield$closeRecipes(CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, false);
    }

    @Redirect(
            method = "init",
            at = @At(value = "NEW", target = "net/minecraft/client/gui/components/ImageButton"))
    private ImageButton blockfield$hideRecipeButton(
            int x, int y, int width, int height, WidgetSprites sprites, Button.OnPress onPress) {
        ImageButton button = new ImageButton(x, y, width, height, sprites, onPress);
        button.visible = false;
        button.active = false;
        return button;
    }
}
