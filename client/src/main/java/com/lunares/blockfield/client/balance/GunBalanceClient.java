package com.lunares.blockfield.client.balance;

import com.google.gson.JsonParser;
import com.lunares.blockfield.common.balance.GunBalanceData;
import com.lunares.blockfield.network.packet.GunBalanceS2CPacket;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.network.CommonNetworkCache;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class GunBalanceClient {
    private static final Logger LOGGER = LogManager.getLogger("BlockField-GunBalance");

    private GunBalanceClient() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(
                GunBalanceS2CPacket.TYPE,
                (packet, context) -> context.client().execute(() -> apply(packet)));
    }

    private static void apply(GunBalanceS2CPacket packet) {
        try {
            CommonNetworkCache cache = CommonNetworkCache.INSTANCE;
            Map<ResourceLocation, GunData> changed = new HashMap<>();
            JsonParser.parseString(packet.json())
                    .getAsJsonObject()
                    .entrySet()
                    .forEach(
                            entry -> {
                                ResourceLocation id = ResourceLocation.parse(entry.getKey());
                                if (!cache.gunData.containsKey(id))
                                    throw new IllegalArgumentException("Unknown gun data " + id);
                                GunData data =
                                        CommonAssetsManager.GSON.fromJson(
                                                entry.getValue(), GunData.class);
                                GunBalanceData.validate(data, id.toString(), data);
                                changed.put(id, data);
                            });
            Map<ResourceLocation, GunData> data = new HashMap<>(cache.gunData);
            data.putAll(changed);
            var indices = GunBalanceData.rebuild(changed, cache.gunIndex);
            var player = Minecraft.getInstance().player;
            var attachmentCache =
                    player == null
                            ? null
                            : GunBalanceData.prepareCache(player, indices, changed.keySet());
            cache.gunData = data;
            cache.gunIndex = indices;
            if (player != null) GunBalanceData.installCache(player, attachmentCache);
        } catch (RuntimeException error) {
            LOGGER.error("Rejected gun balance delta; previous gun data retained", error);
        }
    }
}
