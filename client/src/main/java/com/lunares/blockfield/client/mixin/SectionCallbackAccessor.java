package com.lunares.blockfield.client.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.entity.TransientEntitySectionManager$Callback")
public interface SectionCallbackAccessor {
    @Accessor("currentSectionKey")
    long blockfield$sectionKey();

    @Accessor("currentSection")
    EntitySection<Entity> blockfield$section();

    @Accessor("currentSection")
    void blockfield$setSection(EntitySection<Entity> section);
}
