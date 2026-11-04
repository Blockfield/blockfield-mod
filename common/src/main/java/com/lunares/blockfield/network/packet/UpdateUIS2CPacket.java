package com.lunares.blockfield.network.packet;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Per-tick HUD/tablet state. The wire stays a UTF JSON string; decoding validates known envelope
 * kinds and degrades to {@link #invalid()} instead of throwing, so a malformed payload is ignored
 * downstream exactly like before.
 */
public record UpdateUIS2CPacket(JsonObject ui) implements CustomPacketPayload {
    public static final Type<UpdateUIS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "update_ui"));
    public static final Codec<JsonObject> JSON_CODEC =
            UiJson.codec(
                    Map.ofEntries(
                            Map.entry("tabActive", UiJson.Kind.BOOLEAN),
                            Map.entry("showHud", UiJson.Kind.BOOLEAN),
                            Map.entry("dead", UiJson.Kind.BOOLEAN),
                            Map.entry("resetStamina", UiJson.Kind.BOOLEAN),
                            Map.entry("workshopAi", UiJson.Kind.BOOLEAN),
                            Map.entry("workshopBuild", UiJson.Kind.BOOLEAN),
                            Map.entry("workshopGhost", UiJson.Kind.BOOLEAN),
                            Map.entry("closeDeploymentMenu", UiJson.Kind.BOOLEAN),
                            Map.entry("ffa", UiJson.Kind.BOOLEAN),
                            Map.entry("removeScore", UiJson.Kind.STRING),
                            Map.entry("playerTeam", UiJson.Kind.STRING),
                            Map.entry("rT", UiJson.Kind.NUMBER),
                            Map.entry("bT", UiJson.Kind.NUMBER),
                            Map.entry("timeLeft", UiJson.Kind.NUMBER),
                            Map.entry("deathTimer", UiJson.Kind.NUMBER),
                            Map.entry("safeSpawnThreshold", UiJson.Kind.NUMBER),
                            Map.entry("killLimit", UiJson.Kind.NUMBER),
                            Map.entry("maxT", UiJson.Kind.NUMBER),
                            Map.entry("deploymentCost", UiJson.Kind.NUMBER),
                            Map.entry("battleCredits", UiJson.Kind.NUMBER),
                            Map.entry("pts", UiJson.Kind.ARRAY),
                            Map.entry("map", UiJson.Kind.OBJECT),
                            Map.entry("zone", UiJson.Kind.OBJECT),
                            Map.entry("classes", UiJson.Kind.OBJECT),
                            Map.entry("creditGain", UiJson.Kind.OBJECT),
                            Map.entry("presence", UiJson.Kind.OBJECT)));
    public static final StreamCodec<FriendlyByteBuf, UpdateUIS2CPacket> CODEC =
            CustomPacketPayload.codec(UpdateUIS2CPacket::write, UpdateUIS2CPacket::read);

    private static final int MAX_LENGTH = 262144;
    private static final UpdateUIS2CPacket INVALID = new UpdateUIS2CPacket(new JsonObject());

    public UpdateUIS2CPacket {
        if (ui == null) throw new IllegalArgumentException("UI data is required");
    }

    /** Server builders keep passing the Gson string; malformed input fails fast. */
    public UpdateUIS2CPacket(String uiDataJson) {
        this(UiJson.parse(uiDataJson));
    }

    public String uiDataJson() {
        return ui.toString();
    }

    /** False only for a wire payload that failed validation; receivers ignore those. */
    public boolean valid() {
        return this != INVALID;
    }

    /** Malformed payloads degrade to the invalid sentinel instead of throwing. */
    static UpdateUIS2CPacket decode(String raw) {
        JsonObject root = UiJson.decodeValid(raw, JSON_CODEC);
        return root == null ? INVALID : new UpdateUIS2CPacket(root);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(ui.toString(), MAX_LENGTH);
    }

    private static UpdateUIS2CPacket read(FriendlyByteBuf buf) {
        return decode(buf.readUtf(MAX_LENGTH));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
