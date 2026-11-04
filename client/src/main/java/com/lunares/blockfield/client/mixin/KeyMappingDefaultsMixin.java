package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.KeyMappingDefaultAccess;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(KeyMapping.class)
public abstract class KeyMappingDefaultsMixin implements KeyMappingDefaultAccess {

    @Mutable @Shadow @Final private InputConstants.Key defaultKey;

    @Override
    public void blockfield$setDefaultKey(InputConstants.Key key) {
        this.defaultKey = key;
    }
}
