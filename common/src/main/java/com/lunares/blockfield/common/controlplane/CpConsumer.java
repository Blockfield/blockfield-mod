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
 * Control-plane settings consumer: snapshot baseline, ordered frame handling, durable receipts and
 * last-known-good. Transport-independent; {@link CpSseClient} drives it from the SSE stream.
 *
 * <p>Rules, straight from the frozen v2 contract: frames execute only on the snapshotted
 * epoch/generation, strictly after the cursor, once; the received receipt is persisted before the
 * applier runs; a failed validation keeps last-known-good; unknown effects are terminal and
 * surfaced for operator reconciliation, never blindly retried.
 */
public final class CpConsumer {
    /** Namespaces this build applies. Gate snapshots belong to the proxy; actions land in S4b. */
    private static final Set<String> SETTINGS_NAMESPACES = Set.of("gameplay");

    private final CpStore store;
    private final CpApplier applier;
    private final Clock clock;

    private String epoch;
    private String generation;

    public CpConsumer(CpStore store, CpApplier applier, Clock clock) {
        this.store = store;
        this.applier = applier;
        this.clock = clock;
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
        List<String> errors = new ArrayList<>();
        CpStore.Cursor current = store.loadCursor();
        if (current.epoch().equals(snapshotEpoch)) {
            snapshotCursor = Math.max(snapshotCursor, current.sequence());
        }
        epoch = snapshotEpoch;
        generation = snapshotGeneration;
        store.saveCursor(snapshotEpoch, snapshotCursor);
        if (!root.has("settings") || !root.get("settings").isJsonArray()) {
            throw new CpContractException("snapshot misses settings");
        }
        for (JsonElement entry : root.getAsJsonArray("settings")) {
            if (!entry.isJsonObject()) throw new CpContractException("bad snapshot entry");
            JsonObject item = entry.getAsJsonObject();
            String namespace = CpEnvelope.requiredString(item, "namespace");
            String target = CpEnvelope.requiredString(item, "target");
            long revision = CpEnvelope.requiredLong(item, "revision", 0);
            if (!SETTINGS_NAMESPACES.contains(namespace)) {
                errors.add(namespace + "/" + target + ": unsupported namespace, skipped");
                continue;
            }
            if (!item.has("value") || !item.get("value").isJsonObject()) {
                errors.add(namespace + "/" + target + ": snapshot entry misses value");
                continue;
            }
            CpStore.Lkg lkg = store.loadLkg();
            if (lkg != null && revision <= lkg.revision()) continue;
            CpApplier.Outcome outcome;
            try {
                outcome =
                        applier.applySettings(
                                namespace, target, revision, item.getAsJsonObject("value"));
            } catch (RuntimeException e) {
                errors.add(namespace + "/" + target + ": effect uncertain, needs operator");
                continue;
            }
            if (outcome.applied()) {
                store.saveLkg(revision, item.getAsJsonObject("value"));
            } else {
                errors.addAll(outcome.errors());
            }
        }
        return new SnapshotReport(List.copyOf(errors));
    }

