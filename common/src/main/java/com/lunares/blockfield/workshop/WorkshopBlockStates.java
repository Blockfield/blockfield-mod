package com.lunares.blockfield.workshop;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/** Compact states have one parser and vanilla transform/shape rules on both sides. */
public final class WorkshopBlockStates {
    private WorkshopBlockStates() {}

    public static String id(String text) {
        int bracket = text.indexOf('[');
        return bracket < 0 ? text : text.substring(0, bracket);
    }

    public static boolean shaped(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("minecraft")
                && (block instanceof StairBlock
                        || block instanceof SlabBlock
                        || block instanceof WallBlock
                        || block instanceof FenceBlock
                        || block instanceof IronBarsBlock
                        || block instanceof TrapDoorBlock
                        || block instanceof RotatedPillarBlock
                        || block instanceof ChainBlock
                        || block instanceof LanternBlock);
    }

    public static BlockState parse(String text) {
        String id = id(text);
        if (!id.matches(WorkshopGeometry.ID))
            throw new IllegalArgumentException("Invalid block id");
        var location = ResourceLocation.parse(id);
        if (!BuiltInRegistries.BLOCK.containsKey(location))
            throw new IllegalArgumentException("Unknown block state");
        Block block = BuiltInRegistries.BLOCK.get(location);
        BlockState state = block.defaultBlockState();
        if (block instanceof LeavesBlock) state = state.setValue(LeavesBlock.PERSISTENT, true);
        if (text.equals(id)) return state;
        if (!shaped(block) || !text.endsWith("]"))
            throw new IllegalArgumentException("Unsupported block state");
        String properties = text.substring(id.length() + 1, text.length() - 1);
        if (properties.isEmpty()) throw new IllegalArgumentException("Empty block properties");
        var seen = new HashSet<String>();
        for (String pair : properties.split(",", -1)) {
            String[] parts = pair.split("=", -1);
            if (parts.length != 2 || !seen.add(parts[0]))
                throw new IllegalArgumentException("Invalid or duplicate block property");
            Property<?> property = block.getStateDefinition().getProperty(parts[0]);
            if (property == null) throw new IllegalArgumentException("Unknown block property");
            state = value(state, property, parts[1]);
        }
        if (!state.getFluidState().isEmpty()
                || state.hasProperty(BlockStateProperties.OPEN)
                        && state.getValue(BlockStateProperties.OPEN)
                || state.hasProperty(BlockStateProperties.POWERED)
                        && state.getValue(BlockStateProperties.POWERED))
            throw new IllegalArgumentException("Unsafe block state");
        return state;
    }

    private static <T extends Comparable<T>> BlockState value(
            BlockState state, Property<T> property, String text) {
        return state.setValue(
                property,
                property.getValue(text)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Invalid block property value")));
    }

    public static String text(BlockState state) {
        return net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state);
    }

    public static String transform(String text, int rotY, boolean x, boolean z) {
        // Full cubes need no registry access during pure geometry expansion.
        if ((rotY & 3) == 0 && !x && !z) return text;
        String id = id(text);
        if (id.equals(text) && !mayHaveOrientation(id)) return text;
        BlockState state = parse(text);
        BlockState original = state;
        if (x) state = state.mirror(Mirror.FRONT_BACK);
        if (z) state = state.mirror(Mirror.LEFT_RIGHT);
        state = state.rotate(Rotation.values()[rotY & 3]);
        return text.equals(id) && state.equals(original) ? text : text(state);
    }

    private static boolean mayHaveOrientation(String id) {
        return id.startsWith("minecraft:")
                && (id.endsWith("_stairs")
                        || id.endsWith("_slab")
                        || id.endsWith("_wall")
                        || id.endsWith("_fence")
                        || id.endsWith("_pane")
                        || id.endsWith("_trapdoor")
                        || id.endsWith("_log")
                        || id.endsWith("_wood")
                        || id.endsWith("_pillar")
                        || id.endsWith("_stem")
                        || id.endsWith("_hyphae")
                        || id.endsWith("lantern")
                        || id.equals("minecraft:chain")
                        || id.equals("minecraft:iron_bars")
                        || id.equals("minecraft:hay_block")
                        || id.equals("minecraft:basalt")
                        || id.equals("minecraft:polished_basalt")
                        || id.equals("minecraft:bone_block"));
    }

    public static boolean connects(BlockState state) {
        return state.getBlock() instanceof StairBlock
                || state.getBlock() instanceof WallBlock
                || state.getBlock() instanceof FenceBlock
                || state.getBlock() instanceof IronBarsBlock;
    }

    /** Shape queries see the final projection; scheduled fluid ticks cannot mutate a preview. */
    public static LevelAccessor view(LevelAccessor level, Function<BlockPos, BlockState> blocks) {
        return (LevelAccessor)
                Proxy.newProxyInstance(
                        LevelAccessor.class.getClassLoader(),
                        new Class<?>[] {LevelAccessor.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getBlockState"))
                                return blocks.apply((BlockPos) args[0]);
                            if (method.getName().equals("getFluidState"))
                                return blocks.apply((BlockPos) args[0]).getFluidState();
                            if (method.getName().equals("scheduleTick")) return null;
                            return method.invoke(level, args);
                        });
    }

    public static BlockState shape(BlockState state, LevelAccessor view, BlockPos pos) {
        return connects(state) ? Block.updateFromNeighbourShapes(state, view, pos) : state;
    }
}
