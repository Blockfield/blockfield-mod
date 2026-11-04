package com.lunares.blockfield.client.tacz;

import com.tacz.guns.api.client.animation.AnimationController;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import java.util.ArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = AnimationController.class, remap = false)
public interface AnimationControllerAccessor {
    @Accessor("currentRunners")
    ArrayList<ObjectAnimationRunner> blockfield$getCurrentRunners();
}
