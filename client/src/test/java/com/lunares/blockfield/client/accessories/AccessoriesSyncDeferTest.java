package com.lunares.blockfield.client.accessories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lunares.blockfield.client.accessories.AccessoriesSyncDefer.PendingSyncs;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class AccessoriesSyncDeferTest {
    record ProbePacket(int entityId) {}

    @Test
    void readyPacketIsCollectedForReplayExactlyOnce() {
        var pending = new PendingSyncs();
        pending.offer(8, new ProbePacket(8), 0);

        var replay = new ArrayList<PendingSyncs.Entry>();
        var expired = new ArrayList<PendingSyncs.Entry>();
        pending.collect(1, id -> true, replay, expired);

        assertEquals(1, replay.size());
        assertTrue(expired.isEmpty());
        assertEquals(0, pending.size());
    }

    @Test
    void unreadyPacketStaysQueuedInsideBudget() {
        var pending = new PendingSyncs();
        pending.offer(8, new ProbePacket(8), 0);

        var replay = new ArrayList<PendingSyncs.Entry>();
        var expired = new ArrayList<PendingSyncs.Entry>();
        pending.collect(AccessoriesSyncDefer.RETRY_TICKS, id -> false, replay, expired);

        assertTrue(replay.isEmpty());
        assertTrue(expired.isEmpty());
        assertEquals(1, pending.size());
    }

    @Test
    void unreadyPacketExpiresAfterBudgetInsteadOfStickingForever() {
        var pending = new PendingSyncs();
        pending.offer(8, new ProbePacket(8), 0);

        var replay = new ArrayList<PendingSyncs.Entry>();
        var expired = new ArrayList<PendingSyncs.Entry>();
        pending.collect(AccessoriesSyncDefer.RETRY_TICKS + 1, id -> false, replay, expired);

        assertTrue(replay.isEmpty());
        assertEquals(1, expired.size());
        assertEquals(0, pending.size());
    }

    @Test
    void overflowDropsOldestFirst() {
        var pending = new PendingSyncs();
        for (int i = 0; i <= AccessoriesSyncDefer.MAX_PENDING; i++) {
            pending.offer(i, new ProbePacket(i), 0);
        }

        assertEquals(AccessoriesSyncDefer.MAX_PENDING, pending.size());

        var replay = new ArrayList<PendingSyncs.Entry>();
        var expired = new ArrayList<PendingSyncs.Entry>();
        pending.collect(1, id -> true, replay, expired);

        assertEquals(AccessoriesSyncDefer.MAX_PENDING, replay.size());
        assertEquals(1, replay.get(0).entityId());
    }
}
