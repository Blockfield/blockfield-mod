package com.lunares.blockfield.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunares.blockfield.network.packet.MenuActionC2SPacket;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public final class LauncherBridge {
    private static final String DIRECTORY = System.getenv("BLOCKFIELD_BRIDGE_DIR");
    private static final String SERVER = System.getenv("BLOCKFIELD_SERVER");
    private static JsonObject presence = new JsonObject();
    private static long receivedAt;
    private static int ticks;
    private static int connectedTicks;
    private static String connectingRequest;

    private LauncherBridge() {}

    public static void update(JsonObject value) {
        presence = value.deepCopy();
        receivedAt = System.currentTimeMillis();
    }

    public static void init() {
        if (DIRECTORY == null || SERVER == null || SERVER.isBlank()) return;
        ClientTickEvents.END_CLIENT_TICK.register(LauncherBridge::tick);
    }

    private static void tick(Minecraft mc) {
        boolean connected =
                mc.player != null
                        && mc.getConnection() != null
                        && mc.getCurrentServer() != null
                        && mc.getCurrentServer().ip.equalsIgnoreCase(SERVER);
        connectedTicks = connected ? connectedTicks + 1 : 0;
        if (++ticks % 20 != 0) return;
        Path directory = Path.of(DIRECTORY);
        try {
            JsonObject value =
                    connected && System.currentTimeMillis() - receivedAt < 15000
                            ? presence.deepCopy()
                            : new JsonObject();
            if (!value.has("state"))
                value.addProperty("state", connected ? "На сервере" : "В главном меню");
            value.addProperty("updatedAt", System.currentTimeMillis());
            Files.writeString(directory.resolve("game-presence.json"), value.toString());
            Path request = directory.resolve("room-request.json");
            if (!Files.isRegularFile(request) || Files.size(request) > 4096) return;
            String requestText = Files.readString(request);
            JsonObject candidate = JsonParser.parseString(requestText).getAsJsonObject();
            if (!validRequest(candidate, System.currentTimeMillis())) {
                Files.deleteIfExists(request);
                return;
            }
            if (!connected) {
                // An invite must not disconnect another server or interrupt an in-progress
                // connection.
                if (mc.level == null
                        && mc.screen instanceof TitleScreen
                        && !requestText.equals(connectingRequest)) {
                    connectingRequest = requestText;
                    ServerData server = new ServerData("Blockfield", SERVER, ServerData.Type.OTHER);
                    ConnectScreen.startConnecting(
                            mc.screen, mc, ServerAddress.parseString(SERVER), server, false, null);
                }
                return;
            }
            if (connectedTicks < 60) return;
            Path consumed = directory.resolve("room-request.processing");
            Files.move(request, consumed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            JsonObject join = JsonParser.parseString(Files.readString(consumed)).getAsJsonObject();
            String id = join.get("room").getAsString();
            Files.deleteIfExists(consumed);
            if (!validRequest(join, System.currentTimeMillis())) return;
            connectingRequest = null;
            ClientPlayNetworking.send(
                    new MenuActionC2SPacket(id.equals("lobby") ? "leave" : "join:" + id));
        } catch (Exception ignored) {
            // A launcher write can overlap this tick; retry after the complete file is visible.
        }
    }

    static boolean validRequest(JsonObject value, long now) {
        if (!value.has("room") || !value.has("createdAt")) return false;
        String target = value.get("room").getAsString();
        long age = now - value.get("createdAt").getAsLong();
        return age >= 0
                && age <= 600000
                && (target.equals("lobby")
                        || target.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"));
    }
}
