package com.lunares.blockfield.common.controlplane;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Durable state: one atomic file, forward-only cursor, terminal receipts keyed by operation. */
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

        store.saveCursorIfForward("epoch-a", 7);

        assertEquals("epoch-a", store.loadCursor().epoch());
        assertEquals(7, store.loadCursor().sequence());
        store.saveCursorIfForward("epoch-a", 7);
        store.saveCursorIfForward("epoch-a", 3);
        assertEquals(7, store.loadCursor().sequence());
        store.saveCursorIfForward("epoch-a", 8);
        assertEquals(8, store.loadCursor().sequence());
    }

    @Test
    void epochChangeResetsSequenceSpace() {
        store.saveCursorIfForward("epoch-a", 7);

        store.saveCursorIfForward("epoch-b", 1);

        assertEquals("epoch-b", store.loadCursor().epoch());
        assertEquals(1, store.loadCursor().sequence());
    }

    @Test
    void stateLivesInOneFile() {
        store.saveCursorIfForward("epoch-a", 7);
        JsonObject payload = new JsonObject();
        payload.addProperty("a", 1);
        store.saveAppliedSnapshot("gameplay", "*", 41, payload);

        assertTrue(Files.isRegularFile(directory.resolve("cp/state.json")));
        assertFalse(Files.exists(directory.resolve("cp/receipts.jsonl")));
        assertFalse(Files.exists(directory.resolve("cp/pending-acks.json")));
    }

    @Test
    void lastKnownGoodRoundTripsPerNamespaceTarget() {
        assertNull(store.loadLkg("gameplay", "*"));

        JsonObject payload = new JsonObject();
        payload.addProperty("a", 1);
        store.saveAppliedSnapshot("gameplay", "*", 41, payload);

        JsonObject arena = new JsonObject();
        arena.addProperty("b", 2);
        store.saveAppliedSnapshot("gameplay", "arena", 3, arena);

        CpStore.Lkg lkg = store.loadLkg("gameplay", "*");
        assertEquals(41, lkg.revision());
        assertEquals(64, lkg.sha256().length());
        assertEquals(payload.toString(), lkg.payloadJson());
        assertEquals(3, store.loadLkg("gameplay", "arena").revision());
    }

    @Test
    void epochChangeDropsStaleTerminalsAndMarkers() {
        store.saveCursorIfForward("epoch-a", 7);
        store.recordTerminal(
                "epoch-a", "op-1", 7, CpAck.State.UNKNOWN, CpAck.Result.EFFECT_UNCERTAIN);
        store.markActionStarted("epoch-a", "op-1", "moderation.kick");
        store.markOperatorNeeded("gameplay", "*", 7);

        store.saveCursorIfForward("epoch-b", 1);

        assertNull(store.lastTerminal("epoch-a", "op-1"));
        assertFalse(store.isActionStarted("epoch-a", "op-1"));
        assertFalse(store.hasOperatorNeeded());
        assertEquals("epoch-b", store.loadCursor().epoch());
        assertEquals(1, store.loadCursor().sequence());
    }

    @Test
    void uncertainMarkerIsMonotonic() {
        store.markOperatorNeeded("gameplay", "*", 50);

        store.saveUncertainFrame(
                "gameplay", "*", 42, "epoch-a", "op-42", 42, CpAck.Result.EFFECT_UNCERTAIN);

        assertTrue(store.isOperatorNeeded("gameplay", "*", 50));
        assertFalse(store.isOperatorNeeded("gameplay", "*", 51));
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
    void terminalsKeyedByEpochAndOperation() {
        assertNull(store.lastTerminal("epoch-a", "op-1"));

        store.recordTerminal("epoch-a", "op-1", 9, CpAck.State.EFFECTIVE, CpAck.Result.OK);
        // Same sequence in a new epoch is a different operation, never a replay.
        assertNull(store.lastTerminal("epoch-b", "op-1"));
        store.recordTerminal("epoch-b", "op-2", 9, CpAck.State.REJECTED, CpAck.Result.OK);

        CpStore.Terminal terminal = store.lastTerminal("epoch-a", "op-1");
        assertEquals("op-1", terminal.operationId());
        assertEquals(CpAck.State.EFFECTIVE, terminal.state());
        assertEquals(CpAck.State.REJECTED, store.lastTerminal("epoch-b", "op-2").state());
        assertTrue(store.hasTerminalState(CpAck.State.EFFECTIVE));
        assertFalse(store.hasTerminalState(CpAck.State.UNKNOWN));
    }

    @Test
    void startedActionsStick() {
        assertFalse(store.isActionStarted("epoch-a", "op-9"));

        store.markActionStarted("epoch-a", "op-9", "moderation.kick");

        assertTrue(store.isActionStarted("epoch-a", "op-9"));
        assertFalse(store.isActionStarted("epoch-a", "op-10"));
        assertFalse(store.isActionStarted("epoch-b", "op-9"));
    }

    @Test
    void actionOperatorMarkers() {
        assertFalse(store.hasOperatorNeeded());

        store.markActionOperatorNeeded("op-9");

        assertTrue(store.hasOperatorNeeded());
        assertTrue(store.isActionOperatorNeeded("op-9"));
        assertFalse(store.isActionOperatorNeeded("op-10"));
    }

    @Test
    void corruptStateFailsClosedToEmpty() throws Exception {
        Path dir = directory.resolve("cp-corrupt");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("state.json"), "{\"epoch\":", StandardCharsets.UTF_8);
        CpStore broken = new CpStore(dir);

        assertEquals(0, broken.loadCursor().sequence());
        assertNull(broken.loadLkg("gameplay", "*"));
        assertNull(broken.lastTerminal("epoch-a", "op-1"));
        assertFalse(broken.hasOperatorNeeded());

        broken.saveCursorIfForward("epoch-a", 1);
        assertEquals(1, broken.loadCursor().sequence());
    }

    @Test
    void atomicTerminalAndPendingAcksWritesOnce() {
        AtomicInteger writes = new AtomicInteger();
        CpStore atomic = new CpStore(directory.resolve("cp-atomic"), writes::incrementAndGet);
        UUID epoch = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        OffsetDateTime now = OffsetDateTime.of(2026, 11, 14, 12, 0, 0, 0, ZoneOffset.UTC);
        List<CpAck> acks =
                List.of(
                        new CpAck(
                                epoch,
                                42,
                                "gen-1",
                                null,
                                "a".repeat(64),
                                CpAck.State.RECEIVED,
                                now,
                                CpAck.Result.OK,
                                null,
                                "op-hook"),
                        new CpAck(
                                epoch,
                                42,
                                "gen-1",
                                null,
                                "a".repeat(64),
                                CpAck.State.EFFECTIVE,
                                now,
                                CpAck.Result.OK,
                                null,
                                "op-hook"));

        atomic.saveTerminalAndPendingAcks(
                epoch.toString(),
                "op-hook",
                42,
                CpAck.State.EFFECTIVE,
                CpAck.Result.OK,
                null,
                false,
                acks);

        assertEquals(1, writes.get());
        CpStore.Terminal terminal = atomic.lastTerminal(epoch.toString(), "op-hook");
        assertNotNull(terminal);
        assertEquals(CpAck.State.EFFECTIVE, terminal.state());
        CpStore.PendingAcks pending = atomic.loadPendingAcks();
        assertNotNull(pending);
        assertEquals(0, pending.sent());
    }

    @Test
    void beforePersistFailureLeavesPreviousStateIntact() {
        AtomicBoolean fail = new AtomicBoolean();
        Path dir = directory.resolve("cp-crash");
        CpStore crashing =
                new CpStore(
                        dir,
                        () -> {
                            if (fail.get()) {
                                throw new UncheckedIOException(new IOException("disk fail"));
                            }
                        });
        crashing.saveCursorIfForward("epoch-a", 7);
        crashing.markActionStarted("epoch-a", "op-1", "workshop.game");

        UUID epoch = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        OffsetDateTime now = OffsetDateTime.of(2026, 11, 14, 12, 0, 0, 0, ZoneOffset.UTC);
        List<CpAck> acks =
                List.of(
                        new CpAck(
                                epoch,
                                8,
                                "gen-1",
                                null,
                                "a".repeat(64),
                                CpAck.State.EFFECTIVE,
                                now,
                                CpAck.Result.OK,
                                null,
                                "op-1"));
        fail.set(true);
        assertThrows(
                UncheckedIOException.class,
                () ->
                        crashing.saveTerminalAndPendingAcks(
                                "epoch-a",
                                "op-1",
                                8,
                                CpAck.State.EFFECTIVE,
                                CpAck.Result.OK,
                                null,
                                false,
                                acks));

        CpStore reopened = new CpStore(dir);
        assertEquals(7, reopened.loadCursor().sequence());
        assertTrue(reopened.isActionStarted("epoch-a", "op-1"));
        assertNull(reopened.lastTerminal("epoch-a", "op-1"));
        assertNull(reopened.loadPendingAcks());
    }
}
