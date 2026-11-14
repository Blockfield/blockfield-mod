package com.lunares.blockfield.common.controlplane;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
    /**
     * Namespaces this build applies: the gameplay core plus every settings.json-section namespace
     * the control plane delivers for the game backend (coordination#64 rows 10-11:
     * rooms/deathmatch/classLocks/campaign/parcool/gunBalance/kits/disabledMapIds). Gate snapshots
     * belong to the proxy; ai-limits and workshop-ai are never game-applied.
     */
    private static final Set<String> SETTINGS_NAMESPACES =
            Set.of(
                    "gameplay",
                    "rooms",
                    "deathmatch",
                    "classLocks",
                    "campaign",
                    "parcool",
                    "gunBalance",
                    "kits",
                    "disabledMapIds");

    /** Typed actions this consumer routes; anything else is rejected, never run. */
    static final Set<String> ACTION_KINDS =
            Set.of(
                    "moderation.kick",
                    "moderation.ban",
                    "moderation.say",
                    "moderation.list",
                    "moderation.pardon",
                    "moderation.team-kick",
                    "moderation.class-lock",
                    "moderation.class-unlock",
                    "moderation.range",
                    "moderation.stop-room",
                    "moderation.maintenance",
                    "moderation.announce",
                    "console.run",
                    "console.suggest",
                    "workshop.game");

    /**
     * Kinds whose terminal ack carries text (command output or the rejection reason). Only these: a
     * control plane predating the ack {@code output} field never sends them, so it never sees the
     * field it would refuse.
     */
    private static final Set<String> OUTPUT_KINDS =
            Set.of(
                    "moderation.list",
                    "moderation.maintenance",
                    "console.run",
                    "console.suggest",
                    "workshop.game");

    /** Output cap per ack in characters: the control plane's Ack.output limit. */
    static final int MAX_OUTPUT_CHARS = 16_384;

    /**
     * Output cap per ack as JSON-encoded bytes: the whole ack stays under CP's 32 KiB request cap.
     */
    static final int MAX_OUTPUT_JSON_BYTES = 30_000;

    private static final int MAX_COMMAND = 1000;

    private static final Pattern NICKNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern CLASS_ID = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern ROOM_ID =
            Pattern.compile("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private static final Pattern UUID_CANONICAL =
            Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Set<String> RANGE_OPS = Set.of("open", "close", "reset");
    private static final Set<String> STOP_OUTCOMES = Set.of("none", "draw", "RED", "BLUE");
    private static final Set<String> MAINTENANCE_OPS =
            Set.of("status", "drain", "resume", "interrupt");
    private static final Set<String> WORKSHOP_GAME_OPS =
            Set.of("save-flush", "save-off", "save-on", "stop", "export-limbo");

    /** Maximum number of in-flight pending post-ack hooks tracked at once. */
    static final int MAX_PENDING_ACKS = 64;

    /**
     * Bounded wait before a pending post-ack hook settles on its own so continuations always run.
     */
    static final Duration HOOK_SETTLE_TIMEOUT = Duration.ofSeconds(10);

    private final Object lock = new Object();
    private final CpStore store;
    private final CpApplier applier;
    private final CpActionHandler actions;
    private final Clock clock;
    private final String expectedAudience;
    private final Duration hookSettleTimeout;
    private final Map<PendingKey, PendingAck> pendingAcks = new LinkedHashMap<>();

    private String epoch;
    private String generation;
    private boolean closed;

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
        this(store, applier, actions, clock, expectedAudience, HOOK_SETTLE_TIMEOUT);
    }

    CpConsumer(
            CpStore store,
            CpApplier applier,
            CpActionHandler actions,
            Clock clock,
            String expectedAudience,
            Duration hookSettleTimeout) {
        this.store = store;
        this.applier = applier;
        this.actions = actions;
        this.clock = clock;
        this.expectedAudience = expectedAudience;
        this.hookSettleTimeout = Objects.requireNonNull(hookSettleTimeout, "hookSettleTimeout");
        if (hookSettleTimeout.isNegative()) {
            throw new IllegalArgumentException("hookSettleTimeout must not be negative");
        }
    }

    /** Snapshotted epoch, or null before the first snapshot (client re-snapshots then). */
    public String epoch() {
        synchronized (lock) {
            return epoch;
        }
    }

    /** One audience rule for snapshots and frames: missing counts as mismatch when configured. */
    private boolean audienceMismatch(String audience) {
        return expectedAudience != null && !expectedAudience.equals(audience);
    }

    public record SnapshotReport(List<String> errors) {}

    /** Verdict of the single shared settings-apply path, with acks when driven by a frame. */
    private record NamespaceApply(
            CpAck.State state, CpAck.Result result, List<String> errors, List<CpAck> acks) {}

    private record PendingKey(UUID epoch, long sequence, String operationId) {}

    private record PendingAck(
            UUID epoch,
            long sequence,
            String operationId,
            String generation,
            CompletableFuture<CpAck> future) {
        PendingKey key() {
            return new PendingKey(epoch, sequence, operationId);
        }
    }

    /**
     * Adopt the snapshot baseline (desired state only, never receipt ACKs) and reconcile
     * last-known-good toward it. Unknown namespaces are reported, not applied. Pending ack hooks
     * survive if the snapshot stays in the same epoch and generation with its cursor behind the
     * frame; hooks discarded by an epoch or generation change or a cursor at or past their sequence
     * complete normally with {@code null} as superseded.
     */
    public SnapshotReport onSnapshot(String json) {
        List<PendingAck> discarded = List.of();
        try {
            synchronized (lock) {
                if (closed) {
                    throw new IllegalStateException("control-plane consumer shut down");
                }
                SnapshotReport report = onSnapshotLocked(json);
                CpStore.PendingAcks chain = store.loadPendingAcks();
                if (chain != null) {
                    CpAck first = chain.acks().get(0);
                    boolean epochChanged = !first.epoch.toString().equals(epoch);
                    boolean generationChanged = !first.generation.equals(generation);
                    boolean cursorPassed = store.loadCursor().sequence() >= first.sequence;
                    if (epochChanged || generationChanged || cursorPassed) {
                        store.clearPendingAcks();
                    }
                }
                discarded = discardSupersededBySnapshotLocked();
                return report;
            }
        } finally {
            settleAll(discarded);
        }
    }

    private List<PendingAck> discardSupersededBySnapshotLocked() {
        if (pendingAcks.isEmpty()) {
            return List.of();
        }
        long cursorSequence = store.loadCursor().sequence();
        List<PendingAck> discarded = new ArrayList<>();
        Iterator<Map.Entry<PendingKey, PendingAck>> it = pendingAcks.entrySet().iterator();
        while (it.hasNext()) {
            PendingAck entry = it.next().getValue();
            if (!entry.epoch().toString().equals(epoch)
                    || !entry.generation().equals(generation)
                    || cursorSequence >= entry.sequence()) {
                discarded.add(entry);
                it.remove();
            }
        }
        return discarded;
    }

    /**
     * Track a new post-ack hook, bounded by {@link #MAX_PENDING_ACKS}. Superseded or evicted hooks
     * are collected under the consumer lock and settled normally outside it.
     */
    private void registerPending(PendingAck next, List<PendingAck> superseded) {
        PendingAck replaced = pendingAcks.put(next.key(), next);
        if (replaced != null) {
            superseded.add(replaced);
        }
        superseded.addAll(
                removeSupersededLocked(next.epoch(), next.sequence(), next.operationId()));
        while (pendingAcks.size() > MAX_PENDING_ACKS) {
            Iterator<PendingAck> oldest = pendingAcks.values().iterator();
            PendingAck evicted = oldest.next();
            oldest.remove();
            superseded.add(evicted);
        }
    }

    private List<PendingAck> removeSupersededLocked(UUID epoch, long sequence, String operationId) {
        if (pendingAcks.isEmpty()) {
            return List.of();
        }
        List<PendingAck> superseded = new ArrayList<>();
        Iterator<Map.Entry<PendingKey, PendingAck>> it = pendingAcks.entrySet().iterator();
        while (it.hasNext()) {
            PendingAck entry = it.next().getValue();
            if (!entry.epoch().equals(epoch)) {
                continue;
            }
            if (entry.sequence() < sequence
                    || (entry.sequence() == sequence
                            && !Objects.equals(entry.operationId(), operationId))) {
                superseded.add(entry);
                it.remove();
            }
        }
        return superseded;
    }

    private List<PendingAck> removeSupersededForFrameLocked(
            CpEnvelope frame, CpStore.Cursor cursor, String epochId) {
        List<PendingAck> superseded =
                new ArrayList<>(
                        removeSupersededLocked(frame.epoch, frame.sequence, frame.operationId));
        if (epochId.equals(cursor.epoch()) && cursor.sequence() > frame.sequence) {
            Iterator<Map.Entry<PendingKey, PendingAck>> it = pendingAcks.entrySet().iterator();
            while (it.hasNext()) {
                PendingAck entry = it.next().getValue();
                if (entry.epoch().equals(frame.epoch) && entry.sequence() <= cursor.sequence()) {
                    superseded.add(entry);
                    it.remove();
                }
            }
        }
        return superseded;
    }

    private SnapshotReport onSnapshotLocked(String json) {
        JsonObject root = parseObject(json, "snapshot");
        if (root.has("schema") && root.get("schema").getAsInt() != CpEnvelope.SCHEMA) {
            throw new CpContractException("unsupported snapshot schema");
        }
        String snapshotEpoch = CpEnvelope.requiredString(root, "epoch");
        String snapshotGeneration = CpEnvelope.requiredString(root, "generation");
        long snapshotCursor = CpEnvelope.requiredLong(root, "cursor", 0);
        if (audienceMismatch(stringField(root, "audience"))) {
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
            CpStore.Lkg lkg = store.loadLkg(namespace, target);
            if (lkg != null && revision <= lkg.revision()) continue;
            // Operator gate, applier call and durable record all live in applyNamespace.
            NamespaceApply apply =
                    applyNamespace(
                            namespace, target, revision, item.getAsJsonObject("value"), null);
            if (apply.state() != CpAck.State.EFFECTIVE) errors.addAll(apply.errors());
        }
        return new SnapshotReport(List.copyOf(errors));
    }

    /**
     * Handle one SSE frame, returning the ack chain to POST in order. The cursor is advanced by the
     * caller only after a terminal ack ({@code effective}, {@code rejected}, or {@code unknown}) is
     * accepted, so an undelivered frame replays from the stored cursor: settings re-apply
     * idempotently, decided operations re-post their terminal state, and started-but-unfinished
     * actions report unknown instead of re-running. Gaps and epoch/generation changes request a
     * re-snapshot.
     *
     * <p>A throwing frame leaves earlier post-ack hooks pending until settled by delivery, timeout,
     * supersede, or close.
     */
    public List<CpAck> onFrame(String json) {
        List<PendingAck> superseded = new ArrayList<>();
        try {
            synchronized (lock) {
                if (closed) {
                    throw new IllegalStateException("control-plane consumer shut down");
                }
                return onFrameLocked(json, superseded);
            }
        } finally {
            settleAll(superseded);
        }
    }

    private List<CpAck> onFrameLocked(String json, List<PendingAck> superseded) {
        CpEnvelope frame = CpEnvelope.parse(json);
        if (epoch == null || !epoch.equals(frame.epoch.toString())) {
            throw new CpResyncException("frame epoch is not the snapshotted one");
        }
        if (generation == null || !generation.equals(frame.generation)) {
            throw new CpResyncException("frame generation is not the snapshotted one");
        }
        if (audienceMismatch(frame.audience)) {
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
            superseded.addAll(removeSupersededForFrameLocked(frame, cursor, epochId));
            return repost(frame, prior);
        }
        if (frame.sequence > cursor.sequence() + 1) {
            throw new CpResyncException("frame gap, server replays from the cursor");
        }
        superseded.addAll(removeSupersededForFrameLocked(frame, cursor, epochId));
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
            return onAction(frame, superseded);
        }
        // The bare settings value is extracted once here; the applier never sees the wrapper.
        String namespace = namespaceOf(frame);
        String target = targetOf(frame);
        JsonObject bareValue = bareValueOf(frame);
        if (frame.revision < 1
                || bareValue == null
                || !SETTINGS_NAMESPACES.contains(namespace)
                || target.isBlank()) {
            return terminal(
                    frame,
                    CpAck.State.REJECTED,
                    CpAck.Result.INVALID_SCHEMA,
                    "not a settings frame");
        }
        CpStore.Lkg lkg = store.loadLkg(namespace, target);
        // Strictly older only: an equal revision without a terminal receipt is a crash window
        // (applied, LKG saved, receipt lost), and whole-namespace replace re-applies it safely.
        if (lkg != null && frame.revision < lkg.revision()) {
            return terminal(frame, CpAck.State.REJECTED, CpAck.Result.OK, "stale revision");
        }
        return applyNamespace(namespace, target, frame.revision, bareValue, frame).acks();
    }

    /**
     * Notify the consumer that the control plane accepted a terminal ack for a frame (and the
     * cursor was saved), settling any matching post-ack hook normally with {@code finalAck} and
     * settling superseded hooks behind it with {@code null}. Safe to call from any thread;
     * completions run outside the consumer lock.
     */
    public void ackAccepted(CpAck finalAck) {
        Objects.requireNonNull(finalAck, "finalAck");
        PendingAck matched;
        List<PendingAck> superseded;
        synchronized (lock) {
            matched =
                    pendingAcks.remove(
                            new PendingKey(
                                    finalAck.epoch, finalAck.sequence, finalAck.operationId));
            superseded =
                    removeSupersededLocked(finalAck.epoch, finalAck.sequence, finalAck.operationId);
        }
        if (matched != null) {
            matched.future().complete(finalAck);
        }
        settleAll(superseded);
    }

    /**
     * Demote a frame's durable record to {@link CpAck.State#UNKNOWN} ({@code effect_uncertain})
     * before posting a fallback {@code unknown} ack so retries replay {@code unknown}.
     */
    void markFallbackUnknown(UUID epoch, long sequence, String operationId) {
        Objects.requireNonNull(epoch, "epoch");
        synchronized (lock) {
            demoteUncertainLocked(epoch.toString(), operationId, sequence, true);
        }
    }

    /**
     * Notify the consumer that posting acks for {@code epoch}/{@code sequence}/{@code operationId}
     * failed or timed out without settling the row on the control plane. Only a permanent
     * control-plane rejection ({@link CpAckRejectedException}) demotes durable {@code EFFECTIVE}
     * state to {@code UNKNOWN} and settles the hook immediately with {@code null}; retryable
     * failures leave it pending until accepted, superseded, closed, or the bounded wait expires.
     */
    public void ackFailed(UUID epoch, long sequence, String operationId, Throwable error) {
        Objects.requireNonNull(epoch, "epoch");
        if (!(error instanceof CpAckRejectedException)) {
            return;
        }
        PendingAck matched;
        synchronized (lock) {
            demoteUncertainLocked(epoch.toString(), operationId, sequence, false);
            matched = pendingAcks.remove(new PendingKey(epoch, sequence, operationId));
        }
        if (matched != null) {
            matched.future().complete(null);
        }
    }

    private void demoteUncertainLocked(
            String epochId, String operationId, long sequence, boolean alwaysRecord) {
        if (operationId == null) {
            return;
        }
        store.demoteUncertain(epochId, operationId, sequence, alwaysRecord);
    }

    /**
     * Consumer shutdown: every pending post-ack hook settles normally with {@code null}.
     * Idempotent; the consumer rejects further frames and snapshots afterwards.
     */
    public void close() {
        List<PendingAck> pending;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            pending = List.copyOf(pendingAcks.values());
            pendingAcks.clear();
        }
        settleAll(pending);
    }

    private static void settleAll(List<PendingAck> pending) {
        for (PendingAck ack : pending) {
            ack.future().complete(null);
        }
    }

    private void removePending(PendingKey key, CompletableFuture<CpAck> future) {
        synchronized (lock) {
            PendingAck current = pendingAcks.get(key);
            if (current != null && current.future() == future) {
                pendingAcks.remove(key);
            }
        }
    }

    /**
     * True after an effect-uncertain frame, action or snapshot entry: an operator must reconcile.
     */
    public boolean needsOperator() {
        synchronized (lock) {
            if (store.hasOperatorNeeded()) return true;
            return store.hasTerminalState(CpAck.State.UNKNOWN);
        }
    }

    /**
     * The single settings-apply path for snapshots ({@code frame} null) and frames: operator gate,
     * applier call over the bare value, durable record. The value is always the settings object
     * itself, never the envelope wrapper, so both paths feed the applier one shape.
     */
    private NamespaceApply applyNamespace(
            String namespace, String target, long revision, JsonObject value, CpEnvelope frame) {
        OffsetDateTime now = frame != null ? OffsetDateTime.now(clock) : null;
        if (store.isOperatorNeeded(namespace, target, revision)) {
            String note =
                    namespace + "/" + target + ": effect uncertain, needs operator (not retried)";
            return uncertainNamespace(namespace, target, revision, frame, now, note);
        }
        CpApplier.Outcome outcome;
        try {
            outcome = applier.applySettings(namespace, target, revision, value);
        } catch (RuntimeException e) {
            String note = namespace + "/" + target + ": effect uncertain, needs operator";
            return uncertainNamespace(namespace, target, revision, frame, now, note);
        }
        if (outcome.applied()) {
            List<CpAck> acks = null;
            if (frame != null) {
                acks = effectiveChain(frame, now, null);
                store.saveAppliedFrameAndPendingAcks(
                        namespace,
                        target,
                        revision,
                        value,
                        frame.epoch.toString(),
                        frame.operationId,
                        frame.sequence,
                        acks);
            } else {
                store.saveAppliedSnapshot(namespace, target, revision, value);
            }
            return new NamespaceApply(CpAck.State.EFFECTIVE, CpAck.Result.OK, List.of(), acks);
        }
        CpAck.Result result =
                outcome.result() == CpAck.Result.OK ? CpAck.Result.APPLY_FAILED : outcome.result();
        List<CpAck> acks = null;
        if (frame != null) {
            acks = terminalAcks(frame, CpAck.State.REJECTED, result, now, null);
            store.saveTerminalAndPendingAcks(
                    frame.epoch.toString(),
                    frame.operationId,
                    frame.sequence,
                    CpAck.State.REJECTED,
                    result,
                    null,
                    false,
                    acks);
        }
        return new NamespaceApply(CpAck.State.REJECTED, result, outcome.errors(), acks);
    }

    private NamespaceApply uncertainNamespace(
            String namespace,
            String target,
            long revision,
            CpEnvelope frame,
            OffsetDateTime now,
            String note) {
        List<CpAck> acks = null;
        if (frame != null) {
            acks =
                    List.of(
                            CpAck.forEnvelope(
                                    frame,
                                    CpAck.State.UNKNOWN,
                                    CpAck.Result.EFFECT_UNCERTAIN,
                                    now));
            store.saveUncertainFrameAndPendingAcks(
                    namespace,
                    target,
                    revision,
                    frame.epoch.toString(),
                    frame.operationId,
                    frame.sequence,
                    CpAck.Result.EFFECT_UNCERTAIN,
                    acks);
        } else {
            store.markOperatorNeeded(namespace, target, revision);
        }
        return new NamespaceApply(
                CpAck.State.UNKNOWN, CpAck.Result.EFFECT_UNCERTAIN, List.of(note), acks);
    }

    private List<CpAck> onAction(CpEnvelope frame, List<PendingAck> superseded) {
        if (actions == null) {
            return terminal(frame, CpAck.State.REJECTED, CpAck.Result.OK, "actions fenced");
        }
        String kind = stringField(frame.envelopePayload, "kind");
        String target = stringField(frame.envelopePayload, "target");
        if (!ACTION_KINDS.contains(kind) || target.isEmpty()) {
            return terminal(
                    frame, CpAck.State.REJECTED, CpAck.Result.INVALID_SCHEMA, "not a known action");
        }
        boolean console = "console.run".equals(kind);
        boolean suggest = "console.suggest".equals(kind);
        boolean workshopGame = "workshop.game".equals(kind);
        ActionArgs args =
                suggest
                        ? suggestArgs(frame.envelopePayload)
                        : console
                                ? consoleArgs(frame.envelopePayload)
                                : workshopGame
                                        ? workshopGameArgs(target, frame.envelopePayload)
                                        : actionArgs(kind, frame.envelopePayload);
        if (args == null) {
            return terminal(
                    frame, CpAck.State.REJECTED, CpAck.Result.INVALID_SCHEMA, "not a known action");
        }
        boolean withOutput = OUTPUT_KINDS.contains(kind);
        String epochId = frame.epoch.toString();
        if (store.isActionStarted(epochId, frame.operationId)) {
            // Crash after the started-marker, before any terminal state: a non-idempotent
            // action is never re-run; it reports unknown for operator reconciliation.
            return terminal(
                    frame,
                    CpAck.State.UNKNOWN,
                    CpAck.Result.EFFECT_UNCERTAIN,
                    "action started but never finished, needs operator",
                    null,
                    true);
        }
        store.markActionStarted(epochId, frame.operationId, kind);
        CompletableFuture<CpAck> ackFuture = null;
        if (workshopGame) {
            ackFuture = new CompletableFuture<>();
            registerPending(
                    new PendingAck(
                            frame.epoch,
                            frame.sequence,
                            frame.operationId,
                            frame.generation,
                            ackFuture),
                    superseded);
        }
        CpApplier.Outcome outcome;
        try {
            if (workshopGame) {
                try {
                    outcome =
                            actions.handleWorkshopGame(
                                    target, args.playerId(), args.reason(), ackFuture);
                } finally {
                    PendingKey key = new PendingKey(frame.epoch, frame.sequence, frame.operationId);
                    CompletableFuture<CpAck> hook = ackFuture;
                    hook.completeOnTimeout(
                            null, hookSettleTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    hook.whenComplete((ignored, err) -> removePending(key, hook));
                }
            } else {
                outcome =
                        suggest
                                ? actions.handleSuggest(target, args.reason())
                                : console
                                        ? actions.handleConsole(
                                                target, args.reason(), args.readOnly())
                                        : actions.handleAction(
                                                kind, target, args.playerId(), args.reason());
            }
        } catch (RuntimeException e) {
            return terminal(
                    frame,
                    CpAck.State.UNKNOWN,
                    CpAck.Result.EFFECT_UNCERTAIN,
                    "effect uncertain, needs operator",
                    null,
                    true);
        }
        if (!outcome.applied()) {
            CpAck.Result result =
                    outcome.result() == CpAck.Result.OK
                            ? CpAck.Result.APPLY_FAILED
                            : outcome.result();
            String why = String.join("; ", outcome.errors());
            return terminal(frame, CpAck.State.REJECTED, result, why, withOutput ? why : null);
        }
        if (suggest && !fitsCap(outcome.output())) {
            // A long suggestion list would truncate mid-document into invalid JSON: refuse
            // outright so the ack never carries a broken suggest answer.
            String why = "suggest output exceeds the ack output cap, refused";
            return terminal(frame, CpAck.State.REJECTED, CpAck.Result.APPLY_FAILED, why, why);
        }
        return effectiveChain(frame, withOutput ? outcome.output() : null);
    }

    /**
     * Handler-facing arguments per moderation kind (see {@link CpActionHandler} for the mapping).
     * Null means the payload is malformed and the frame is rejected without a handler call.
     */
    private record ActionArgs(String playerId, String reason, boolean readOnly) {
        ActionArgs(String playerId, String reason) {
            this(playerId, reason, false);
        }
    }

    /**
     * {@code console.run}: one line (no control characters, at most 1000 chars) plus the control
     * plane's read-only verdict, carried in {@code reason}/{@code readOnly}. Null when malformed.
     */
    private static ActionArgs consoleArgs(JsonObject payload) {
        String command = stringField(payload, "command");
        if (command.isBlank()
                || command.length() > MAX_COMMAND
                || command.chars().anyMatch(Character::isISOControl)
                || !payload.has("readOnly")
                || !payload.get("readOnly").isJsonPrimitive()
                || !payload.getAsJsonPrimitive("readOnly").isBoolean()) {
            return null;
        }
        return new ActionArgs("", command, payload.get("readOnly").getAsBoolean());
    }

    /**
     * {@code console.suggest}: the partial line as typed (at most 1000 chars, no control
     * characters). Empty input completes the command itself. Null when malformed.
     */
    private static ActionArgs suggestArgs(JsonObject payload) {
        String input = stringField(payload, "input");
        if (input.length() > MAX_COMMAND || input.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return new ActionArgs("", input);
    }

    /**
     * {@code workshop.game}: requires {@code target="workshop"} and {@code gameOp} in {@link
     * #WORKSHOP_GAME_OPS}; {@code job} is a required canonical UUID for {@code export-limbo} and
     * forbidden otherwise. Null when malformed.
     */
    private static ActionArgs workshopGameArgs(String target, JsonObject payload) {
        if (!"workshop".equals(target) || payload == null) {
            return null;
        }
        if (!payload.has("gameOp")
                || !payload.get("gameOp").isJsonPrimitive()
                || !payload.getAsJsonPrimitive("gameOp").isString()) {
            return null;
        }
        String gameOp = payload.get("gameOp").getAsString();
        if (!WORKSHOP_GAME_OPS.contains(gameOp)) {
            return null;
        }
        boolean hasJob = payload.has("job") && !payload.get("job").isJsonNull();
        if ("export-limbo".equals(gameOp)) {
            if (!hasJob
                    || !payload.get("job").isJsonPrimitive()
                    || !payload.getAsJsonPrimitive("job").isString()) {
                return null;
            }
            String job = payload.get("job").getAsString();
            return UUID_CANONICAL.matcher(job).matches() ? new ActionArgs(gameOp, job) : null;
        }
        return hasJob ? null : new ActionArgs(gameOp, "");
    }

    private static ActionArgs actionArgs(String kind, JsonObject payload) {
        return switch (kind) {
            case "moderation.kick", "moderation.ban" -> {
                String playerId = stringField(payload, "playerId");
                if (playerId.isEmpty()) {
                    yield null;
                }
                yield new ActionArgs(playerId, stringField(payload, "reason"));
            }
            case "moderation.say" -> {
                String text = stringField(payload, "text");
                yield text.isEmpty() ? null : new ActionArgs("", text);
            }
            case "moderation.pardon", "moderation.team-kick" -> {
                String player = stringField(payload, "player");
                yield NICKNAME.matcher(player).matches() ? new ActionArgs(player, "") : null;
            }
            case "moderation.class-lock", "moderation.class-unlock" -> {
                String classId = stringField(payload, "classId");
                yield CLASS_ID.matcher(classId).matches() ? new ActionArgs(classId, "") : null;
            }
            case "moderation.range" -> {
                String op = stringField(payload, "op");
                yield RANGE_OPS.contains(op) ? new ActionArgs(op, "") : null;
            }
            case "moderation.stop-room" -> stopRoomArgs(payload);
            case "moderation.list", "moderation.announce" -> new ActionArgs("", "");
            case "moderation.maintenance" -> {
                String op = stringField(payload, "op");
                String reason = stringField(payload, "reason");
                if (!MAINTENANCE_OPS.contains(op) || reason.length() > 120) yield null;
                if ("interrupt".equals(op) == reason.isBlank()) yield null;
                yield new ActionArgs(op, reason);
            }
            default -> null;
        };
    }

    private static ActionArgs stopRoomArgs(JsonObject payload) {
        String roomId = stringField(payload, "roomId");
        String outcome = stringField(payload, "outcome");
        if (!ROOM_ID.matcher(roomId).matches() || !STOP_OUTCOMES.contains(outcome)) return null;
        String reason = stringField(payload, "reason");
        if (reason.length() > 500) return null;
        return new ActionArgs(roomId, reason.isEmpty() ? outcome : outcome + " " + reason);
    }

    private static String namespaceOf(CpEnvelope frame) {
        return stringField(frame.envelopePayload, "namespace");
    }

    private static String targetOf(CpEnvelope frame) {
        return stringField(frame.envelopePayload, "target");
    }

    /** The bare settings value inside the envelope wrapper; null when absent or not an object. */
    private static JsonObject bareValueOf(CpEnvelope frame) {
        JsonObject payload = frame.envelopePayload;
        if (payload == null || !payload.has("value") || !payload.get("value").isJsonObject()) {
            return null;
        }
        return payload.getAsJsonObject("value");
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
        return terminal(frame, state, result, why, null, false);
    }

    private List<CpAck> terminal(
            CpEnvelope frame, CpAck.State state, CpAck.Result result, String why, String output) {
        return terminal(frame, state, result, why, output, false);
    }

    /** {@code output} rides the final ack only; the durable record keeps state and result. */
    private List<CpAck> terminal(
            CpEnvelope frame,
            CpAck.State state,
            CpAck.Result result,
            String why,
            String output,
            boolean markOperator) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String kept = capped(output);
        List<CpAck> acks = terminalAcks(frame, state, result, now, kept);
        store.saveTerminalAndPendingAcks(
                frame.epoch.toString(),
                frame.operationId,
                frame.sequence,
                state,
                result,
                kept,
                markOperator,
                acks);
        return acks;
    }

    private static List<CpAck> terminalAcks(
            CpEnvelope frame,
            CpAck.State state,
            CpAck.Result result,
            OffsetDateTime now,
            String output) {
        CpAck last = CpAck.forEnvelope(frame, state, result, now, output);
        if (state == CpAck.State.UNKNOWN) {
            return List.of(last);
        }
        return List.of(CpAck.forEnvelope(frame, CpAck.State.RECEIVED, now), last);
    }

    private List<CpAck> effectiveChain(CpEnvelope frame, String output) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String kept = capped(output);
        List<CpAck> acks = effectiveChain(frame, now, kept);
        store.saveTerminalAndPendingAcks(
                frame.epoch.toString(),
                frame.operationId,
                frame.sequence,
                CpAck.State.EFFECTIVE,
                CpAck.Result.OK,
                kept,
                false,
                acks);
        return acks;
    }

    private static List<CpAck> effectiveChain(CpEnvelope frame, OffsetDateTime now, String output) {
        return List.of(
                CpAck.forEnvelope(frame, CpAck.State.RECEIVED, now),
                CpAck.forEnvelope(frame, CpAck.State.VALIDATED, now),
                CpAck.forEnvelope(frame, CpAck.State.APPLIED, now),
                CpAck.forEnvelope(frame, CpAck.State.EFFECTIVE, CpAck.Result.OK, now, output));
    }

    private static String capped(String output) {
        if (output == null) return null;
        String text = sanitize(output);
        int end = 0;
        int bytes = 0;
        // Cut on a code point boundary at whichever cap comes first.
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int chars = Character.charCount(codePoint);
            int size = jsonBytes(codePoint);
            if (end + chars > MAX_OUTPUT_CHARS || bytes + size > MAX_OUTPUT_JSON_BYTES) break;
            bytes += size;
            end += chars;
        }
        return text.substring(0, end);
    }

    private static String sanitize(String output) {
        // Other control characters would become \\uXXXX escapes; the UTF-8 round trip turns a
        // lone surrogate (which the control plane refuses) into '?'.
        return new String(
                output.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    /** True when a suggest answer survives the ack output cap byte-for-byte. */
    private static boolean fitsCap(String output) {
        return output == null || capped(output).equals(sanitize(output));
    }

    /** Bytes one code point takes in the ack JSON (Gson escaping, UTF-8). */
    private static int jsonBytes(int codePoint) {
        if (codePoint == '"' || codePoint == '\\' || codePoint == '\n' || codePoint == '\t') {
            return 2;
        }
        if (codePoint == 0x2028 || codePoint == 0x2029) return 6;
        return codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
    }

    private List<CpAck> repost(CpEnvelope frame, CpStore.Terminal prior) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<CpAck> acks =
                prior.state() == CpAck.State.EFFECTIVE
                        ? effectiveChain(frame, now, prior.output())
                        : terminalAcks(frame, prior.state(), prior.result(), now, prior.output());
        store.saveRepostedPendingAcks(acks);
        return acks;
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
