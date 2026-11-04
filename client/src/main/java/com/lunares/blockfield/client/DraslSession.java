package com.lunares.blockfield.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Запомненный вход через прокси для игроков без токена Drasl: после /login прокси присылает JWT и
 * ключ, а при следующем входе клиент отвечает JWT и HMAC(ключ, serverId) — сам ключ наружу не
 * уходит.
 */
public final class DraslSession {
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("blockfield-session.json");

    public record Payload(String token, String key) implements CustomPacketPayload {
        public static final Type<Payload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "session"));
        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC =
                StreamCodec.of(
                        (buf, p) -> {
                            buf.writeUtf(p.token, 2048);
                            buf.writeUtf(p.key, 64);
                        },
                        buf -> new Payload(buf.readUtf(2048), buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private DraslSession() {}

    public static void init() {
        PayloadTypeRegistry.playS2C().register(Payload.TYPE, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(
                Payload.TYPE,
                (payload, context) ->
                        save(context.client().getUser().getName(), payload.token(), payload.key()));
    }

    /** Ответ на login-запрос blockfield:auth, когда Drasl вход не подтвердил. */
    static FriendlyByteBuf reply(String name, String serverId) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        try {
            JsonObject session = load().getAsJsonObject(name);
            if (session != null) {
                String token = session.get("token").getAsString();
                String proof =
                        HexFormat.of()
                                .formatHex(
                                        hmac(
                                                HexFormat.of()
                                                        .parseHex(session.get("key").getAsString()),
                                                serverId));
                buf.writeByte(2);
                buf.writeUtf(token, 2048);
                buf.writeUtf(proof, 64);
                return buf;
            }
        } catch (RuntimeException ignored) {
            // Испорченный файл сессии: лучше /login в лобби, чем обрыв входа.
            buf.clear();
        }
        buf.writeByte(0);
        return buf;
    }

    private static synchronized void save(String name, String token, String key) {
        JsonObject all = load();
        if (token.isEmpty()) {
            all.remove(name);
        } else {
            JsonObject session = new JsonObject();
            session.addProperty("token", token);
            session.addProperty("key", key);
            all.add(name, session);
        }
        try {
            Files.writeString(FILE, all.toString());
        } catch (java.io.IOException ignored) {
            // Не записали — значит, в следующий раз попросят /login.
        }
    }

    private static JsonObject load() {
        try {
            return JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
