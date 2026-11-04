package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.HudRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class KillFeedLevelMixin {
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void blockfield$clearKillFeed(
            ClientLevel level, ReceivingLevelScreen.Reason reason, CallbackInfo ci) {
        HudRenderer.clearKillFeed();
    }
}
