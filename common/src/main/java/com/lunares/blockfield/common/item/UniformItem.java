package com.lunares.blockfield.common.item;

import net.minecraft.world.item.Item;

/** Cosmetic only: protection and armor plates remain on the equipped combat armor. */
public final class UniformItem extends Item {
    public enum Role {
        INFANTRY,
        ENGINEER,
        SNIPER,
        AIRBORNE,
        DRONE,
        DM
    }

    public enum Side {
        RED,
        BLUE,
        NEUTRAL
    }

    public final Role role;
    public final Side side;

    public UniformItem(Role role, Side side) {
        super(new Item.Properties().stacksTo(1));
        this.role = role;
        this.side = side;
    }
}
