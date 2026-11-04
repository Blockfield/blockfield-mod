package com.lunares.blockfield.client;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Автовход через прокси: Velocity присылает случайный serverId, клиент отмечается на Drasl своим
 * токеном из лаунчера, а прокси проверяет hasJoined. Токен на наш сервер не уходит.
 */
public final class DraslAuth {
    private static final ResourceLocation CHANNEL =
            ResourceLocation.fromNamespaceAndPath("blockfield", "auth");
    private static final URI JOIN =
            URI.create(
                    System.getProperty("blockfield.drasl", "https://skins.blockfield.pro")
                            + "/session/minecraft/join");
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private DraslAuth() {}

    public static void init() {
        ClientLoginNetworking.registerGlobalReceiver(
                CHANNEL,
                (client, handler, buf, callbacks) -> {
                    String serverId = buf.readUtf(64);
                    var user = client.getUser();
                    // Offline-аккаунты (Prism без Drasl) несут токен "0": Drasl не спрашиваем,
                    // пробуем запомненный /login.
                    if (user.getAccessToken().length() < 8)
                        return CompletableFuture.completedFuture(
                                DraslSession.reply(user.getName(), serverId));
                    JsonObject body = new JsonObject();
                    body.addProperty("accessToken", user.getAccessToken());
                    body.addProperty(
                            "selectedProfile", user.getProfileId().toString().replace("-", ""));
                    body.addProperty("serverId", serverId);
                    HttpRequest request =
                            HttpRequest.newBuilder(JOIN)
                                    .timeout(Duration.ofSeconds(10))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                                    .build();
                    return HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                            .handle(
                                    (response, error) ->
                                            error == null && response.statusCode() / 100 == 2
                                                    ? joined()
                                                    : DraslSession.reply(user.getName(), serverId));
                });
    }

    private static FriendlyByteBuf joined() {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeByte(1);
        return buf;
    }
}
