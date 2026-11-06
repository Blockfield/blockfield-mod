package com.lunares.blockfield.common.controlplane;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Control-plane settings consumer: snapshot baseline plus ordered frame handling over the single
 * {@link CpStore} state file. Transport-independent; {@link CpSseClient} drives it from SSE.
 *
 * <p>Rules, straight from the frozen v2 contract: frames execute only on the snapshotted
 * epoch/generation, strictly after the cursor, once per (epoch, operationId). Terminal decisions
 * are the only durable receipts: a settings replay without one re-applies (whole-namespace replace
 * is idempotent), while a non-idempotent action marked started-but-unfinished is never re-run and
 * instead reports unknown for operator reconciliation.
 */
public final class CpConsumer {
    /** Namespaces this build applies. Gate snapshots belong to the proxy. */
    private static final Set<String> SETTINGS_NAMESPACES = Set.of("gameplay");

    /** Typed actions this consumer routes; anything else (console, OP) is rejected, never run. */
    static final Set<String> ACTION_KINDS = Set.of("moderation.kick", "moderation.ban");

    private final CpStore store;
    private final CpApplier applier;
    private final CpActionHandler actions;
    private final Clock clock;
    private final String expectedAudience;

    private String epoch;
    private String generation;

    public CpConsumer(CpStore store, CpApplier applier, Clock clock) {
        this(store, applier, null, clock, null);
    }

    public CpConsumer(CpStore store, CpApplier applier, Clock clock, String expectedAudience) {
        this(store, applier, null, clock, expectedAudience);
    }

    public CpConsumer(CpStore store, CpApplier applier, CpActionHandler actions, Clock clock) {
        this(store, applier, actions, clock, null);
    }

    public CpConsumer(
            CpStore store,
            CpApplier applier,
            CpActionHandler actions,
            Clock clock,
            String expectedAudience) {
        this.store = store;
        this.applier = applier;
        this.actions = actions;
        this.clock = clock;
        this.expectedAudience = expectedAudience;
    }

    /** Snapshotted epoch, or null before the first snapshot (client re-snapshots then). */
    public synchronized String epoch() {
        return epoch;
    }

    public record SnapshotReport(List<String> errors) {}

    /**
     * Adopt the snapshot baseline (desired state only, never receipt ACKs) and reconcile
     * last-known-good toward it. Unknown namespaces are reported, not applied.
     */
    public synchronized SnapshotReport onSnapshot(String json) {
        JsonObject root = parseObject(json, "snapshot");
        if (root.has("schema") && root.get("schema").getAsInt() != CpEnvelope.SCHEMA) {
            throw new CpContractException("unsupported snapshot schema");
        }
        String snapshotEpoch = CpEnvelope.requiredString(root, "epoch");
        String snapshotGeneration = CpEnvelope.requiredString(root, "generation");
        long snapshotCursor = CpEnvelope.requiredLong(root, "cursor", 0);
        if (expectedAudience != null
                && root.has("audience")
                && !expectedAudience.equals(stringField(root, "audience"))) {
            throw new CpResyncException("snapshot audience mismatch, refusing to adopt it");
        }
        List<String> errors = new ArrayList<>();
        // A repeated snapshot with an unchanged cursor is a no-op: the cursor only moves forward.
        store.saveCursorIfForward(snapshotEpoch, snapshotCursor);
        epoch = snapshotEpoch;
        generation = snapshotGeneration;
        if (!root.has("settings") || !root.get("settings").isJsonArray()) {
            throw new CpContractException("snapshot misses settings");
        }
        for (JsonElement entry : root.getAsJsonArray("settings")) {
            if (!entry.isJsonObject()) throw new CpContractException("bad snapshot entry");
            JsonObject item = entry.getAsJsonObject();
            String namespace = CpEnvelope.requiredString(item, "namespace");
            String target = stringField(item, "target");
            long revision = CpEnvelope.requiredLong(item, "revision", 0);
            if (!SETTINGS_NAMESPACES.contains(namespace)) {
                errors.add(namespace + "/" + target + ": unsupported namespace, skipped");
                continue;
            }
            if (target.isBlank()) {
                errors.add(namespace + ": snapshot entry misses target, skipped");
                continue;
            }
            if (!item.has("value") || !item.get("value").isJsonObject()) {
                errors.add(namespace + "/" + target + ": snapshot entry misses value");
                continue;
            }
            if (store.isOperatorNeeded(namespace, target, revision)) {
                errors.add(
                        namespace
                                + "/"
                                + target
                                + ": effect uncertain, needs operator (not retried)");
                continue;
            }
            CpStore.Lkg lkg = store.loadLkg(namespace, target);
            if (lkg != null && revision <= lkg.revision()) continue;
            CpApplier.Outcome outcome;
            try {
                outcome =
                        applier.applySettings(
                                namespace, target, revision, item.getAsJsonObject("value"));
            } catch (RuntimeException e) {
                store.markOperatorNeeded(namespace, target, revision);
                errors.add(namespace + "/" + target + ": effect uncertain, needs operator");
                continue;
            }
            if (outcome.applied()) {
                store.saveAppliedSnapshot(
                        namespace, target, revision, item.getAsJsonObject("value"));
            } else {
                errors.addAll(outcome.errors());
            }
        }
        return new SnapshotReport(List.copyOf(errors));
    }

