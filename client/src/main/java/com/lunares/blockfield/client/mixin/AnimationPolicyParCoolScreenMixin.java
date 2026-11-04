package com.lunares.blockfield.client.mixin;

import com.alrex.parcool.client.gui.SettingBooleanConfigScreen;
import com.alrex.parcool.config.ParCoolConfig;
import com.lunares.blockfield.client.combat.AnimationPolicy;
import net.minecraft.client.gui.components.Checkbox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Locks the forced ParCool toggles in their own settings screen while connected (ADR4 §6): the
 * widgets render disabled, so the stored values behind the getter hooks cannot be edited
 * mid-session. Runs on every screen open, so no stale lock survives a disconnect.
 */
@Mixin(value = SettingBooleanConfigScreen.class, remap = false)
public abstract class AnimationPolicyParCoolScreenMixin {
    @Shadow private ParCoolConfig.Client.Booleans[] booleans;
    @Shadow private Checkbox[] configButtons;

    @Inject(method = "<init>", at = @At("TAIL"), require = 1)
    private void blockfield$lockPolicy(CallbackInfo ci) {
        for (int i = 0; i < booleans.length && i < configButtons.length; i++) {
            if (AnimationPolicy.lockedParCool(booleans[i].name())) configButtons[i].active = false;
        }
    }
}
