package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Запрос «открыть клетки разгрузочного жилета» с клиентской клавиши key.blockfield.rig. Жилет надет
 * всегда и предметом в руке не открывается, поэтому открытие идёт по клавише: сервер решает, есть
 * ли жилет, и поднимает ванильный контейнер (ChestRigManager.openInventory).
 */
public class RigOpenC2SPacket implements CustomPacketPayload {
    public static final RigOpenC2SPacket INSTANCE = new RigOpenC2SPacket();
    public static final Type<RigOpenC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "rig_open"));
    public static final StreamCodec<FriendlyByteBuf, RigOpenC2SPacket> CODEC =
            StreamCodec.unit(INSTANCE);

    private RigOpenC2SPacket() {}

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