    /**
     * Handle one SSE frame, returning the ack chain to POST in order. Duplicate deliveries re-post
     * the cached terminal receipt without re-applying; gaps and epoch/generation changes request a
     * re-snapshot.
     */
    public synchronized List<CpAck> onFrame(String json) {
        CpEnvelope frame = CpEnvelope.parse(json);
        if (epoch == null || !epoch.equals(frame.epoch.toString())) {
            throw new CpResyncException("frame epoch is not the snapshotted one");
        }
        if (!generation.equals(frame.generation)) {
            return terminal(
                    frame,
                    CpAck.State.REJECTED,
                    CpAck.Result.STALE_GENERATION,
                    "stale generation, never runs on a replacement");
        }
        CpStore.Cursor cursor = store.loadCursor();
        CpStore.Receipt prior = store.lastReceipt(frame.sequence);
        if (frame.sequence <= cursor.sequence()) {
            if (prior == null) throw new CpResyncException("cursor moved past an undecided frame");
            return List.of(repost(frame, prior));
        }
        if (frame.sequence > cursor.sequence() + 1) {
            throw new CpResyncException("frame gap, server replays from the cursor");
        }
        if (!frame.executable) {
            return terminal(frame, CpAck.State.REJECTED, CpAck.Result.OK, note(frame));
        }
        if (!frame.expiresAt.toInstant().isAfter(clock.instant())) {
            return terminal(frame, CpAck.State.REJECTED, CpAck.Result.OK, "expired, never applied");
        }
        if (frame.revision == null) {
            // Typed moderation/drain actions are fenced until S4b wires them with the
            // command-credential split; this consumer applies settings only.
            return terminal(frame, CpAck.State.REJECTED, CpAck.Result.OK, "actions fenced");
        }
        if (frame.revision < 1
                || frame.envelopePayload == null
                || !SETTINGS_NAMESPACES.contains(namespaceOf(frame))) {
            return terminal(
                    frame,
                    CpAck.State.REJECTED,
                    CpAck.Result.INVALID_SCHEMA,
                    "not a settings frame");
        }
        CpStore.Lkg lkg = store.loadLkg();
        if (lkg != null && frame.revision <= lkg.revision()) {
            return terminal(frame, CpAck.State.REJECTED, CpAck.Result.OK, "stale revision");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        store.recordReceipt(
                frame.operationId, frame.sequence, CpAck.State.RECEIVED, CpAck.Result.OK);
        CpApplier.Outcome outcome;
        try {
            outcome =
                    applier.applySettings(
                            namespaceOf(frame),
                            targetOf(frame),
                            frame.revision,
                            frame.envelopePayload);
        } catch (RuntimeException e) {
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
        store.saveLkg(frame.revision, frame.envelopePayload);
        List<CpAck> acks =
                List.of(
                        CpAck.forEnvelope(frame, CpAck.State.RECEIVED, now),
                        CpAck.forEnvelope(frame, CpAck.State.VALIDATED, now),
                        CpAck.forEnvelope(frame, CpAck.State.APPLIED, now),
                        CpAck.forEnvelope(frame, CpAck.State.EFFECTIVE, now));
        store.recordReceipt(
                frame.operationId, frame.sequence, CpAck.State.EFFECTIVE, CpAck.Result.OK);
        store.saveCursor(frame.epoch.toString(), frame.sequence);
        return acks;
    }

    /** True after an effect-uncertain frame: an operator must reconcile before trusting state. */
    public synchronized boolean needsOperator() {
        return store.receipts().stream()
                .anyMatch(receipt -> receipt.outcome().state() == CpAck.State.UNKNOWN);
    }

    private static String namespaceOf(CpEnvelope frame) {
        JsonObject payload = frame.envelopePayload;
        return payload.has("namespace") && payload.get("namespace").isJsonPrimitive()
                ? payload.get("namespace").getAsString()
                : "";
    }

    private static String targetOf(CpEnvelope frame) {
        JsonObject payload = frame.envelopePayload;
        return payload.has("target") && payload.get("target").isJsonPrimitive()
                ? payload.get("target").getAsString()
                : "*";
    }

    private static String note(CpEnvelope frame) {
        return frame.reason == null ? "not executable" : frame.reason;
    }

    private List<CpAck> terminal(
            CpEnvelope frame, CpAck.State state, CpAck.Result result, String why) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        store.recordReceipt(frame.operationId, frame.sequence, state, result);
        store.saveCursor(frame.epoch.toString(), frame.sequence);
        if (state == CpAck.State.UNKNOWN)
            return List.of(CpAck.forEnvelope(frame, state, result, now));
        return List.of(
                CpAck.forEnvelope(frame, CpAck.State.RECEIVED, now),
                CpAck.forEnvelope(frame, state, result, now));
    }

    private CpAck repost(CpEnvelope frame, CpStore.Receipt prior) {
        return CpAck.forEnvelope(
                frame,
                prior.outcome().state(),
                prior.outcome().result(),
                OffsetDateTime.now(clock));
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
