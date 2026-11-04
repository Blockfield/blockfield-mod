package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.music.MusicDirector;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * С саундтреком Blockfield ванильная фоновая музыка не играет: музыкой управляет только
 * MusicDirector.
 */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
    @Shadow @Nullable private SoundInstance currentMusic;

    @Shadow
    public abstract void stopPlaying();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void blockfield$adaptiveMusic(CallbackInfo ci) {
        if (!MusicDirector.active()) return;
        if (currentMusic != null) stopPlaying();
        ci.cancel();
    }
}
