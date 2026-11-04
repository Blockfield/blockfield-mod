package com.lunares.blockfield.client.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TransientEntitySectionManager.class)
public interface EntitySectionManagerAccessor {
    @Accessor("entityStorage")
    EntityLookup<Entity> blockfield$lookup();

    @Accessor("sectionStorage")
    EntitySectionStorage<Entity> blockfield$sections();
}
