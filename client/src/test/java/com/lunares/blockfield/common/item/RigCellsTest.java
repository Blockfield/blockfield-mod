package com.lunares.blockfield.common.item;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.ModItems;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Typed rig storage: canonical pack/unpack, codec + stream round-trips, and the legacy {@code
 * custom_data.Slots} fallback (every on-disk variant) until the server migrator converts old rigs.
 */
class RigCellsTest {

    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        unfreeze(BuiltInRegistries.ITEM);
        unfreeze(BuiltInRegistries.DATA_COMPONENT_TYPE);
        Registry.register(
                BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath("tacz", "ammo"),
                new Item(new Item.Properties()));
        ModItems.register();
        RigCells.register();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static void unfreeze(Registry<?> registry) throws Exception {
        MappedRegistry<?> mapped = (MappedRegistry<?>) registry;
        Field frozen = MappedRegistry.class.getDeclaredField("frozen");
        frozen.setAccessible(true);
        frozen.set(mapped, false);
        // Holder-мапу трогаем только у ITEM: предмет создаёт intrusive holder в конструкторе,
        // который freeze обнуляет. У остальных реестров сброс мапы ломает регистрацию.
        if (registry != BuiltInRegistries.ITEM) return;
        Field intrusive = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
        intrusive.setAccessible(true);
        intrusive.set(mapped, new java.util.IdentityHashMap<>());
    }

    private static ItemStack namedAmmo(int count) {
        ItemStack stack = new ItemStack(Items.ARROW);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("9x19"));
        stack.setCount(count);
        return stack;
    }

    @Test
    void packCapsStackAt99KeepingAmountAndComponents() {
        RigCells cells = RigCells.pack(List.of(namedAmmo(5000), ItemStack.EMPTY));

        assertEquals(1, cells.cells().size());
        assertEquals(0, cells.cells().get(0).slot());
        assertEquals(5000, cells.cells().get(0).amount());
        assertEquals(99, cells.cells().get(0).stack().getCount());
        assertEquals(
                Component.literal("9x19"),
                cells.cells().get(0).stack().get(DataComponents.CUSTOM_NAME));
    }

    @Test
    void codecRoundTripKeepsOverCapAndSkipsCorruptCell() {
        RigCells cells = RigCells.pack(List.of(ItemStack.EMPTY, namedAmmo(120_500)));

        CompoundTag tag =
                (CompoundTag)
                        RigCells.CODEC
                                .encodeStart(NbtOps.INSTANCE, cells)
                                .resultOrPartial()
                                .orElseThrow();
        // One corrupt cell must not void the whole rig.
        tag.getList("cells", CompoundTag.TAG_COMPOUND).add(new CompoundTag());
        RigCells decoded =
                RigCells.CODEC.parse(NbtOps.INSTANCE, tag).resultOrPartial().orElseThrow();

        List<ItemStack> slots = decoded.unpack(ChestRigMenu.RIG_SLOT_COUNT);
        assertTrue(slots.get(0).isEmpty());
        assertEquals(120_500, slots.get(1).getCount());
        assertEquals(Component.literal("9x19"), slots.get(1).get(DataComponents.CUSTOM_NAME));
    }

    @Test
    void streamRoundTripKeepsOverCap() {
        RigCells cells = RigCells.pack(List.of(namedAmmo(77_000)));

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        RigCells.STREAM_CODEC.encode(buf, cells);
        RigCells decoded = RigCells.STREAM_CODEC.decode(buf);

        assertEquals(77_000, decoded.unpack(ChestRigMenu.RIG_SLOT_COUNT).get(0).getCount());
    }

    @Test
    void storedStacksReadsComponentFirst() {
        ItemStack rig = new ItemStack(ModItems.CHEST_RIG);
        rig.set(RigCells.TYPE, RigCells.pack(List.of(namedAmmo(5000))));

        List<ItemStack> stored = ChestRigAmmo.storedStacks(rig, registries);
        assertEquals(1, stored.size());
        assertEquals(5000, stored.get(0).getCount());
        assertEquals(Items.ARROW, stored.get(0).getItem());
    }

    @Test
    void storedStacksFallsBackToEveryLegacyVariant() {
        ItemStack rig = new ItemStack(ModItems.CHEST_RIG);
        ListTag slots = new ListTag();
        // {stack} + Amount, over the codec cap.
        CompoundTag capped = new CompoundTag();
        capped.putByte("Slot", (byte) 0);
        ItemStack saved = namedAmmo(64);
        saved.set(DataComponents.MAX_STACK_SIZE, 99);
        capped.put("stack", saved.save(registries));
        capped.putInt("Amount", 461);
        slots.add(capped);
        // {stack} without Amount.
        CompoundTag plain = new CompoundTag();
        plain.putByte("Slot", (byte) 2);
        plain.put("stack", namedAmmo(12).save(registries));
        slots.add(plain);
        // Oldest {id, Count} format.
        CompoundTag oldest = new CompoundTag();
        oldest.putByte("Slot", (byte) 4);
        oldest.putString("id", "minecraft:arrow");
        oldest.putInt("Count", 30);
        slots.add(oldest);
        CompoundTag custom = new CompoundTag();
        custom.put("Slots", slots);
        rig.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));

        List<ItemStack> stored = ChestRigAmmo.storedStacks(rig, registries);
        assertEquals(3, stored.size());
        assertEquals(461, stored.get(0).getCount());
        assertEquals(Integer.valueOf(99), stored.get(0).get(DataComponents.MAX_STACK_SIZE));
        assertEquals(12, stored.get(1).getCount());
        assertEquals(30, stored.get(2).getCount());
    }

    @Test
    void componentBeatsStaleLegacy() {
        ItemStack rig = new ItemStack(ModItems.CHEST_RIG);
        rig.set(RigCells.TYPE, RigCells.pack(List.of(namedAmmo(7))));
        CompoundTag oldest = new CompoundTag();
        oldest.putByte("Slot", (byte) 0);
        oldest.putString("id", "minecraft:arrow");
        oldest.putInt("Count", 3);
        ListTag slots = new ListTag();
        slots.add(oldest);
        CompoundTag custom = new CompoundTag();
        custom.put("Slots", slots);
        rig.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));

        List<ItemStack> stored = ChestRigAmmo.storedStacks(rig, registries);
        assertEquals(1, stored.size());
        assertEquals(7, stored.get(0).getCount());
    }
}
