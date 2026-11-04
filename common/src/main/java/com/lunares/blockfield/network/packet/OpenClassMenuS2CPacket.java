package com.lunares.blockfield.network.packet;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Class menu content. The wire stays a UTF JSON string plus the update flag; decoding validates the
 * envelope and degrades to {@link #invalid()} instead of throwing.
 */
public record OpenClassMenuS2CPacket(JsonObject classes, boolean isUpdate)
        implements CustomPacketPayload {
    public static final Type<OpenClassMenuS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "open_class_menu"));

    /** Class entries keyed by class id are dynamic; the envelope only guarantees an object. */
    public static final Codec<JsonObject> JSON_CODEC = UiJson.codec(Map.of());

    public static final StreamCodec<FriendlyByteBuf, OpenClassMenuS2CPacket> CODEC =
            CustomPacketPayload.codec(OpenClassMenuS2CPacket::write, OpenClassMenuS2CPacket::read);

    private static final int MAX_LENGTH = 262144;
    private static final OpenClassMenuS2CPacket INVALID =
            new OpenClassMenuS2CPacket(new JsonObject(), false);

    public OpenClassMenuS2CPacket {
        if (classes == null) throw new IllegalArgumentException("Classes are required");
    }

    /** Server builders keep passing the Gson string; malformed input fails fast. */
    public OpenClassMenuS2CPacket(String classesJson) {
        this(classesJson, false);
    }

    /** Server builders keep passing the Gson string; malformed input fails fast. */
    public OpenClassMenuS2CPacket(String classesJson, boolean isUpdate) {
        this(UiJson.parse(classesJson), isUpdate);
    }

    public String classesJson() {
        return classes.toString();
    }

    /** False only for a wire payload that failed validation; receivers ignore those. */
    public boolean valid() {
        return this != INVALID;
    }

    /** Malformed payloads degrade to the invalid sentinel instead of throwing. */
    static OpenClassMenuS2CPacket decode(String raw, boolean isUpdate) {
        JsonObject root = UiJson.decodeValid(raw, JSON_CODEC);
        return root == null ? INVALID : new OpenClassMenuS2CPacket(root, isUpdate);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(classes.toString(), MAX_LENGTH);
        buf.writeBoolean(isUpdate);
    }

    private static OpenClassMenuS2CPacket read(FriendlyByteBuf buf) {
        return decode(buf.readUtf(MAX_LENGTH), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
