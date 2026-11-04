package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Способ броска для следующего нативного use гранаты: клиент шлёт его прямо перед use. Скорость,
 * запал и расход считает мод; сервер только замедляет короткий бросок (GrenadeThrowService).
 */
public record GrenadeThrowC2SPacket(int mode, int slot) implements CustomPacketPayload {
    public static final Type<GrenadeThrowC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "grenade_throw"));
    public static final StreamCodec<FriendlyByteBuf, GrenadeThrowC2SPacket> CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeByte(packet.mode);
                        buf.writeByte(packet.slot);
                    },
                    buf -> new GrenadeThrowC2SPacket(buf.readByte(), buf.readByte()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
