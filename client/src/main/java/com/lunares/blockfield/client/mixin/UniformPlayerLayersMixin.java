package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.UniformRenderers;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
public abstract class UniformPlayerLayersMixin {
    @Inject(method = "setModelProperties", at = @At("TAIL"))
    private void blockfield$outerSkin(AbstractClientPlayer player, CallbackInfo ci) {
        if (!FabricLoader.getInstance().isModLoaded("accessories")
                || !UniformRenderers.isEquipped(player)) return;
        var model = ((PlayerRenderer) (Object) this).getModel();
        model.hat.visible =
                model.jacket.visible = model.leftSleeve.visible = model.rightSleeve.visible = false;
        model.leftPants.visible = model.rightPants.visible = false;
    }
}
