package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.UniformRenderers;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SBM draws its sleeves independently of HumanoidArmorLayer. */
@Pseudo
@Mixin(
        targets =
                "com.github.mcmodderanchor.simplebedrockmodel.v1.client.handler.FirstPersonArmorHandler",
        remap = false)
public abstract class UniformFirstPersonArmorMixin {
    @Inject(method = "onRenderArm", at = @At("HEAD"), cancellable = true)
    private static void blockfield$uniformSleeve(CallbackInfo ci) {
        if (FabricLoader.getInstance().isModLoaded("accessories")
                && UniformRenderers.isEquipped(Minecraft.getInstance().player)) ci.cancel();
    }
}
