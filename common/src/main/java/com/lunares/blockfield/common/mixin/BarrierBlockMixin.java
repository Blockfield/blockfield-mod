package com.lunares.blockfield.common.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BarrierBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Barriers are player clip, as in CS: they stop players and mobs, not grenades, rockets and other
 * projectiles (LR Tactical, SuperbWarfare, vanilla), nor the entities tagged
 * blockfield:passes_barriers (SuperbWarfare's FPV and scout drones). Map barriers seal ParCool
 * ledges and roofs (blockfield-modpack docs/CODRE-MAPS.md) and must not change what can be thrown
 * or shot across the map. TaCZ bullets use their own ray trace: they pass through PassThroughBlocks
 * in tacz-common.toml.
 */
@Mixin(BarrierBlock.class)
public abstract class BarrierBlockMixin extends Block {
    @Unique
    private static final TagKey<EntityType<?>> PASSES_BARRIERS =
            TagKey.create(
                    Registries.ENTITY_TYPE,
                    ResourceLocation.fromNamespaceAndPath("blockfield", "passes_barriers"));

    private BarrierBlockMixin(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext entityContext
                && entityContext.getEntity() != null
                && (entityContext.getEntity() instanceof Projectile
                        || entityContext.getEntity().getType().is(PASSES_BARRIERS))) {
            return Shapes.empty();
        }
        return super.getCollisionShape(state, level, pos, context);
    }
}
