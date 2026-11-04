package com.lunares.blockfield.common;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Trapdoors, levers and item frames on maps are locked: without the right to build (adventure:
 * matches, hub, firing range) a player cannot open a trapdoor, flip a lever, turn a frame's item or
 * knock it out -- by hand, with a gun or through the TACZ interact key, which ends in the same use.
 * A lever drives the map's redstone, so one flip changes the map for everyone. Registered on both
 * sides, so the client does not predict the change and flicker it back. Creative and workshop
 * builders keep them.
 */
public final class DecorInteractions {
    private DecorInteractions() {}

    private static boolean registered;

    public static synchronized void register() {
        // The game jar runs both the common and the server entrypoint, so on the Workshop
        // profile this arrives twice: the callbacks are pure checks, but register once anyway.
        if (registered) return;
        registered = true;
        UseBlockCallback.EVENT.register(
                (player, level, hand, hit) ->
                        locked(player) && isLocked(level.getBlockState(hit.getBlockPos()))
                                ? InteractionResult.FAIL
                                : InteractionResult.PASS);
        UseEntityCallback.EVENT.register(
                (player, level, hand, entity, hit) -> frameLocked(player, entity));
        AttackEntityCallback.EVENT.register(
                (player, level, hand, entity, hit) -> frameLocked(player, entity));
    }

    /** Blocks a player without the right to build cannot use. */
    static boolean isLocked(BlockState state) {
        return isTrapdoor(state) || state.getBlock() instanceof LeverBlock;
    }

    static boolean isTrapdoor(BlockState state) {
        return state.getBlock() instanceof TrapDoorBlock || state.is(BlockTags.TRAPDOORS);
    }

    private static boolean locked(Player player) {
        return !player.getAbilities().mayBuild;
    }

    private static InteractionResult frameLocked(Player player, Entity entity) {
        return locked(player) && entity instanceof ItemFrame
                ? InteractionResult.FAIL
                : InteractionResult.PASS;
    }
}
