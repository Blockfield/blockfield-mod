package com.lunares.blockfield.network.packet;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Generic server menu. The wire stays a UTF JSON string; decoding validates the envelope and
 * degrades to {@link #invalid()} instead of throwing.
 */
public record OpenMenuS2CPacket(JsonObject menu) implements CustomPacketPayload {
    public static final Type<OpenMenuS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "open_menu"));
    public static final Codec<JsonObject> JSON_CODEC =
            UiJson.codec(
                    Map.of(
                            "id", UiJson.Kind.STRING,
                            "close", UiJson.Kind.BOOLEAN,
                            "update", UiJson.Kind.BOOLEAN,
                            "timer", UiJson.Kind.NUMBER,
                            "sections", UiJson.Kind.ARRAY));
    public static final StreamCodec<FriendlyByteBuf, OpenMenuS2CPacket> CODEC =
            CustomPacketPayload.codec(OpenMenuS2CPacket::write, OpenMenuS2CPacket::read);

    private static final int MAX_LENGTH = 262144;
    private static final OpenMenuS2CPacket INVALID = new OpenMenuS2CPacket(new JsonObject());

    public OpenMenuS2CPacket {
        if (menu == null) throw new IllegalArgumentException("Menu is required");
    }

    /** Server builders keep passing the Gson string; malformed input fails fast. */
    public OpenMenuS2CPacket(String json) {
        this(UiJson.parse(json));
    }

    public String json() {
        return menu.toString();
    }

    /** Menu id for routing (workshop radial vs tablet); empty when absent, as before. */
    public String id() {
        return UiJson.str(menu, "id", "");
    }

    public boolean close() {
        return UiJson.bool(menu, "close", false);
    }

    public boolean update() {
        return UiJson.bool(menu, "update", false);
    }

    /** False only for a wire payload that failed validation; receivers ignore those. */
    public boolean valid() {
        return this != INVALID;
    }

    /** Malformed payloads degrade to the invalid sentinel instead of throwing. */
    static OpenMenuS2CPacket decode(String raw) {
        JsonObject root = UiJson.decodeValid(raw, JSON_CODEC);
        return root == null ? INVALID : new OpenMenuS2CPacket(root);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(menu.toString(), MAX_LENGTH);
    }

    private static OpenMenuS2CPacket read(FriendlyByteBuf buf) {
        return decode(buf.readUtf(MAX_LENGTH));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
