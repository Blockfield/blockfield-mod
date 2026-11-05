package com.lunares.blockfield.common.item;

import com.lunares.blockfield.common.BlockFieldCommon;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Typed rig storage: the 9 ammo cells live in this component instead of {@code custom_data.Slots}.
 * The vanilla item codec caps count at 99, so each cell keeps the capped stack plus the real size
 * in {@code amount} — the same split the legacy NBT format used, only parsed once and cached by the
 * components map instead of re-parsed from NBT on every read.
 */
public record RigCells(List<RigCell> cells) {
    /**
     * One nonempty cell. A corrupt entry decodes to {@link #INVALID} and is skipped on read, so a
     * single bad cell never voids the whole rig — the same leniency the legacy Slots loop had.
     */
    public record RigCell(int slot, int amount, ItemStack stack) {
        static final RigCell INVALID = new RigCell(-1, 0, ItemStack.EMPTY);

        private static final Codec<RigCell> STRICT =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                Codec.INT.fieldOf("slot").forGetter(RigCell::slot),
                                                Codec.INT
                                                        .fieldOf("amount")
                                                        .forGetter(RigCell::amount),
                                                ItemStack.CODEC
                                                        .fieldOf("stack")
                                                        .forGetter(RigCell::stack))
                                        .apply(i, RigCell::new));

        static final Codec<RigCell> CODEC = STRICT.orElse(INVALID);

        static final StreamCodec<RegistryFriendlyByteBuf, RigCell> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.INT,
                        RigCell::slot,
                        ByteBufCodecs.VAR_INT,
                        RigCell::amount,
                        ItemStack.STREAM_CODEC,
                        RigCell::stack,
                        RigCell::new);
    }

    public static final Codec<RigCells> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            RigCell.CODEC
                                                    .listOf()
                                                    .fieldOf("cells")
                                                    .forGetter(RigCells::cells))
                                    .apply(i, RigCells::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, RigCells> STREAM_CODEC =
            RigCell.STREAM_CODEC.apply(ByteBufCodecs.list()).map(RigCells::new, RigCells::cells);

    public static final DataComponentType<RigCells> TYPE =
            Registry.register(
                    BuiltInRegistries.DATA_COMPONENT_TYPE,
                    ResourceLocation.fromNamespaceAndPath(BlockFieldCommon.MOD_ID, "rig_slots"),
                    DataComponentType.<RigCells>builder()
                            .persistent(CODEC)
                            .networkSynchronized(STREAM_CODEC)
                            .build());

    public static final RigCells EMPTY = new RigCells(List.of());

    /** Вызывается из BlockFieldCommon.onInitialize на обеих сторонах. */
    public static void register() {}

    /**
     * Canonical builder from a slot list: empties skipped, stack capped at the codec ceiling with
     * the real size kept in {@code amount}. Copies every stored stack.
     */
    public static RigCells pack(List<ItemStack> slots) {
        List<RigCell> cells = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            ItemStack slot = slots.get(i);
            if (slot.isEmpty() || slot.getCount() <= 0) continue;
            ItemStack stored = slot.copy();
            if (stored.getCount() > 99) stored.setCount(99);
            cells.add(new RigCell(i, slot.getCount(), stored));
        }
        return new RigCells(List.copyOf(cells));
    }

    /**
     * Full slot list (empties are {@link ItemStack#EMPTY}); last entry per slot wins, like the
     * legacy loop. Every returned stack is a copy the caller may mutate.
     */
    public List<ItemStack> unpack(int slotCount) {
        ItemStack[] out = new ItemStack[slotCount];
        Arrays.fill(out, ItemStack.EMPTY);
        for (RigCell cell : cells) {
            if (cell.slot < 0 || cell.slot >= slotCount) continue;
            if (cell.amount <= 0 || cell.stack.isEmpty()) continue;
            ItemStack stored = cell.stack.copy();
            stored.setCount(cell.amount);
            if (stored.isEmpty()) continue;
            out[cell.slot] = stored;
        }
        return new ArrayList<>(Arrays.asList(out));
    }
}
