package com.lunares.blockfield.common.controlplane;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void lastKnownGoodRoundTrips() {
        assertNull(store.loadLkg());

        JsonObject payload = new JsonObject();
        payload.addProperty("a", 1);
        store.saveLkg(41, payload);

        CpStore.Lkg lkg = store.loadLkg();
        assertEquals(41, lkg.revision());
        assertEquals(64, lkg.sha256().length());
        assertEquals(payload.toString(), lkg.payloadJson());
        assertTrue(Files.isRegularFile(directory.resolve("cp/lkg.json")));
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
