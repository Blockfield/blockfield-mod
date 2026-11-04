package com.lunares.blockfield.common.compat;

import com.lunares.blockfield.common.item.ChestRigAmmo;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

/**
 * Both logical sides must see the same creative-box source for reload prediction and consumption.
 */
public final class SuperbWarfareRigAmmo {
    private SuperbWarfareRigAmmo() {}

    public static boolean hasItemInEquippedRig(Object owner, Item item) {
        if (!(owner instanceof Player player) || !ChestRigAmmo.isSuperbCreativeBox(item))
            return false;
        if (!FabricLoader.getInstance().isModLoaded("accessories")) return false;
        return Accessories.containsBox(player);
    }

    private static java.lang.invoke.MethodHandle isAmmoItem;

    /**
     * SBW AmmoConsumer.isAmmoItem; SBW is not on the compile classpath, its method name is not
     * remapped.
     */
    public static boolean isConsumerAmmo(
            Object consumer, net.minecraft.world.item.ItemStack stack) {
        try {
            if (isAmmoItem == null)
                isAmmoItem =
                        java.lang.invoke.MethodHandles.publicLookup()
                                .findVirtual(
                                        consumer.getClass(),
                                        "isAmmoItem",
                                        java.lang.invoke.MethodType.methodType(
                                                boolean.class,
                                                net.minecraft.world.item.ItemStack.class));
            // SBW compares components; rig cells carry a MAX_STACK_SIZE override (ChestRigManager).
            // remove() would leave a "removed" patch entry, so restore the prototype value instead.
            var type = net.minecraft.core.component.DataComponents.MAX_STACK_SIZE;
            var plain = stack.copy();
            Integer base = plain.getItem().components().get(type);
            if (base == null) plain.remove(type);
            else plain.set(type, base);
            return (boolean) isAmmoItem.invoke(consumer, plain);
        } catch (Throwable e) {
            throw new IllegalStateException("AmmoConsumer.isAmmoItem недоступен", e);
        }
    }

    /**
     * Rig cells matching the SBW ammo consumer; both sides, so the HUD and reload prediction agree
     * with the server.
     */
    public static int countInEquippedRig(Object owner, Object consumer) {
        if (!(owner instanceof Player player)
                || !FabricLoader.getInstance().isModLoaded("accessories")) return 0;
        int count = 0;
        for (var stack : Accessories.stored(player))
            if (ChestRigAmmo.accepts(stack) && isConsumerAmmo(consumer, stack))
                count += stack.getCount();
        return count;
    }

    /**
     * Loaded only with Accessories installed; no client-only classes and no hard SBW dependency.
     */
    private static final class Accessories {
        private static java.util.List<net.minecraft.world.item.ItemStack> stored(Player player) {
            var capability = io.wispforest.accessories.api.AccessoriesCapability.get(player);
            var container = capability == null ? null : capability.getContainers().get("rig");
            if (container == null) return java.util.List.of();
            var equipped = container.getAccessories();
            java.util.List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>();
            for (int i = 0; i < equipped.getContainerSize(); i++) {
                out.addAll(ChestRigAmmo.storedStacks(equipped.getItem(i), player.registryAccess()));
            }
            return out;
        }

        private static boolean containsBox(Player player) {
            for (var stack : stored(player))
                if (ChestRigAmmo.isSuperbCreativeBox(stack.getItem())) return true;
            return false;
        }
    }
}
