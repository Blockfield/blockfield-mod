package com.lunares.blockfield.oracle;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;

/** Real pinned SBW item classes with only their registry holder/components fixture-supplied. */
public final class FxClientItems {
    private FxClientItems() {}

    public static ItemStack stack(String id) throws Exception {
        if (id == null) return ItemStack.EMPTY;
        String type =
                switch (id) {
                    case "glock_17" -> "gun.handgun.Glock17Item";
                    case "lunge_mine" -> "LungeMine";
                    case "minigun" -> "gun.machinegun.MinigunItem";
                    case "m2hb" -> "gun.machinegun.M2HBItem";
                    case "super_star_shooter" -> "gun.launcher.SuperStarShooterItem";
                    default -> throw new IllegalArgumentException(id);
                };
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = field.get(null);
        var item =
                (Item)
                        unsafe.getClass()
                                .getMethod("allocateInstance", Class.class)
                                .invoke(
                                        unsafe,
                                        Class.forName("com.atsuishio.superbwarfare.item." + type));
        var registry = new MappedRegistry<Item>(Registries.ITEM, Lifecycle.stable(), true);
        var holder = registry.createIntrusiveHolder(item);
        set(
                Item.class,
                item,
                "builtInRegistryHolder",
                "Lnet/minecraft/core/Holder$Reference;",
                holder);
        set(
                Item.class,
                item,
                "components",
                "Lnet/minecraft/core/component/DataComponentMap;",
                Items.STICK.components());
        Registry.register(
                registry, ResourceLocation.fromNamespaceAndPath("superbwarfare", id), item);
        registry.freeze();
        return new ItemStack(item);
    }

    private static void set(
            Class<?> owner, Object target, String name, String descriptor, Object value)
            throws Exception {
        var field = OracleReflection.field(owner, name, descriptor);
        field.setAccessible(true);
        field.set(target, value);
    }
}
