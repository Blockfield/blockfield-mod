package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lunares.blockfield.client.WorkshopKeyBindings;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Options.class)
public class WorkshopOptionsSaveMixin {
    @WrapMethod(method = "save")
    private void blockfield$keepBuilderBindingsTemporary(Operation<Void> original) {
        WorkshopKeyBindings.withPersistentBindings(() -> original.call());
    }
}