    /**
     * Handle one SSE frame, returning the ack chain to POST in order. The cursor is advanced by the
     * caller only after every ack is accepted, so an undelivered frame replays from the stored
     * cursor: settings re-apply idempotently, decided operations re-post their terminal state, and
     * started-but-unfinished actions report unknown instead of re-running. Gaps and
     * epoch/generation changes request a re-snapshot.
     */
    public synchronized List<CpAck> onFrame(String json) {
        CpEnvelope frame = CpEnvelope.parse(json);
        if (epoch == null || !epoch.equals(frame.epoch.toString())) {
            throw new CpResyncException("frame epoch is not the snapshotted one");
        }
        if (generation == null || !generation.equals(frame.generation)) {
            throw new CpResyncException("frame generation is not the snapshotted one");
        }
        if (expectedAudience != null && !expectedAudience.equals(frame.audience)) {
            return terminal(
                    frame,
                    CpAck.State.REJECTED,
                    CpAck.Result.INVALID_SCHEMA,
                    "audience mismatch, not applied");
        }
        CpStore.Cursor cursor = store.loadCursor();
        String epochId = frame.epoch.toString();
        CpStore.Terminal prior = store.lastTerminal(epochId, frame.operationId);
        if (frame.sequence <= cursor.sequence()) {
            if (prior == null) throw new CpResyncException("cursor moved past an undecided frame");
            return repost(frame, prior);
        }
        if (frame.sequence > cursor.sequence() + 1) {
            throw new CpResyncException("frame gap, server replays from the cursor");
        }
        if (prior != null) return repost(frame, prior);
        if (!frame.executable) {
            return terminal(frame, CpAck.State.REJECTED, CpAck.Result.OK, note(frame));
        }
        if (!frame.expiresAt.toInstant().isAfter(clock.instant())) {
            return terminal(frame, CpAck.State.REJECTED, CpAck.Result.OK, "expired, never applied");
        }
        if (frame.revision == null) {
            // Typed moderation/drain actions are fenced until a CpActionHandler is wired with the
            // command-credential split; this consumer applies settings only.
            // (S4b dispatches action frames here, before settings apply.)
            return onAction(frame);
        }
        if (frame.revision < 1
                || frame.envelopePayload == null
                || !SETTINGS_NAMESPACES.contains(namespaceOf(frame))
                || targetOf(frame).isBlank()) {
            return terminal(
                    frame,
                    CpAck.State.REJECTED,
                    CpAck.Result.INVALID_SCHEMA,
                    "not a settings frame");
        }
        CpStore.Lkg lkg = store.loadLkg(namespaceOf(frame), targetOf(frame));
        // Strictly older only: an equal revision without a terminal receipt is a crash window
        // (applied, LKG saved, receipt lost), and whole-namespace replace re-applies it safely.
        if (lkg != null && frame.revision < lkg.revision()) {
            return terminal(frame, CpAck.State.REJECTED, CpAck.Result.OK, "stale revision");
        }
        CpApplier.Outcome outcome;
        try {
            outcome =
                    applier.applySettings(
                            namespaceOf(frame),
                            targetOf(frame),
                            frame.revision,
                            frame.envelopePayload);
        } catch (RuntimeException e) {
            store.saveUncertainFrame(
                    namespaceOf(frame),
                    targetOf(frame),
                    frame.revision,
                    epochId,
                    frame.operationId,
                    frame.sequence,
                    CpAck.Result.EFFECT_UNCERTAIN);
            return List.of(
                    CpAck.forEnvelope(
                            frame,
                            CpAck.State.UNKNOWN,
                            CpAck.Result.EFFECT_UNCERTAIN,
                            OffsetDateTime.now(clock)));
        }
        if (!outcome.applied()) {
            CpAck.Result result =
                    outcome.result() == CpAck.Result.OK
                            ? CpAck.Result.APPLY_FAILED
                            : outcome.result();
            return terminal(
                    frame, CpAck.State.REJECTED, result, String.join("; ", outcome.errors()));
        }
        store.saveAppliedFrame(
                namespaceOf(frame),
                targetOf(frame),
                frame.revision,
                frame.envelopePayload,
                epochId,
                frame.operationId,
                frame.sequence);
        return effectiveChain(frame, OffsetDateTime.now(clock));
    }

