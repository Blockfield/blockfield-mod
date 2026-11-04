package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lunares.blockfield.client.music.MusicDirector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ваниль глушит все звуки при каждой смене мира (setLevel/disconnect -> updateScreenAndTick). В
 * Blockfield это каждая смерть (комната возрождения), высадка и выход из боя: без этого трек
 * обрывался бы и начинался заново. Музыку оставляем, остальные категории глушим как раньше.
 */
@Mixin(Minecraft.class)
public abstract class MusicKeepAcrossLevelsMixin {
    @WrapOperation(
            method = "updateScreenAndTick",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/sounds/SoundManager;stop()V"))
    private void blockfield$keepMusic(SoundManager sounds, Operation<Void> original) {
        if (MusicDirector.keepMusicAcrossLevels()) MusicDirector.stopAllButMusic(sounds);
        else original.call(sounds);
    }
}
