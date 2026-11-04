package com.lunares.blockfield.client.workshop;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Optional WorldEdit API; resolved only after the loader confirms WorldEdit is installed. */
final class WorkshopWorldEditCui {
    private final CustomPacketPayload.Type<CustomPacketPayload> type;
    private final Constructor<?> constructor;
    private final Method text;

    WorkshopWorldEditCui() {
        this(packetClass());
    }

    @SuppressWarnings("unchecked")
    WorkshopWorldEditCui(Class<? extends CustomPacketPayload> packet) {
        try {
            type =
                    (CustomPacketPayload.Type<CustomPacketPayload>)
                            packet.getField("TYPE").get(null);
            constructor = packet.getConstructor(String.class);
            text = packet.getMethod("text");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported WorldEdit CUI API", failure);
        }
    }

    private static Class<? extends CustomPacketPayload> packetClass() {
        try {
            return Class.forName(
                            "com.sk89q.worldedit.fabric.net.handler.WECUIPacketHandler$CuiPacket")
                    .asSubclass(CustomPacketPayload.class);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("WorldEdit CUI packet missing", failure);
        }
    }

    CustomPacketPayload.Type<CustomPacketPayload> type() {
        return type;
    }

    CustomPacketPayload payload(String value) {
        try {
            return (CustomPacketPayload) constructor.newInstance(value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot construct WorldEdit CUI packet", failure);
        }
    }

    String text(CustomPacketPayload payload) {
        try {
            String value = (String) text.invoke(payload);
            return value != null && value.length() <= WorkshopWandSelection.MAX_TEXT ? value : null;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read WorldEdit CUI packet", failure);
        }
    }
}
