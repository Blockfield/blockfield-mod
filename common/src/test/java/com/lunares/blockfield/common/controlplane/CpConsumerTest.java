package com.lunares.blockfield.common.controlplane;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

/** Frame handling: ordering, validation, durable terminal receipts and last-known-good. */
class CpConsumerTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
    private static final String EPOCH = "123e4567-e89b-12d3-a456-426614174000";
    private static final String OTHER_EPOCH = "123e4567-e89b-12d3-a456-426614174099";
    private static final String GENERATION = "123e4567-e89b-12d3-a456-426614174001";
    private static final String GENERATION_2 = "123e4567-e89b-12d3-a456-426614174002";
    private static final String AUDIENCE = "game-1";

    @TempDir Path directory;

    private CpStore store;
    private RecordingApplier applier;
    private CpConsumer consumer;

    @BeforeEach
    void setUp() {
        store = new CpStore(directory.resolve("cp"));
        applier = new RecordingApplier();
        consumer = new CpConsumer(store, applier, CLOCK, AUDIENCE);
        consumer.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));
        applier.calls.clear();
    }

    @Test
    void badConfigPreservesLastKnownGood() {
        List<CpAck> acks = consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), true));

        assertEquals(1, applier.calls.size());
        assertEquals(CpAck.State.REJECTED, acks.get(acks.size() - 1).state);
        assertEquals(CpAck.Result.INVALID_SCHEMA, acks.get(acks.size() - 1).result);
        CpStore.Lkg lkg = store.loadLkg("gameplay", "*");
        assertNotNull(lkg);
        assertEquals(41, lkg.revision());
        assertTrue(lkg.payloadJson().contains("\"pvpEnabled\":true"));
        assertEquals(41, store.loadCursor().sequence());
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
        assertEquals(42, store.loadLkg("gameplay", "*").revision());
        assertEquals(41, store.loadCursor().sequence());
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
        assertEquals(41, store.loadLkg("gameplay", "*").revision());
        assertEquals(41, store.loadCursor().sequence());
    }

    @Test
    void duplicateFrameAppliesOnceAndRepostsFullChain() {
        String frame = settingsFrame(42, 42, gameplayValue(false), false);
        List<CpAck> first = consumer.onFrame(frame);
        List<CpAck> second = consumer.onFrame(frame);

        assertEquals(1, applier.calls.size());
        assertEquals(4, first.size());
        assertEquals(4, second.size());
        assertEquals(
                first.stream().map(CpAck::toJson).toList(),
                second.stream().map(CpAck::toJson).toList());
    }

    @Test
    void epochMismatchRequiresResyncWithoutReceipt() {
        consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), false));

        assertThrows(
                CpResyncException.class,
                () ->
                        consumer.onFrame(
                                settingsFrame(OTHER_EPOCH, 43, 43, gameplayValue(false), false)));
        assertEquals(41, store.loadCursor().sequence());
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
        commit(consumer.onFrame(settingsFrame(42, 43, gameplayValue(false), false)));

        List<CpAck> acks = consumer.onFrame(settingsFrame(43, 42, gameplayValue(false), false));

        assertEquals(CpAck.State.REJECTED, acks.get(acks.size() - 1).state);
        assertEquals(43, store.loadLkg("gameplay", "*").revision());
        assertEquals(1, applier.calls.size());
    }

    @Test
    void actionFrameStaysFenced() {
        List<CpAck> acks = consumer.onFrame(actionFrame(42));

        assertTrue(applier.calls.isEmpty());
        assertEquals(CpAck.State.REJECTED, acks.get(acks.size() - 1).state);
        assertEquals(41, store.loadLkg("gameplay", "*").revision());
    }

    @Test
    void nonExecutableFrameSkipsApplier() {
        List<CpAck> acks = consumer.onFrame(nonExecutableFrame(42, "superseded"));

        assertTrue(applier.calls.isEmpty());
        assertEquals(CpAck.State.REJECTED, acks.get(acks.size() - 1).state);
        assertEquals(41, store.loadCursor().sequence());
    }

    @Test
    void uncertainEffectIsTerminalAndFlagged() {
        applier.throwOnApply = true;

        List<CpAck> acks = consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), false));

        assertEquals(CpAck.State.UNKNOWN, acks.get(acks.size() - 1).state);
        assertEquals(CpAck.Result.EFFECT_UNCERTAIN, acks.get(acks.size() - 1).result);
        assertTrue(consumer.needsOperator());
        assertTrue(store.isOperatorNeeded("gameplay", "*", 42));
        assertEquals(41, store.loadLkg("gameplay", "*").revision());

        // A replay re-posts UNKNOWN instead of re-applying blindly.
        List<CpAck> replay = consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), false));
        assertEquals(CpAck.State.UNKNOWN, replay.get(replay.size() - 1).state);
        assertEquals(1, applier.calls.size());
    }

    @Test
    void repeatSnapshotWithSameCursorIsNoop() {
        CpConsumer.SnapshotReport report =
                consumer.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        assertTrue(report.errors().isEmpty());
        assertEquals(41, store.loadCursor().sequence());
    }

    @Test
    void generationMismatchRequiresResyncWithoutReceipt() {
        commit(consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), false)));

        assertThrows(
                CpResyncException.class,
                () ->
                        consumer.onFrame(
                                settingsFrame(
                                        EPOCH, 43, 43, gameplayValue(false), false, GENERATION_2)));
        assertEquals(42, store.loadCursor().sequence());
        assertNull(store.lastTerminal(EPOCH, "op-43"));

        consumer.onSnapshot(snapshot(EPOCH, GENERATION_2, 42, gameplayValue(false)));
        List<CpAck> acks =
                consumer.onFrame(
                        settingsFrame(EPOCH, 43, 43, gameplayValue(false), false, GENERATION_2));

        assertEquals(CpAck.State.EFFECTIVE, acks.get(acks.size() - 1).state);
    }

    @Test
    void snapshotAppliesTwoTargetsWithKeyedLastKnownGood() {
        JsonObject value = gameplayValue(false);
        consumer.onSnapshot(twoTargetSnapshot(EPOCH, GENERATION, value, 45, 43));

        assertEquals(2, applier.calls.size());
        assertEquals(45, store.loadLkg("gameplay", "*").revision());
        assertEquals(43, store.loadLkg("gameplay", "arena").revision());
    }

    @Test
    void snapshotSkipsOperatorMarkedRevisionWithoutRetry() {
        applier.throwOnApply = true;
        consumer.onSnapshot(snapshot(EPOCH, GENERATION, 42, gameplayValue(false)));

        assertTrue(consumer.needsOperator());
        assertEquals(1, applier.calls.size());

        applier.throwOnApply = false;
        CpConsumer.SnapshotReport report =
                consumer.onSnapshot(snapshot(EPOCH, GENERATION, 42, gameplayValue(false)));

        assertEquals(1, applier.calls.size());
        assertTrue(consumer.needsOperator());
        assertEquals(1, report.errors().size());
    }

    @Test
    void blankTargetFrameIsRejectedWithoutApply() {
        JsonObject payload = new JsonObject();
        payload.addProperty("namespace", "gameplay");
        payload.add("value", gameplayValue(false));
        String frame =
                frame(
                        EPOCH,
                        42,
                        payload,
                        42L,
                        OffsetDateTime.now(CLOCK).plusMinutes(5),
                        true,
                        null,
                        GENERATION,
                        AUDIENCE);

        List<CpAck> acks = consumer.onFrame(frame);

        assertEquals(CpAck.State.REJECTED, acks.get(acks.size() - 1).state);
        assertEquals(CpAck.Result.INVALID_SCHEMA, acks.get(acks.size() - 1).result);
        assertTrue(applier.calls.isEmpty());
        assertEquals(41, store.loadCursor().sequence());
        assertEquals(41, store.loadLkg("gameplay", "*").revision());
    }

    @Test
    void snapshotEntryWithBlankTargetIsSkipped() {
        JsonArray settings = new JsonArray();
        settings.add(snapshotEntry("", 50, gameplayValue(false)));
        settings.add(snapshotEntry("*", 42, gameplayValue(false)));

        CpConsumer.SnapshotReport report =
                consumer.onSnapshot(snapshotRoot(EPOCH, GENERATION, settings, AUDIENCE));

        assertEquals(1, report.errors().size());
        assertEquals(1, applier.calls.size());
        assertEquals(42, store.loadLkg("gameplay", "*").revision());
    }

    @Test
    void malformedPayloadHashIsContractErrorNeverSilentDrop() {
        String frame =
                settingsFrame(42, 42, gameplayValue(false), false).replace("a".repeat(64), "xyz");

        assertThrows(CpContractException.class, () -> consumer.onFrame(frame));
        assertEquals(41, store.loadCursor().sequence());
        assertNull(store.lastTerminal(EPOCH, "op-42"));
        assertTrue(applier.calls.isEmpty());
    }

    @Test
    void missingPayloadHashIsContractErrorNeverSilentDrop() {
        String frame =
                settingsFrame(42, 42, gameplayValue(false), false)
                        .replace("\"payloadHash\":\"" + "a".repeat(64) + "\",", "");

        assertThrows(CpContractException.class, () -> consumer.onFrame(frame));
        assertEquals(41, store.loadCursor().sequence());
        assertNull(store.lastTerminal(EPOCH, "op-42"));
        assertTrue(applier.calls.isEmpty());
    }

    @Test
    void nullAuthorizationEpochParsesAsServiceFrame() {
        // Service-issued frames carry no account, so CP emits explicit null; the
        // consumer never reads the epoch, and the frame must not poison the stream.
        String frame =
                settingsFrame(42, 42, gameplayValue(false), false)
                        .replace("\"authorizationEpoch\":3", "\"authorizationEpoch\":null");

        List<CpAck> acks = consumer.onFrame(frame);

        assertEquals(CpAck.State.EFFECTIVE, acks.get(acks.size() - 1).state);
        assertEquals(42, store.loadLkg("gameplay", "*").revision());
    }

    @Test
    void missingAuthorizationEpochIsContractErrorNeverSilentDrop() {
        String frame =
                settingsFrame(42, 42, gameplayValue(false), false)
                        .replace(",\"authorizationEpoch\":3", "");

        assertThrows(CpContractException.class, () -> consumer.onFrame(frame));
        assertEquals(41, store.loadCursor().sequence());
        assertNull(store.lastTerminal(EPOCH, "op-42"));
        assertTrue(applier.calls.isEmpty());
    }

    @Test
    void oldEpochReceiptDoesNotShadowNewEpochFrame() {
        consumer.onSnapshot(snapshotWithCursor(OTHER_EPOCH, GENERATION, 0, 41));
        store.recordTerminal(OTHER_EPOCH, "op-old", 1, CpAck.State.EFFECTIVE, CpAck.Result.OK);
        applier.calls.clear();

        List<CpAck> acks =
                consumer.onFrame(settingsFrame(OTHER_EPOCH, 1, 42, gameplayValue(false), false));

        assertEquals(1, applier.calls.size(), "new-epoch frame silently skipped via old receipt");
        assertEquals(CpAck.State.EFFECTIVE, acks.get(acks.size() - 1).state);
    }

    @Test
    void frameExceptionMarksOperatorNeeded() {
        applier.throwOnApply = true;

        List<CpAck> acks = consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), false));

        assertEquals(CpAck.State.UNKNOWN, acks.get(acks.size() - 1).state);
        assertTrue(store.isOperatorNeeded("gameplay", "*", 42));
    }

    @Test
    void crashWindowReappliesInsteadOfStaleReject() {
        // Crash after apply + LKG save, before the terminal receipt: only LKG survived.
        store.saveAppliedSnapshot("gameplay", "*", 42, gameplayValue(false));
        applier.calls.clear();

        List<CpAck> acks = consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), false));

        assertEquals(CpAck.State.EFFECTIVE, acks.get(acks.size() - 1).state);
        assertEquals(1, applier.calls.size());
    }

    @Test
    void tornStateFailsClosedToResync() throws Exception {
        Path dir = directory.resolve("cp-torn");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("state.json"), "{\"epoch\":", StandardCharsets.UTF_8);
        Files.writeString(
                dir.resolve("receipts.jsonl"),
                "{\"operationId\":\"op-2\",\"seq",
                StandardCharsets.UTF_8);
        CpStore torn = new CpStore(dir);
        CpConsumer fresh = new CpConsumer(torn, applier, CLOCK, AUDIENCE);
        fresh.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        List<CpAck> acks = fresh.onFrame(settingsFrame(42, 42, gameplayValue(false), false));

        assertEquals(CpAck.State.EFFECTIVE, acks.get(acks.size() - 1).state);
    }

    @Test
    void foreignAudienceRejectedWithoutApply() {
        List<CpAck> acks =
                consumer.onFrame(
                        frame(
                                EPOCH,
                                42,
                                settingsPayload(gameplayValue(false), false),
                                42L,
                                OffsetDateTime.now(CLOCK).plusMinutes(5),
                                true,
                                null,
                                GENERATION,
                                "other-game"));

        assertTrue(applier.calls.isEmpty(), "foreign-audience frame was applied");
        assertEquals(CpAck.State.REJECTED, acks.get(acks.size() - 1).state);
        assertEquals(CpAck.Result.INVALID_SCHEMA, acks.get(acks.size() - 1).result);
    }

    @Test
    void foreignSnapshotAudienceRefusesAdoption() {
        JsonArray settings = new JsonArray();
        settings.add(snapshotEntry("*", 42, gameplayValue(false)));

        assertThrows(
                CpResyncException.class,
                () -> consumer.onSnapshot(snapshotRoot(EPOCH, GENERATION, settings, "other-game")));
        assertTrue(applier.calls.isEmpty(), "mismatched snapshot was applied");
        assertEquals(41, store.loadLkg("gameplay", "*").revision());
        assertEquals(41, store.loadCursor().sequence());
    }

    @Test
    void actionAppliesThroughHandler() {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        List<CpAck> acks = withActions.onFrame(knownActionFrame(42, "moderation.kick"));

        assertEquals(
                List.of(
                        CpAck.State.RECEIVED,
                        CpAck.State.VALIDATED,
                        CpAck.State.APPLIED,
                        CpAck.State.EFFECTIVE),
                acks.stream().map(ack -> ack.state).toList());
        assertEquals(1, actions.calls.size());
        assertTrue(applier.calls.isEmpty());
    }

    @Test
    void unknownActionKindRejectedWithoutHandlerCall() {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        List<CpAck> acks = withActions.onFrame(knownActionFrame(42, "console.exec"));

        assertTrue(actions.calls.isEmpty());
        assertEquals(CpAck.State.REJECTED, acks.get(acks.size() - 1).state);
        assertEquals(CpAck.Result.INVALID_SCHEMA, acks.get(acks.size() - 1).result);
    }

    @Test
    void sayRoutesTextAsReason() {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        List<CpAck> acks = withActions.onFrame(actionFrame(42, "moderation.say", "text", "hello"));

        assertEquals(CpAck.State.EFFECTIVE, acks.get(acks.size() - 1).state);
        assertEquals(List.of("moderation.say/arena//hello"), actions.calls);
    }

    @Test
    void nicknameKindsRoutePlayer() {
        assertNicknameKindRoutes("moderation.pardon", "op-pardon");
        assertNicknameKindRoutes("moderation.team-kick", "op-team-kick");
    }

    private void assertNicknameKindRoutes(String kind, String operationId) {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        List<CpAck> acks =
                withActions.onFrame(actionFrameOp(42, operationId, kind, "player", "Steve_99"));

        assertEquals(CpAck.State.EFFECTIVE, acks.get(acks.size() - 1).state);
        assertEquals(List.of(kind + "/arena/Steve_99/"), actions.calls);
    }

    @Test
    void classKindsRouteClassId() {
        assertClassKindRoutes("moderation.class-lock", "op-lock");
        assertClassKindRoutes("moderation.class-unlock", "op-unlock");
    }

    private void assertClassKindRoutes(String kind, String operationId) {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        List<CpAck> acks =
                withActions.onFrame(actionFrameOp(42, operationId, kind, "classId", "assault"));

        assertEquals(CpAck.State.EFFECTIVE, acks.get(acks.size() - 1).state);
        assertEquals(List.of(kind + "/arena/assault/"), actions.calls);
    }

    @Test
    void rangeRoutesOp() {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        List<CpAck> acks = withActions.onFrame(actionFrame(42, "moderation.range", "op", "reset"));

        assertEquals(CpAck.State.EFFECTIVE, acks.get(acks.size() - 1).state);
        assertEquals(List.of("moderation.range/arena/reset/"), actions.calls);
    }

    @Test
    void stopRoomEncodesOutcomeAndReason() {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));
        String room = "123e4567-e89b-12d3-a456-426614174002";

        List<CpAck> acks = withActions.onFrame(stopRoomFrame(42, room, "RED", "final"));

        assertEquals(CpAck.State.EFFECTIVE, acks.get(acks.size() - 1).state);
        assertEquals(List.of("moderation.stop-room/arena/" + room + "/RED final"), actions.calls);
    }

    @Test
    void stopRoomWithoutReasonEncodesOutcomeOnly() {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));
        String room = "123e4567-e89b-12d3-a456-426614174002";

        List<CpAck> acks = withActions.onFrame(stopRoomFrame(42, room, "none", ""));

        assertEquals(CpAck.State.EFFECTIVE, acks.get(acks.size() - 1).state);
        assertEquals(List.of("moderation.stop-room/arena/" + room + "/none"), actions.calls);
    }

    @Test
    void malformedModerationPayloadsRejectedWithoutHandlerCall() {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        // Same cursor sequence, distinct operation ids: every frame is decided on its own.
        List<String> frames =
                List.of(
                        actionFrameOp(42, "op-bad-1", "moderation.say", "text", ""),
                        actionFrameOp(42, "op-bad-2", "moderation.pardon", "player", "ab"),
                        actionFrameOp(42, "op-bad-3", "moderation.team-kick", "player", "x y"),
                        actionFrameOp(
                                42, "op-bad-4", "moderation.class-lock", "classId", "Assault"),
                        actionFrameOp(42, "op-bad-5", "moderation.range", "op", "explode"),
                        stopRoomFrameOp(42, "op-bad-6", "not-a-room", "RED", "final"),
                        stopRoomFrameOp(
                                42,
                                "op-bad-7",
                                "123e4567-e89b-12d3-a456-426614174002",
                                "GREEN",
                                ""));
        for (String frame : frames) {
            List<CpAck> acks = withActions.onFrame(frame);

            assertEquals(CpAck.State.REJECTED, acks.get(acks.size() - 1).state);
            assertEquals(CpAck.Result.INVALID_SCHEMA, acks.get(acks.size() - 1).result);
        }
        assertTrue(actions.calls.isEmpty());
    }

    @Test
    void listReturnsOutputOnTheEffectiveAckOnly() {
        RecordingActions actions = new RecordingActions();
        actions.output = "There are 2 of a max of 40 players online: a, b";
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        List<CpAck> acks = withActions.onFrame(actionFrame(42, "moderation.list", "unused", ""));

        assertEquals(List.of("moderation.list/arena//"), actions.calls);
        CpAck last = acks.get(acks.size() - 1);
        assertEquals(CpAck.State.EFFECTIVE, last.state);
        assertEquals(actions.output, last.output);
        assertTrue(last.toJson().contains("\"output\""));
        assertFalse(acks.get(0).toJson().contains("output"), "only the final ack carries it");
    }

    @Test
    void maintenanceRoutesOpAndNeedsReasonOnlyForInterrupt() {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        assertEquals(
                CpAck.State.EFFECTIVE,
                last(withActions.onFrame(maintenanceFrame("op-m1", "status", ""))).state);
        assertEquals(
                CpAck.State.EFFECTIVE,
                last(withActions.onFrame(maintenanceFrame("op-m2", "interrupt", "hotfix"))).state);
        for (String[] bad :
                List.of(
                        new String[] {"op-m3", "interrupt", ""},
                        new String[] {"op-m4", "status", "why"},
                        new String[] {"op-m5", "pause", ""})) {
            CpAck ack = last(withActions.onFrame(maintenanceFrame(bad[0], bad[1], bad[2])));
            assertEquals(CpAck.State.REJECTED, ack.state, bad[0]);
            assertEquals(CpAck.Result.INVALID_SCHEMA, ack.result, bad[0]);
        }
        assertEquals(
                List.of(
                        "moderation.maintenance/arena/status/",
                        "moderation.maintenance/arena/interrupt/hotfix"),
                actions.calls);
    }

    @Test
    void consoleLineGoesToHandleConsoleWithTheReadOnlyVerdict() {
        RecordingActions actions = new RecordingActions();
        actions.output = "The game is running normally";
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        CpAck ack = last(withActions.onFrame(consoleFrame("op-c1", "tick query", true)));
        CpAck run = last(withActions.onFrame(consoleFrame("op-c2", "save-all", false)));

        assertEquals(CpAck.State.EFFECTIVE, ack.state);
        assertEquals("The game is running normally", ack.output);
        assertEquals(CpAck.State.EFFECTIVE, run.state);
        assertEquals(
                List.of("console/arena/tick query/true", "console/arena/save-all/false"),
                actions.calls);
    }

    @Test
    void malformedConsoleFramesRejectedWithoutHandlerCall() {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        for (String frame :
                List.of(
                        consoleFrame("op-cb1", "say hi\nstop", false),
                        consoleFrame("op-cb2", "   ", true),
                        consoleFrame("op-cb3", "x".repeat(1001), false),
                        consoleFrame("op-cb4", "list", null))) {
            CpAck ack = last(withActions.onFrame(frame));
            assertEquals(CpAck.State.REJECTED, ack.state);
            assertEquals(CpAck.Result.INVALID_SCHEMA, ack.result);
        }
        assertTrue(actions.calls.isEmpty());
    }

    @Test
    void consoleRejectionReasonRidesTheAckButKickRejectionsStaySilent() {
        RecordingActions actions = new RecordingActions();
        actions.next = CpApplier.Outcome.rejected(CpAck.Result.APPLY_FAILED, "not allowlisted");
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        CpAck console = last(withActions.onFrame(consoleFrame("op-r1", "op Steve", true)));
        CpAck kick =
                last(
                        withActions.onFrame(
                                actionFrameOp(42, "op-r2", "moderation.kick", "playerId", "p")));

        assertEquals(CpAck.State.REJECTED, console.state);
        assertEquals("not allowlisted", console.output);
        assertEquals(CpAck.State.REJECTED, kick.state);
        // Older control planes refuse an unknown ack field: pre-existing kinds never send it.
        assertNull(kick.output);
        assertFalse(kick.toJson().contains("output"));
    }

    @Test
    void longOutputIsCappedByBytesWithoutSplittingASurrogatePair() {
        RecordingActions actions = new RecordingActions();
        actions.output = "a".repeat(CpConsumer.MAX_OUTPUT_BYTES - 1) + "\uD83D\uDE00tail";
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        String output = last(withActions.onFrame(consoleFrame("op-o1", "list", true))).output;

        assertEquals("a".repeat(CpConsumer.MAX_OUTPUT_BYTES - 1), output);
    }

    @Test
    void outputDropsControlCharactersAndLoneSurrogates() {
        RecordingActions actions = new RecordingActions();
        actions.output = "a\u0001b\nc\td\uD83D";
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        assertEquals(
                "a b\nc\td?",
                last(withActions.onFrame(consoleFrame("op-o2", "list", true))).output);
    }

    private static CpAck last(List<CpAck> acks) {
        return acks.get(acks.size() - 1);
    }

    private String maintenanceFrame(String operationId, String op, String reason) {
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", "moderation.maintenance");
        payload.addProperty("target", "arena");
        payload.addProperty("op", op);
        if (!reason.isEmpty()) payload.addProperty("reason", reason);
        return payloadFrame(operationId, payload);
    }

    private String consoleFrame(String operationId, String command, Boolean readOnly) {
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", "console.run");
        payload.addProperty("target", "arena");
        payload.addProperty("command", command);
        if (readOnly != null) payload.addProperty("readOnly", readOnly);
        return payloadFrame(operationId, payload);
    }

    /** Every frame at the cursor's next sequence, decided by its own operation id. */
    private String payloadFrame(String operationId, JsonObject payload) {
        return frameWithOp(
                EPOCH,
                42,
                operationId,
                payload,
                null,
                OffsetDateTime.now(CLOCK).plusMinutes(5),
                true,
                null,
                GENERATION,
                AUDIENCE);
    }

    @Test
    void decidedModerationActionRepostsWithoutRerun() {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));
        String frame = actionFrame(42, "moderation.say", "text", "hello");

        List<CpAck> first = withActions.onFrame(frame);
        List<CpAck> second = withActions.onFrame(frame);

        assertEquals(1, actions.calls.size());
        assertEquals(
                first.stream().map(ack -> ack.state).toList(),
                second.stream().map(ack -> ack.state).toList());
        assertEquals(CpAck.State.EFFECTIVE, second.get(second.size() - 1).state);
    }

    @Test
    void startedActionNeverRerunsAndReportsUnknown() {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));
        // Crash after the started-marker, before any terminal state: the handler ran once.
        store.markActionStarted(EPOCH, "op-42", "moderation.kick");

        List<CpAck> acks = withActions.onFrame(knownActionFrame(42, "moderation.kick"));

        assertTrue(actions.calls.isEmpty(), "started action re-ran after a crash");
        assertEquals(CpAck.State.UNKNOWN, acks.get(acks.size() - 1).state);
        assertEquals(CpAck.Result.EFFECT_UNCERTAIN, acks.get(acks.size() - 1).result);
        assertTrue(store.isActionOperatorNeeded("op-42"));
        assertTrue(withActions.needsOperator());
    }

    @Test
    void actionExceptionMarksOperator() {
        RecordingActions actions = new RecordingActions();
        actions.throwOnHandle = true;
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));

        List<CpAck> acks = withActions.onFrame(knownActionFrame(42, "moderation.ban"));

        assertEquals(CpAck.State.UNKNOWN, acks.get(acks.size() - 1).state);
        assertTrue(store.isActionOperatorNeeded("op-42"));
        assertTrue(withActions.needsOperator());
    }

    @Test
    void decidedActionRepostsWithoutRerun() {
        RecordingActions actions = new RecordingActions();
        CpConsumer withActions = new CpConsumer(store, applier, actions, CLOCK, AUDIENCE);
        withActions.onSnapshot(snapshot(EPOCH, GENERATION, 41, gameplayValue(true)));
        List<CpAck> first = withActions.onFrame(knownActionFrame(42, "moderation.kick"));

        List<CpAck> second = withActions.onFrame(knownActionFrame(42, "moderation.kick"));

        assertEquals(1, actions.calls.size());
        assertEquals(
                first.stream().map(ack -> ack.state).toList(),
                second.stream().map(ack -> ack.state).toList());
        assertEquals(CpAck.State.EFFECTIVE, second.get(second.size() - 1).state);
    }

    @Test
    void frameDeliversSameValueShapeAsSnapshot() {
        consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), false));

        assertEquals(1, applier.calls.size());
        assertEquals(gameplayValue(false), applier.calls.get(0));
        assertFalse(applier.calls.get(0).has("namespace"));
    }

    @Test
    void frameOnOperatorNeededNamespaceIsHeld() {
        applier.throwOnApply = true;
        consumer.onSnapshot(snapshot(EPOCH, GENERATION, 42, gameplayValue(false)));
        assertTrue(store.isOperatorNeeded("gameplay", "*", 42));
        applier.throwOnApply = false;
        int calls = applier.calls.size();

        List<CpAck> acks = consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), false));

        assertEquals(calls, applier.calls.size(), "frame applied over operator-needed marker");
        assertEquals(CpAck.State.UNKNOWN, acks.get(acks.size() - 1).state);
        assertEquals(CpAck.Result.EFFECT_UNCERTAIN, acks.get(acks.size() - 1).result);
        assertTrue(store.isOperatorNeeded("gameplay", "*", 42));
    }

    @Test
    void missingSnapshotAudienceRejectedWhenConfigured() {
        JsonArray settings = new JsonArray();
        settings.add(snapshotEntry("*", 42, gameplayValue(false)));
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        root.addProperty("epoch", EPOCH);
        root.addProperty("generation", GENERATION);
        root.addProperty("cursor", 41);
        root.add("settings", settings);

        assertThrows(CpResyncException.class, () -> consumer.onSnapshot(root.toString()));
        assertTrue(applier.calls.isEmpty(), "audience-less snapshot was applied");
        assertEquals(41, store.loadLkg("gameplay", "*").revision());
        assertEquals(41, store.loadCursor().sequence());
    }

    @Test
    void uncertainMarkerNeverDowngraded() {
        applier.throwOnApply = true;
        consumer.onSnapshot(snapshot(EPOCH, GENERATION, 50, gameplayValue(false)));
        assertTrue(store.isOperatorNeeded("gameplay", "*", 50));
        applier.throwOnApply = false;
        int calls = applier.calls.size();

        List<CpAck> acks = consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), false));

        assertEquals(CpAck.State.UNKNOWN, acks.get(acks.size() - 1).state);
        assertEquals(calls, applier.calls.size());
        assertTrue(store.isOperatorNeeded("gameplay", "*", 50));

        // The forbidden blind retry of rev 50 stays held.
        consumer.onSnapshot(snapshot(EPOCH, GENERATION, 50, gameplayValue(false)));
        assertEquals(calls, applier.calls.size());
    }

    @Test
    void epochRotationClearsStaleUncertainty() {
        applier.throwOnApply = true;
        consumer.onFrame(settingsFrame(42, 42, gameplayValue(false), false));
        assertTrue(consumer.needsOperator());
        store.markActionOperatorNeeded("op-42");
        applier.throwOnApply = false;

        consumer.onSnapshot(snapshotWithCursor(OTHER_EPOCH, GENERATION, 0, 41));

        assertFalse(consumer.needsOperator());
        assertFalse(store.isActionOperatorNeeded("op-42"));
        List<CpAck> acks =
                consumer.onFrame(settingsFrame(OTHER_EPOCH, 1, 42, gameplayValue(false), false));
        assertEquals(CpAck.State.EFFECTIVE, acks.get(acks.size() - 1).state);
    }

    private void commit(List<CpAck> acks) {
        store.saveCursorIfForward(acks.get(0).epoch.toString(), acks.get(0).sequence);
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
        root.addProperty("audience", AUDIENCE);
        root.addProperty("epoch", epoch);
        root.addProperty("generation", generation);
        root.addProperty("cursor", 41);
        JsonArray settings = new JsonArray();
        settings.add(entry);
        root.add("settings", settings);
        root.addProperty("hash", "snapshot");
        return root.toString();
    }

    private String snapshotWithCursor(String epoch, String generation, long cursor, long revision) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        root.addProperty("audience", AUDIENCE);
        root.addProperty("epoch", epoch);
        root.addProperty("generation", generation);
        root.addProperty("cursor", cursor);
        JsonArray settings = new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("namespace", "gameplay");
        entry.addProperty("target", "*");
        entry.addProperty("revision", revision);
        entry.add("value", gameplayValue(true));
        settings.add(entry);
        root.add("settings", settings);
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
        if (invalid) value.addProperty("unknownKey", true);
        payload.add("value", value);
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
        return settingsFrame(epoch, sequence, revision, value, invalid, GENERATION);
    }

    private String settingsFrame(
            String epoch,
            long sequence,
            long revision,
            JsonObject value,
            boolean invalid,
            String generation) {
        return frame(
                epoch,
                sequence,
                settingsPayload(value, invalid),
                revision,
                OffsetDateTime.now(CLOCK).plusMinutes(5),
                true,
                null,
                generation,
                AUDIENCE);
    }

    private String settingsFrame(
            String epoch,
            long sequence,
            long revision,
            JsonObject value,
            boolean invalid,
            OffsetDateTime expiresAt) {
        return frame(
                epoch,
                sequence,
                settingsPayload(value, invalid),
                revision,
                expiresAt,
                true,
                null,
                GENERATION,
                AUDIENCE);
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
                null,
                GENERATION,
                AUDIENCE);
    }

    private String knownActionFrame(long sequence, String kind) {
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", kind);
        payload.addProperty("target", "arena");
        payload.addProperty("playerId", "player-1");
        payload.addProperty("reason", "test");
        return frame(
                EPOCH,
                sequence,
                payload,
                null,
                OffsetDateTime.now(CLOCK).plusMinutes(5),
                true,
                null,
                GENERATION,
                AUDIENCE);
    }

    private String nonExecutableFrame(long sequence, String reason) {
        return frame(
                EPOCH,
                sequence,
                null,
                42L,
                OffsetDateTime.now(CLOCK).plusMinutes(5),
                false,
                reason,
                GENERATION,
                AUDIENCE);
    }

    private String frameAtSequence(long sequence) {
        return settingsFrame(sequence, 42, gameplayValue(false), false);
    }

    private String actionFrame(long sequence, String kind, String key, String value) {
        return actionFrameOp(sequence, "op-" + sequence, kind, key, value);
    }

    private String actionFrameOp(
            long sequence, String operationId, String kind, String key, String value) {
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", kind);
        payload.addProperty("target", "arena");
        payload.addProperty(key, value);
        return frameWithOp(
                EPOCH,
                sequence,
                operationId,
                payload,
                null,
                OffsetDateTime.now(CLOCK).plusMinutes(5),
                true,
                null,
                GENERATION,
                AUDIENCE);
    }

    private String stopRoomFrame(long sequence, String roomId, String outcome, String reason) {
        return stopRoomFrameOp(sequence, "op-" + sequence, roomId, outcome, reason);
    }

    private String stopRoomFrameOp(
            long sequence, String operationId, String roomId, String outcome, String reason) {
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", "moderation.stop-room");
        payload.addProperty("target", "arena");
        payload.addProperty("roomId", roomId);
        payload.addProperty("outcome", outcome);
        payload.addProperty("reason", reason);
        return frameWithOp(
                EPOCH,
                sequence,
                operationId,
                payload,
                null,
                OffsetDateTime.now(CLOCK).plusMinutes(5),
                true,
                null,
                GENERATION,
                AUDIENCE);
    }

    private String frame(
            String epoch,
            long sequence,
            JsonObject payload,
            Long revision,
            OffsetDateTime expiresAt,
            boolean executable,
            String reason,
            String generation,
            String audience) {
        return frameWithOp(
                epoch,
                sequence,
                "op-" + sequence,
                payload,
                revision,
                expiresAt,
                executable,
                reason,
                generation,
                audience);
    }

    private String frameWithOp(
            String epoch,
            long sequence,
            String operationId,
            JsonObject payload,
            Long revision,
            OffsetDateTime expiresAt,
            boolean executable,
            String reason,
            String generation,
            String audience) {
        JsonObject frame = new JsonObject();
        frame.addProperty("schema", 2);
        frame.addProperty("epoch", epoch);
        frame.addProperty("sequence", sequence);
        frame.addProperty("operationId", operationId);
        frame.addProperty("audience", audience);
        frame.addProperty("generation", generation);
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

    private String twoTargetSnapshot(
            String epoch,
            String generation,
            JsonObject value,
            long starRevision,
            long arenaRevision) {
        JsonArray settings = new JsonArray();
        settings.add(snapshotEntry("*", starRevision, value));
        settings.add(snapshotEntry("arena", arenaRevision, value));
        return snapshotRoot(epoch, generation, settings, AUDIENCE);
    }

    private String snapshotRoot(
            String epoch, String generation, JsonArray settings, String audience) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        root.addProperty("audience", audience);
        root.addProperty("epoch", epoch);
        root.addProperty("generation", generation);
        root.addProperty("cursor", 41);
        root.add("settings", settings);
        root.addProperty("hash", "snapshot");
        return root.toString();
    }

    private JsonObject snapshotEntry(String target, long revision, JsonObject value) {
        JsonObject entry = new JsonObject();
        entry.addProperty("namespace", "gameplay");
        entry.addProperty("target", target);
        entry.addProperty("revision", revision);
        entry.add("value", value);
        entry.addProperty("value_hash", "snapshot");
        return entry;
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

    static final class RecordingActions implements CpActionHandler {
        final List<String> calls = new ArrayList<>();
        boolean throwOnHandle;
        String output;
        CpApplier.Outcome next;

        @Override
        public CpApplier.Outcome handleAction(
                String kind, String target, String playerId, String reason) {
            calls.add(kind + "/" + target + "/" + playerId + "/" + reason);
            if (throwOnHandle) throw new RuntimeException("boom");
            return next != null ? next : CpApplier.Outcome.ok(output);
        }

        @Override
        public CpApplier.Outcome handleConsole(String target, String command, boolean readOnly) {
            calls.add("console/" + target + "/" + command + "/" + readOnly);
            return next != null ? next : CpApplier.Outcome.ok(output);
        }
    }
}