    /**
     * True after an effect-uncertain frame, action or snapshot entry: an operator must reconcile.
     */
    public synchronized boolean needsOperator() {
        if (store.hasOperatorNeeded()) return true;
        return store.hasTerminalState(CpAck.State.UNKNOWN);
    }

    private List<CpAck> onAction(CpEnvelope frame) {
        if (actions == null) {
            return terminal(frame, CpAck.State.REJECTED, CpAck.Result.OK, "actions fenced");
        }
        String kind = stringField(frame.envelopePayload, "kind");
        String target = stringField(frame.envelopePayload, "target");
        String playerId = stringField(frame.envelopePayload, "playerId");
        String reason = stringField(frame.envelopePayload, "reason");
        if (!ACTION_KINDS.contains(kind) || target.isEmpty() || playerId.isEmpty()) {
            return terminal(
                    frame, CpAck.State.REJECTED, CpAck.Result.INVALID_SCHEMA, "not a known action");
        }
        String epochId = frame.epoch.toString();
        if (store.isActionStarted(epochId, frame.operationId)) {
            // Crash after the started-marker, before any terminal state: a non-idempotent
            // action is never re-run; it reports unknown for operator reconciliation.
            store.markActionOperatorNeeded(frame.operationId);
            return terminal(
                    frame,
                    CpAck.State.UNKNOWN,
                    CpAck.Result.EFFECT_UNCERTAIN,
                    "action started but never finished, needs operator");
        }
        store.markActionStarted(epochId, frame.operationId, kind);
        CpApplier.Outcome outcome;
        try {
            outcome = actions.handleAction(kind, target, playerId, reason);
        } catch (RuntimeException e) {
            store.markActionOperatorNeeded(frame.operationId);
            return terminal(
                    frame,
                    CpAck.State.UNKNOWN,
                    CpAck.Result.EFFECT_UNCERTAIN,
                    "effect uncertain, needs operator");
        }
        if (!outcome.applied()) {
            CpAck.Result result =
                    outcome.result() == CpAck.Result.OK
                            ? CpAck.Result.APPLY_FAILED
                            : outcome.result();
            return terminal(
                    frame, CpAck.State.REJECTED, result, String.join("; ", outcome.errors()));
        }
        return effectiveChain(frame);
    }

    private static String namespaceOf(CpEnvelope frame) {
        return stringField(frame.envelopePayload, "namespace");
    }

    private static String targetOf(CpEnvelope frame) {
        return stringField(frame.envelopePayload, "target");
    }

    private static String stringField(JsonObject payload, String k) {
        return payload != null && payload.has(k) && payload.get(k).isJsonPrimitive()
                ? payload.get(k).getAsString()
                : "";
    }

    private static String note(CpEnvelope frame) {
        return frame.reason == null ? "not executable" : frame.reason;
    }

    private List<CpAck> terminal(
            CpEnvelope frame, CpAck.State state, CpAck.Result result, String why) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        store.recordTerminal(
                frame.epoch.toString(), frame.operationId, frame.sequence, state, result);
        if (state == CpAck.State.UNKNOWN) {
            return List.of(CpAck.forEnvelope(frame, state, result, now));
        }
        return List.of(
                CpAck.forEnvelope(frame, CpAck.State.RECEIVED, now),
                CpAck.forEnvelope(frame, state, result, now));
    }

    private List<CpAck> effectiveChain(CpEnvelope frame) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        store.recordTerminal(
                frame.epoch.toString(),
                frame.operationId,
                frame.sequence,
                CpAck.State.EFFECTIVE,
                CpAck.Result.OK);
        return effectiveChain(frame, now);
    }

    private static List<CpAck> effectiveChain(CpEnvelope frame, OffsetDateTime now) {
        return List.of(
                CpAck.forEnvelope(frame, CpAck.State.RECEIVED, now),
                CpAck.forEnvelope(frame, CpAck.State.VALIDATED, now),
                CpAck.forEnvelope(frame, CpAck.State.APPLIED, now),
                CpAck.forEnvelope(frame, CpAck.State.EFFECTIVE, now));
    }

    private List<CpAck> repost(CpEnvelope frame, CpStore.Terminal prior) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return switch (prior.state()) {
            case EFFECTIVE -> effectiveChain(frame, now);
            case UNKNOWN ->
                    List.of(CpAck.forEnvelope(frame, CpAck.State.UNKNOWN, prior.result(), now));
            default ->
                    List.of(
                            CpAck.forEnvelope(frame, CpAck.State.RECEIVED, now),
                            CpAck.forEnvelope(frame, prior.state(), prior.result(), now));
        };
    }

    private static JsonObject parseObject(String json, String what) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new CpContractException(what + " is not a JSON object: " + e.getMessage());
        }
        if (!root.isJsonObject()) throw new CpContractException(what + " is not a JSON object");
        return root.getAsJsonObject();
    }
}
