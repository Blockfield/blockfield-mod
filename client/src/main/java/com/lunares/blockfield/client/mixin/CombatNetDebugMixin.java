package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.ClientCombatScene;
import java.util.List;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DebugScreenOverlay.class)
public abstract class CombatNetDebugMixin {
    @Inject(method = "getGameInformation", at = @At("RETURN"), require = 1)
    private void blockfield$netDebug(CallbackInfoReturnable<List<String>> cir) {
        cir.getReturnValue().add(ClientCombatScene.debugLine());
    }
}
