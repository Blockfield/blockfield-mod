package com.lunares.blockfield.client.gui;

import com.lunares.blockfield.client.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Кнопка «Играть» в главном меню ведёт сразу на сервер проекта, минуя список серверов. Адрес
 * берётся из -Dblockfield.server, затем config/blockfield-client.properties (ship модпаком), затем
 * встроенный дефолт из content.json.
 */
public final class DirectConnect {
    private static final String DEFAULT_SERVER = "raknet;play.blockfield.pro:25566";

    private DirectConnect() {}

    public static String serverAddress() {
        String property = System.getProperty("blockfield.server");
        if (property != null && !property.isBlank()) return property.trim();
        return ClientConfig.PACK.get("server", DEFAULT_SERVER);
    }

    public static void connect(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        String address = serverAddress();
        ServerData data = new ServerData("Blockfield", address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(
                parent, minecraft, ServerAddress.parseString(address), data, false, null);
    }
}
