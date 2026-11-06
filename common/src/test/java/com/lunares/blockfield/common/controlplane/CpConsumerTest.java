package com.lunares.blockfield.common.controlplane;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Frame handling: ordering, validation, durable receipts and last-known-good. */
class CpConsumerTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
    private static final String EPOCH = "123e4567-e89b-12d3-a456-426614174000";
    private static final String OTHER_EPOCH = "123e4567-e89b-12d3-a456-426614174099";
    private static final String GENERATION = "123e4567-e89b-12d3-a456-426614174001";

    @TempDir Path directory;

    private CpStore store;
    private RecordingApplier applier;
    private CpConsumer consumer;

    @BeforeEach
    void setUp() {
        store = new CpStore(directory.resolve("cp"));
        applier = new RecordingApplier();
        consumer = new CpConsumer(store, applier, CLOCK);
        consumer.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));
        applier.calls.clear();
    }

    @Test
    void badConfigPreservesLastKnownGood() {
        List<CpAck> acks = consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), true));

        assertEquals(1, applier.calls.size());
        assertEquals(CpAck.State.REJECTED, acks.get(acks.size() - 1).state);
        assertEquals(CpAck.Result.INVALID_SCHEMA, acks.get(acks.size() - 1).result);
        CpStore.Lkg lkg = store.loadLkg();
        assertNotNull(lkg);
        assertEquals(41, lkg.revision());
        assertTrue(lkg.payloadJson().contains("\"pvpEnabled\":true"));
        assertEquals(42, store.loadCursor().sequence());
    }

    @Test
    void goodConfigUpdatesLastKnownGoodWithFullAckChain() {
        List<CpAck> acks = consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), false));

        assertEquals(
                List.of(
                        CpAck.State.RECEIVED,
                        CpAck.State.VALIDATED,
                        CpAck.State.APPLIED,
                        CpAck.State.EFFECTIVE),
                acks.stream().map(ack -> ack.state).toList());
        assertEquals(42, store.loadLkg().revision());
        assertEquals(42, store.loadCursor().sequence());
    }

    @Test
    void expiredFrameNeverExecutes() {
        List<CpAck> acks =
                consumer.onFrame(
                        settingsFrame(
                                42,
                                42,
                                gameplayValue(false),
                                false,
                                OffsetDateTime.ofInstant(
                                        Instant.parse("2026-11-06T11:00:00Z"), ZoneOffset.UTC)));

        assertTrue(applier.calls.isEmpty());
        assertEquals(CpAck.State.REJECTED, acks.get(acks.size() - 1).state);
        assertEquals(41, store.loadLkg().revision());
        assertEquals(42, store.loadCursor().sequence());
    }

    @Test
    void duplicateFrameAppliesOnceAndRepostsReceipt() {
        String frame = settingsFrame(42, 42, gameplayValue(false), false);
        List<CpAck> first = consumer.onFrame(frame);
        List<CpAck> second = consumer.onFrame(frame);

        assertEquals(1, applier.calls.size());
        assertEquals(1, second.size());
        assertEquals(CpAck.State.EFFECTIVE, second.get(0).state);
        assertEquals(first.get(first.size() - 1).toJson(), second.get(0).toJson());
    }

    @Test
    void epochMismatchRequiresResyncWithoutReceipt() {
        consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), false));

        assertThrows(
                CpResyncException.class,
                () ->
                        consumer.onFrame(
                                settingsFrame(OTHER_EPOCH, 43, 43, gameplayValue(false), false)));
        assertEquals(42, store.loadCursor().sequence());
        assertEquals(1, applier.calls.size());
    }

    @Test
    void frameGapRequiresResync() {
        assertThrows(CpResyncException.class, () -> consumer.onFrame(frameAtSequence(43)));
        assertEquals(41, store.loadCursor().sequence());
        assertTrue(applier.calls.isEmpty());
    }

    @Test
    void staleRevisionRejectedAfterNewerApplied() {
        consumer.onFrame(settingsFrame(42, 43, gameplayValue(false), false));

        List<CpAck> acks = consumer.onFrame(settingsFrame(43, 42, gameplayValue(false), false));

        assertEquals(CpAck.State.REJECTED, acks.get(acks.size() - 1).state);
        assertEquals(43, store.loadLkg().revision());
        assertEquals(1, applier.calls.size());
    }

    @Test
    void actionFrameStaysFenced() {
        List<CpAck> acks = consumer.onFrame(actionFrame(42));

        assertTrue(applier.calls.isEmpty());
        assertEquals(CpAck.State.REJECTED, acks.get(acks.size() - 1).state);
        assertEquals(41, store.loadLkg().revision());
    }

    @Test
    void nonExecutableFrameSkipsApplier() {
        List<CpAck> acks = consumer.onFrame(nonExecutableFrame(42, "superseded"));

        assertTrue(applier.calls.isEmpty());
        assertEquals(CpAck.State.REJECTED, acks.get(acks.size() - 1).state);
        assertEquals(42, store.loadCursor().sequence());
    }

    @Test
    void uncertainEffectIsTerminalAndFlagged() {
        applier.throwOnApply = true;

        List<CpAck> acks = consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), false));

        assertEquals(CpAck.State.UNKNOWN, acks.get(acks.size() - 1).state);
        assertEquals(CpAck.Result.EFFECT_UNCERTAIN, acks.get(acks.size() - 1).result);
        assertTrue(consumer.needsOperator());
        assertEquals(41, store.loadLkg().revision());
    }

    private String snapshot(String epoch, String generation, long revision, JsonObject value) {
        JsonObject entry = new JsonObject();
        entry.addProperty("namespace", "gameplay");
        entry.addProperty("target", "*");
        entry.addProperty("revision", revision);
        entry.add("value", value);
        entry.addProperty("value_hash", "snapshot");
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        root.addProperty("audience", "game-1");
        root.addProperty("epoch", epoch);
        root.addProperty("generation", generation);
        root.addProperty("cursor", 41);
        JsonArray settings = new JsonArray();
        settings.add(entry);
        root.add("settings", settings);
        root.addProperty("hash", "snapshot");
        return root.toString();
    }

    private JsonObject gameplayValue(boolean pvp) {
        JsonObject lobby = new JsonObject();
        lobby.addProperty("pvpEnabled", pvp);
        JsonObject value = new JsonObject();
        value.add("lobby", lobby);
        return value;
    }

    private JsonObject settingsPayload(JsonObject value, boolean invalid) {
        JsonObject payload = new JsonObject();
        payload.addProperty("namespace", "gameplay");
        payload.addProperty("target", "*");
        payload.add("value", value);
        if (invalid) payload.addProperty("unknownKey", true);
        return payload;
    }

    private String settingsFrame(long sequence, long revision, JsonObject value, boolean invalid) {
        return settingsFrame(
                EPOCH,
                sequence,
                revision,
                value,
                invalid,
                OffsetDateTime.now(CLOCK).plusMinutes(5));
    }

    private String settingsFrame(
            long sequence,
            long revision,
            JsonObject value,
            boolean invalid,
            OffsetDateTime expiresAt) {
        return settingsFrame(EPOCH, sequence, revision, value, invalid, expiresAt);
    }

    private String settingsFrame(
            String epoch, long sequence, long revision, JsonObject value, boolean invalid) {
        return settingsFrame(
                epoch,
                sequence,
                revision,
                value,
                invalid,
                OffsetDateTime.now(CLOCK).plusMinutes(5));
    }

    private String settingsFrame(
            String epoch,
            long sequence,
            long revision,
            JsonObject value,
            boolean invalid,
            OffsetDateTime expiresAt) {
        return frame(
                epoch, sequence, settingsPayload(value, invalid), revision, expiresAt, true, null);
    }

    private String actionFrame(long sequence) {
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", "moderation.kick");
        payload.addProperty("target", "some-player");
        return frame(
                EPOCH,
                sequence,
                payload,
                null,
                OffsetDateTime.now(CLOCK).plusMinutes(5),
                true,
                null);
    }

    private String nonExecutableFrame(long sequence, String reason) {
        return frame(
                EPOCH,
                sequence,
                null,
                42L,
                OffsetDateTime.now(CLOCK).plusMinutes(5),
                false,
                reason);
    }

    private String frameAtSequence(long sequence) {
        return settingsFrame(sequence, 42, gameplayValue(false), false);
    }

    private String frame(
            String epoch,
            long sequence,
            JsonObject payload,
            Long revision,
            OffsetDateTime expiresAt,
            boolean executable,
            String reason) {
        JsonObject frame = new JsonObject();
        frame.addProperty("schema", 2);
        frame.addProperty("epoch", epoch);
        frame.addProperty("sequence", sequence);
        frame.addProperty(
                "operationId",
                "123e4567-e89b-12d3-a456-42661417" + String.format("%04d", sequence));
        frame.addProperty("audience", "game-1");
        frame.addProperty("generation", GENERATION);
        if (revision == null) frame.add("revision", null);
        else frame.addProperty("revision", revision);
        frame.addProperty("payloadHash", "a".repeat(64));
        frame.addProperty("authorizationEpoch", 3);
        frame.addProperty("expiresAt", expiresAt.toString());
        frame.addProperty("executable", executable);
        if (reason == null) frame.add("reason", null);
        else frame.addProperty("reason", reason);
        if (payload == null) frame.add("payload", null);
        else frame.add("payload", payload);
        return frame.toString();
    }

    static final class RecordingApplier implements CpApplier {
        final List<JsonObject> calls = new ArrayList<>();
        boolean throwOnApply;

        @Override
        public Outcome applySettings(
                String namespace, String target, long revision, JsonObject value) {
            calls.add(value);
            if (throwOnApply) throw new RuntimeException("boom");
            if (value.has("unknownKey")) {
                return Outcome.rejected(CpAck.Result.INVALID_SCHEMA, "gameplay: unknown key");
            }
            return Outcome.ok();
        }
    }
}
