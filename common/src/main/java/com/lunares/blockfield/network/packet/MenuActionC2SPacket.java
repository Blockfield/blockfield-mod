package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Единственный канал действий игрока: вместо команд в чат все кнопки экранов шлют структурированное
 * действие ("join:1", "class:assault:0", "spawn:BASE", ...). Разбор и проверки — на сервере, см.
 * BlockFieldServer.handleMenuAction.
 */
public class MenuActionC2SPacket implements CustomPacketPayload {
    public static final Type<MenuActionC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "menu_action"));
    public static final int MAX_LENGTH = 256;
    public static final StreamCodec<FriendlyByteBuf, MenuActionC2SPacket> CODEC =
            CustomPacketPayload.codec(MenuActionC2SPacket::toBytes, MenuActionC2SPacket::new);

    private final String action;

    public MenuActionC2SPacket(String action) {
        this.action = action;
    }

    public MenuActionC2SPacket(FriendlyByteBuf buf) {
        this.action = buf.readUtf(MAX_LENGTH);
    }

    public String action() {
        return this.action;
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUtf(this.action, MAX_LENGTH);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
