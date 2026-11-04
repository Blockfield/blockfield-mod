package com.lunares.blockfield.client.mixin;

import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * view_range of a display: DisplayBatcher culls a baked section at the distance vanilla would cull
 * its entities.
 */
@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker("getViewRange")
    float blockfield$viewRange();
}
