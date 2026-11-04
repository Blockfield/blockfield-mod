package com.lunares.blockfield.network.packet;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Spawn menu content. The wire stays a UTF JSON string plus the map id; decoding validates the
 * envelope and degrades to {@link #invalid()} instead of throwing.
 */
public record OpenSpawnMenuS2CPacket(JsonObject spawns, String mapId)
        implements CustomPacketPayload {
    public static final Type<OpenSpawnMenuS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "open_spawn_menu"));
    public static final Codec<JsonObject> JSON_CODEC =
            UiJson.codec(
                    Map.of(
                            "ffa", UiJson.Kind.BOOLEAN,
                            "fixedClass", UiJson.Kind.BOOLEAN,
                            "bounds", UiJson.Kind.OBJECT,
                            "pts", UiJson.Kind.ARRAY));
    public static final StreamCodec<FriendlyByteBuf, OpenSpawnMenuS2CPacket> CODEC =
            CustomPacketPayload.codec(OpenSpawnMenuS2CPacket::write, OpenSpawnMenuS2CPacket::read);

    private static final int MAX_LENGTH = 262144;
    private static final OpenSpawnMenuS2CPacket INVALID =
            new OpenSpawnMenuS2CPacket(new JsonObject(), "");

    public OpenSpawnMenuS2CPacket {
        if (spawns == null || mapId == null)
            throw new IllegalArgumentException("Spawns are required");
    }

    /** Server builders keep passing the Gson string; malformed input fails fast. */
    public OpenSpawnMenuS2CPacket(String spawnsJson, String mapId) {
        this(UiJson.parse(spawnsJson), mapId);
    }

    public String spawnsJson() {
        return spawns.toString();
    }

    /** False only for a wire payload that failed validation; receivers ignore those. */
    public boolean valid() {
        return this != INVALID;
    }

    /** Malformed payloads degrade to the invalid sentinel instead of throwing. */
    static OpenSpawnMenuS2CPacket decode(String raw, String mapId) {
        JsonObject root = UiJson.decodeValid(raw, JSON_CODEC);
        return root == null ? INVALID : new OpenSpawnMenuS2CPacket(root, mapId);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(spawns.toString(), MAX_LENGTH);
        buf.writeUtf(mapId, 256);
    }

    private static OpenSpawnMenuS2CPacket read(FriendlyByteBuf buf) {
        return decode(buf.readUtf(MAX_LENGTH), buf.readUtf(256));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
