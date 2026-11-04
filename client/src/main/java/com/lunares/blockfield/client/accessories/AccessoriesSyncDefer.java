package com.lunares.blockfield.client.accessories;

import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.networking.client.InvalidateEntityCache;
import io.wispforest.accessories.networking.client.SyncContainerData;
import io.wispforest.accessories.networking.client.SyncEntireContainer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Join/dimension-change race: the server container sync lands before the client slot map, so the
 * capability is still null and the packet is dropped. Defer it on the client tick until the
 * capability exists, bounded, then drop with a debug log.
 */
@Environment(EnvType.CLIENT)
public final class AccessoriesSyncDefer {
    private static final Logger LOGGER = LogManager.getLogger("BlockField-Client");

    static final int RETRY_TICKS = 200;
    static final int MAX_PENDING = 64;

    /** Pure pending-packet budget: no Minecraft types, unit-tested. */
    static final class PendingSyncs {
        record Entry(int entityId, Record packet, long enqueueTick) {}

        private final ArrayDeque<Entry> queue = new ArrayDeque<>();

        int size() {
            return queue.size();
        }

        void offer(int entityId, Record packet, long nowTick) {
            while (queue.size() >= MAX_PENDING) queue.pollFirst();
            queue.offerLast(new Entry(entityId, packet, nowTick));
        }

        void collect(
                long nowTick,
                IntPredicate capabilityReady,
                List<Entry> replayOut,
                List<Entry> expiredOut) {
            for (var it = queue.iterator(); it.hasNext(); ) {
                var entry = it.next();
                if (capabilityReady.test(entry.entityId())) {
                    it.remove();
                    replayOut.add(entry);
                } else if (nowTick - entry.enqueueTick() > RETRY_TICKS) {
                    it.remove();
                    expiredOut.add(entry);
                }
            }
        }

        void clear() {
            queue.clear();
        }
    }

    private static final PendingSyncs PENDING = new PendingSyncs();
    private static final ArrayList<PendingSyncs.Entry> REPLAY = new ArrayList<>();
    private static final ArrayList<PendingSyncs.Entry> EXPIRED = new ArrayList<>();
    private static volatile long tick;

    private AccessoriesSyncDefer() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(AccessoriesSyncDefer::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PENDING.clear());
    }

    /**
     * Returns true when the caller must skip handling now: the packet is queued for replay once the
     * capability exists, or it was dropped after the retry budget.
     */
    public static boolean deferIfNoCapability(int entityId, Record packet) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || capabilityOf(mc, entityId) == null) {
            PENDING.offer(entityId, packet, tick);
            return true;
        }
        return false;
    }

    private static AccessoriesCapability capabilityOf(Minecraft mc, int entityId) {
        var entity = mc.level.getEntity(entityId);
        return entity instanceof LivingEntity living ? AccessoriesCapability.get(living) : null;
    }

    private static void tick(Minecraft mc) {
        tick++;
        if (PENDING.size() == 0) return;
        REPLAY.clear();
        EXPIRED.clear();
        PENDING.collect(
                tick,
                entityId -> mc.level != null && capabilityOf(mc, entityId) != null,
                REPLAY,
                EXPIRED);
        var player = mc.player;
        if (player != null && mc.level != null) {
            for (var entry : REPLAY) {
                replay(entry.packet(), player);
                LOGGER.debug(
                        "Replaying deferred accessories sync [EntityId: {}]", entry.entityId());
            }
        } else {
            // No local player yet: re-queue replayable packets instead of losing them.
            for (var entry : REPLAY) PENDING.offer(entry.entityId(), entry.packet(), tick);
        }
        for (var entry : EXPIRED) {
            LOGGER.debug(
                    "Dropping deferred accessories sync, capability never attached [EntityId: {}]",
                    entry.entityId());
        }
    }

    private static void replay(Record packet, Player player) {
        if (packet instanceof SyncEntireContainer sync)
            SyncEntireContainer.handlePacket(sync, player);
        else if (packet instanceof SyncContainerData sync)
            SyncContainerData.handlePacket(sync, player);
        else if (packet instanceof InvalidateEntityCache sync)
            InvalidateEntityCache.handlePacket(sync, player);
    }
}
