package com.lunares.blockfield.common.controlplane;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Durable state: atomic writes, forward-only cursor, receipt journal. */
class CpStoreTest {
    @TempDir Path directory;

    private CpStore store;

    @BeforeEach
    void setUp() {
        store = new CpStore(directory.resolve("cp"));
    }

    @Test
    void cursorStartsEmptyAndMovesForwardOnly() {
        assertEquals(0, store.loadCursor().sequence());

        store.saveCursor("epoch-a", 7);

        assertEquals("epoch-a", store.loadCursor().epoch());
        assertEquals(7, store.loadCursor().sequence());
        assertThrows(IllegalStateException.class, () -> store.saveCursor("epoch-a", 7));
        assertThrows(IllegalStateException.class, () -> store.saveCursor("epoch-a", 3));
    }

    @Test
    void epochChangeResetsSequenceSpace() {
        store.saveCursor("epoch-a", 7);

        store.saveCursor("epoch-b", 1);

        assertEquals("epoch-b", store.loadCursor().epoch());
        assertEquals(1, store.loadCursor().sequence());
    }

    @Test
    void lastKnownGoodRoundTripsPerNamespaceTarget() {
        assertNull(store.loadLkg("gameplay", "*"));

        JsonObject payload = new JsonObject();
        payload.addProperty("a", 1);
        store.saveLkg("gameplay", "*", 41, payload);

        JsonObject arena = new JsonObject();
        arena.addProperty("b", 2);
        store.saveLkg("gameplay", "arena", 3, arena);

        CpStore.Lkg lkg = store.loadLkg("gameplay", "*");
        assertEquals(41, lkg.revision());
        assertEquals(64, lkg.sha256().length());
        assertEquals(payload.toString(), lkg.payloadJson());
        assertEquals(3, store.loadLkg("gameplay", "arena").revision());
        assertTrue(Files.isRegularFile(directory.resolve("cp/lkg.json")));
    }

    @Test
    void cursorIfForwardSkipsUnchangedRepeat() {
        store.saveCursor("epoch-a", 7);

        store.saveCursorIfForward("epoch-a", 7);
        store.saveCursorIfForward("epoch-a", 3);

        assertEquals(7, store.loadCursor().sequence());
        store.saveCursorIfForward("epoch-a", 8);
        assertEquals(8, store.loadCursor().sequence());
        store.saveCursorIfForward("epoch-b", 1);
        assertEquals("epoch-b", store.loadCursor().epoch());
    }

    @Test
    void pendingAcksRoundTrip() {
        assertTrue(store.loadPendingAcks().isEmpty());

        store.savePendingAcks(List.of("{\"state\":\"received\"}", "{\"state\":\"effective\"}"));

        assertEquals(2, store.loadPendingAcks().size());
        store.clearPendingAcks();
        assertTrue(store.loadPendingAcks().isEmpty());
    }

    @Test
    void operatorMarkersStickUntilCleared() {
        assertFalse(store.hasOperatorNeeded());
        assertFalse(store.isOperatorNeeded("gameplay", "*", 5));

        store.markOperatorNeeded("gameplay", "*", 5);

        assertTrue(store.hasOperatorNeeded());
        assertTrue(store.isOperatorNeeded("gameplay", "*", 5));
        assertTrue(store.isOperatorNeeded("gameplay", "*", 4));
        assertFalse(store.isOperatorNeeded("gameplay", "*", 6));
        assertFalse(store.isOperatorNeeded("gameplay", "arena", 5));
        store.clearOperatorNeeded("gameplay", "*");
        assertFalse(store.hasOperatorNeeded());
    }

    @Test
    void receiptsKeepLatestStatePerSequence() {
        assertNull(store.lastReceipt(9));

        store.recordReceipt("op-1", 9, CpAck.State.RECEIVED, CpAck.Result.OK);
        store.recordReceipt("op-1", 9, CpAck.State.EFFECTIVE, CpAck.Result.OK);

        CpStore.Receipt receipt = store.lastReceipt(9);
        assertEquals("op-1", receipt.operationId());
        assertEquals(CpAck.State.EFFECTIVE, receipt.outcome().state());
        assertEquals(2, store.receipts().size());
    }
}
