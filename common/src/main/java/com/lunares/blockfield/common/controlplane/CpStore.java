package com.lunares.blockfield.common.controlplane;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable consumer state in ONE small file ({@code state.json}): epoch, generation, cursor,
 * last-known-good per namespace/target, last terminal ack per (epoch, operationId), started
 * non-idempotent actions, and operator-needed markers. Every mutation rewrites the file atomically
 * (temp file + fsync + rename), so a crash between effect and receipt replays safely instead of
 * half-applying. A corrupt or unreadable file resets to empty: the client re-snapshots from the
 * server rather than crashing.
 */
public final class CpStore {
    public record Cursor(String epoch, long sequence) {}

    public record Lkg(long revision, String sha256, String payloadJson) {}

    /** {@code output}: the final ack's text, kept only until the cursor passes the frame. */
    public record Terminal(
            String operationId,
            long sequence,
            CpAck.State state,
            CpAck.Result result,
            String output) {
        public Terminal(String operationId, long sequence, CpAck.State state, CpAck.Result result) {
            this(operationId, sequence, state, result, null);
        }
    }

    private static final String FILE = "state.json";

    private final Path dir;
    private final Runnable beforePersist;

    public CpStore(Path dir) {
        this(dir, () -> {});
    }

    CpStore(Path dir, Runnable beforePersist) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.beforePersist = Objects.requireNonNull(beforePersist, "beforePersist");
    }

    public synchronized Cursor loadCursor() {
        JsonObject state = load();
        return new Cursor(stringField(state, "epoch", ""), longField(state, "cursor", 0));
    }

    /**
     * Cursors only move forward; an epoch change resets the sequence space. A repeated snapshot
     * with an unchanged cursor is a no-op instead of a throw. Adopting a new epoch drops the old
     * epoch's terminals, started actions and operator markers: stale decisions must never block the
     * new epoch (its reconciliation happens at rotation, per the v2 contract).
     */
    public synchronized void saveCursorIfForward(String epoch, long sequence) {
        Cursor current = loadCursor();
        if (current.epoch().equals(epoch) && sequence <= current.sequence()) return;
        JsonObject state = load();
        if (!current.epoch().equals(epoch)) {
            state.remove("terminal");
            state.remove("started");
            state.remove("operator");
        } else {
            // Frames at or below the cursor are acknowledged, output included: drop the copy so
            // the state file stays bounded by the in-flight window.
            JsonObject all = objectField(state, "terminal");
            if (all != null) {
                for (String key : all.keySet()) {
                    JsonObject entry = all.getAsJsonObject(key);
                    if (longField(entry, "sequence", 0) <= sequence) entry.remove("output");
                }
            }
        }
        state.addProperty("epoch", epoch);
        state.addProperty("cursor", sequence);
        save(state);
    }

    public record PendingAcks(List<CpAck> acks, int sent) {}

    /** Persist the exact wire bodies so a lost response can be retried idempotently. */
    public synchronized void savePendingAcks(List<CpAck> acks, int sent) {
        JsonObject state = load();
        putPendingAcks(state, acks, sent);
        save(state);
    }

    /**
     * Persist a reposted chain only when no matching pending chain is already queued on disk, so
     * partially delivered chains keep their {@code sent} offset.
     */
    public synchronized void saveRepostedPendingAcks(List<CpAck> acks) {
        JsonObject state = load();
        JsonObject existing = objectField(state, "pendingAcks");
        if (existing != null && matchesPendingIdentity(existing, acks.get(0))) return;
        putPendingAcks(state, acks, 0);
        save(state);
    }

    private static boolean matchesPendingIdentity(JsonObject pending, CpAck first) {
        if (!pending.has("acks") || !pending.get("acks").isJsonArray()) return false;
        JsonArray entries = pending.getAsJsonArray("acks");
        if (entries.isEmpty() || !entries.get(0).isJsonObject()) return false;
        JsonObject head = entries.get(0).getAsJsonObject();
        String existingOp =
                head.has("operationId") && !head.get("operationId").isJsonNull()
                        ? stringField(head, "operationId", null)
                        : null;
        return first.epoch.toString().equals(stringField(head, "epoch", ""))
                && first.sequence == longField(head, "sequence", -1L)
                && Objects.equals(first.generation, stringField(head, "generation", null))
                && Objects.equals(first.operationId, existingOp);
    }

    private static void putPendingAcks(JsonObject state, List<CpAck> acks, int sent) {
        JsonObject pending = new JsonObject();
        JsonArray entries = new JsonArray();
        for (CpAck ack : acks) {
            JsonObject entry = JsonParser.parseString(ack.toJson()).getAsJsonObject();
            entry.addProperty("operationId", ack.operationId);
            entries.add(entry);
        }
        pending.add("acks", entries);
        pending.addProperty("sent", sent);
        state.add("pendingAcks", pending);
        CpAck lastAck = acks.get(acks.size() - 1);
        if (lastAck.state == CpAck.State.UNKNOWN && lastAck.operationId != null) {
            String epochId = lastAck.epoch.toString();
            putTerminal(
                    state,
                    epochId,
                    lastAck.operationId,
                    lastAck.sequence,
                    lastAck.state,
                    lastAck.result,
                    null);
            if (started(state).has(terminalKey(epochId, lastAck.operationId))) {
                operator(state).addProperty("action/" + lastAck.operationId, true);
            }
        }
    }

    public synchronized PendingAcks loadPendingAcks() {
        JsonObject pending = objectField(load(), "pendingAcks");
        if (pending == null) return null;
        List<CpAck> acks = new ArrayList<>();
        for (JsonElement item : pending.getAsJsonArray("acks")) {
            JsonObject a = item.getAsJsonObject();
            acks.add(
                    new CpAck(
                            UUID.fromString(a.get("epoch").getAsString()),
                            a.get("sequence").getAsLong(),
                            a.get("generation").getAsString(),
                            a.get("revision").isJsonNull() ? null : a.get("revision").getAsLong(),
                            a.get("payloadHash").isJsonNull()
                                    ? null
                                    : a.get("payloadHash").getAsString(),
                            CpAck.State.valueOf(
                                    a.get("state").getAsString().toUpperCase(Locale.ROOT)),
                            OffsetDateTime.parse(a.get("actualAt").getAsString()),
                            CpAck.Result.valueOf(
                                    a.get("result").getAsString().toUpperCase(Locale.ROOT)),
                            a.has("output") ? a.get("output").getAsString() : null,
                            a.has("operationId") && !a.get("operationId").isJsonNull()
                                    ? a.get("operationId").getAsString()
                                    : null));
        }
        return new PendingAcks(List.copyOf(acks), pending.get("sent").getAsInt());
    }

    public synchronized void clearPendingAcks() {
        JsonObject state = load();
        state.remove("pendingAcks");
        save(state);
    }

    /** Last-known-good and its revision, keyed by namespace/target pair. */
    public synchronized Lkg loadLkg(String namespace, String target) {
        JsonObject entry = child(load(), "lkg", key(namespace, target));
        if (entry == null) return null;
        return new Lkg(
                longField(entry, "revision", 0),
                stringField(entry, "sha256", ""),
                entry.has("payload") ? entry.get("payload").toString() : "{}");
    }

    /**
     * Applied settings frame or snapshot entry: LKG, terminal receipt and cleared marker, one
     * write.
     */
    public synchronized void saveAppliedFrame(
            String namespace,
            String target,
            long revision,
            JsonObject payload,
            String epoch,
            String operationId,
            long sequence) {
        JsonObject state = load();
        putAppliedFrame(state, namespace, target, revision, payload, epoch, operationId, sequence);
        save(state);
    }

    /**
     * Applied settings frame: LKG, terminal receipt, cleared operator marker and pending ack chain
     * in one atomic write.
     */
    public synchronized void saveAppliedFrameAndPendingAcks(
            String namespace,
            String target,
            long revision,
            JsonObject payload,
            String epoch,
            String operationId,
            long sequence,
            List<CpAck> acks) {
        JsonObject state = load();
        putAppliedFrame(state, namespace, target, revision, payload, epoch, operationId, sequence);
        putPendingAcks(state, acks, 0);
        save(state);
    }

    private static void putAppliedFrame(
            JsonObject state,
            String namespace,
            String target,
            long revision,
            JsonObject payload,
            String epoch,
            String operationId,
            long sequence) {
        lkg(state).add(key(namespace, target), lkgEntry(revision, payload));
        putTerminal(
                state, epoch, operationId, sequence, CpAck.State.EFFECTIVE, CpAck.Result.OK, null);
        operator(state).remove(key(namespace, target));
    }

    /** Applied snapshot entry without any ack identity: LKG and cleared marker, one write. */
    public synchronized void saveAppliedSnapshot(
            String namespace, String target, long revision, JsonObject payload) {
        JsonObject state = load();
        lkg(state).add(key(namespace, target), lkgEntry(revision, payload));
        operator(state).remove(key(namespace, target));
        save(state);
    }

    /**
     * Effect-uncertain settings frame: operator marker plus terminal receipt, one write, so a
     * replay re-posts UNKNOWN instead of re-applying blindly. The marker is monotonic through the
     * same max logic as {@link #markOperatorNeeded}, never downgraded.
     */
    public synchronized void saveUncertainFrame(
            String namespace,
            String target,
            long revision,
            String epoch,
            String operationId,
            long sequence,
            CpAck.Result result) {
        JsonObject state = load();
        putUncertainFrame(state, namespace, target, revision, epoch, operationId, sequence, result);
        save(state);
    }

    /**
     * Effect-uncertain settings frame: monotonic operator marker, terminal UNKNOWN receipt and
     * pending ack chain in one atomic write.
     */
    public synchronized void saveUncertainFrameAndPendingAcks(
            String namespace,
            String target,
            long revision,
            String epoch,
            String operationId,
            long sequence,
            CpAck.Result result,
            List<CpAck> acks) {
        JsonObject state = load();
        putUncertainFrame(state, namespace, target, revision, epoch, operationId, sequence, result);
        putPendingAcks(state, acks, 0);
        save(state);
    }

    private static void putUncertainFrame(
            JsonObject state,
            String namespace,
            String target,
            long revision,
            String epoch,
            String operationId,
            long sequence,
            CpAck.Result result) {
        markMax(operator(state), key(namespace, target), revision);
        putTerminal(state, epoch, operationId, sequence, CpAck.State.UNKNOWN, result, null);
    }

    /** Last terminal ack for an operation, or null when it was never decided. */
    public synchronized Terminal lastTerminal(String epoch, String operationId) {
        JsonObject entry = child(load(), "terminal", terminalKey(epoch, operationId));
        if (entry == null) return null;
        try {
            return new Terminal(
                    stringField(entry, "operationId", operationId),
                    longField(entry, "sequence", 0),
                    CpAck.State.valueOf(stringField(entry, "state", "")),
                    CpAck.Result.valueOf(stringField(entry, "result", "")),
                    entry.has("output") && !entry.get("output").isJsonNull()
                            ? entry.get("output").getAsString()
                            : null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Terminal acks only: a replayed operation re-posts its decision, never re-applies. */
    public synchronized void recordTerminal(
            String epoch,
            String operationId,
            long sequence,
            CpAck.State state,
            CpAck.Result result) {
        recordTerminal(epoch, operationId, sequence, state, result, null);
    }

    /** Same, keeping the final ack's output so a repost after a crash still carries it. */
    public synchronized void recordTerminal(
            String epoch,
            String operationId,
            long sequence,
            CpAck.State state,
            CpAck.Result result,
            String output) {
        JsonObject stateJson = load();
        putTerminal(stateJson, epoch, operationId, sequence, state, result, output);
        save(stateJson);
    }

    /**
     * Persist a terminal receipt, optional action-operator marker, and queued ack chain in one
     * atomic write so a crash never leaves a terminal receipt without its pending chain.
     */
    public synchronized void saveTerminalAndPendingAcks(
            String epoch,
            String operationId,
            long sequence,
            CpAck.State ackState,
            CpAck.Result result,
            String output,
            boolean markOperator,
            List<CpAck> acks) {
        JsonObject state = load();
        putTerminal(state, epoch, operationId, sequence, ackState, result, output);
        if (markOperator && operationId != null) {
            operator(state).addProperty("action/" + operationId, true);
        }
        putPendingAcks(state, acks, 0);
        save(state);
    }

    /**
     * Atomically demote an operation to UNKNOWN ({@code effect_uncertain}) and set the action
     * operator marker when the operation had already started.
     */
    public synchronized void demoteUncertain(
            String epoch, String operationId, long sequence, boolean alwaysRecord) {
        JsonObject state = load();
        JsonObject existingEntry = child(state, "terminal", terminalKey(epoch, operationId));
        boolean shouldRecord =
                alwaysRecord
                        || existingEntry == null
                        || CpAck.State.EFFECTIVE
                                .name()
                                .equals(stringField(existingEntry, "state", ""));
        boolean actionStarted = started(state).has(terminalKey(epoch, operationId));
        if (!shouldRecord && !actionStarted) return;
        if (shouldRecord) {
            putTerminal(
                    state,
                    epoch,
                    operationId,
                    sequence,
                    CpAck.State.UNKNOWN,
                    CpAck.Result.EFFECT_UNCERTAIN,
                    null);
        }
        if (actionStarted) {
            operator(state).addProperty("action/" + operationId, true);
        }
        save(state);
    }

    private static void putTerminal(
            JsonObject stateJson,
            String epoch,
            String operationId,
            long sequence,
            CpAck.State state,
            CpAck.Result result,
            String output) {
        if (!state.terminal()) throw new IllegalArgumentException("terminal states only");
        JsonObject entry = terminalEntry(operationId, sequence, state, result);
        if (output != null) entry.addProperty("output", output);
        terminal(stateJson).add(terminalKey(epoch, operationId), entry);
    }

    public synchronized boolean hasTerminalState(CpAck.State state) {
        JsonObject all = objectField(load(), "terminal");
        if (all == null) return false;
        for (String k : all.keySet()) {
            JsonObject entry = objectField(all, k);
            if (entry != null && state.name().equals(stringField(entry, "state", ""))) return true;
        }
        return false;
    }

    /**
     * Non-idempotent action started: written BEFORE the handler runs, so a crash between start and
     * terminal state never re-runs the handler on replay.
     */
    public synchronized void markActionStarted(String epoch, String operationId, String kind) {
        JsonObject state = load();
        if (!started(state).has(terminalKey(epoch, operationId))) {
            started(state).addProperty(terminalKey(epoch, operationId), kind);
            save(state);
        }
    }

    public synchronized boolean isActionStarted(String epoch, String operationId) {
        return started(load()).has(terminalKey(epoch, operationId));
    }

    /** Snapshot revisions whose effect is uncertain: applied once, then skipped, never retried. */
    public synchronized void markOperatorNeeded(String namespace, String target, long revision) {
        JsonObject state = load();
        if (markMax(operator(state), key(namespace, target), revision)) save(state);
    }

    public synchronized boolean isOperatorNeeded(String namespace, String target, long revision) {
        JsonObject markers = operator(load());
        return markers != null
                && revision <= numberField(markers, key(namespace, target), Long.MIN_VALUE);
    }

    public synchronized boolean hasOperatorNeeded() {
        JsonObject markers = operator(load());
        return markers != null && !markers.isEmpty();
    }

    /** A newer applied revision moots older uncertainty markers for the pair. */
    public synchronized void clearOperatorNeeded(String namespace, String target) {
        JsonObject state = load();
        if (operator(state).has(key(namespace, target))) {
            operator(state).remove(key(namespace, target));
            save(state);
        }
    }

    /** Started-but-unfinished non-idempotent action: an operator must reconcile it. */
    public synchronized void markActionOperatorNeeded(String operationId) {
        JsonObject state = load();
        String k = "action/" + operationId;
        if (!operator(state).has(k)) {
            operator(state).addProperty(k, true);
            save(state);
        }
    }

    public synchronized boolean isActionOperatorNeeded(String operationId) {
        JsonObject markers = operator(load());
        return markers != null && markers.has("action/" + operationId);
    }

    private static String key(String namespace, String target) {
        return namespace + "/" + target;
    }

    private static String terminalKey(String epoch, String operationId) {
        return epoch + "|" + operationId;
    }

    /** Monotonic marker write: the single writer for operator revisions, never downgrades. */
    private static boolean markMax(JsonObject operator, String k, long revision) {
        if (revision > numberField(operator, k, Long.MIN_VALUE)) {
            operator.addProperty(k, revision);
            return true;
        }
        return false;
    }

    private static JsonObject lkgEntry(long revision, JsonObject payload) {
        JsonObject root = new JsonObject();
        root.addProperty("revision", revision);
        root.addProperty("sha256", sha256(payload.toString()));
        root.add("payload", payload);
        return root;
    }

    private static JsonObject terminalEntry(
            String operationId, long sequence, CpAck.State state, CpAck.Result result) {
        JsonObject root = new JsonObject();
        root.addProperty("operationId", operationId);
        root.addProperty("sequence", sequence);
        root.addProperty("state", state.name());
        root.addProperty("result", result.name());
        return root;
    }

    private static JsonObject lkg(JsonObject state) {
        return section(state, "lkg");
    }

    private static JsonObject terminal(JsonObject state) {
        return section(state, "terminal");
    }

    private static JsonObject started(JsonObject state) {
        return section(state, "started");
    }

    private static JsonObject operator(JsonObject state) {
        return section(state, "operator");
    }

    private static JsonObject section(JsonObject state, String name) {
        JsonObject section = objectField(state, name);
        if (section == null) {
            section = new JsonObject();
            state.add(name, section);
        }
        return section;
    }

    private static JsonObject child(JsonObject state, String section, String k) {
        JsonObject all = objectField(state, section);
        if (all == null) return null;
        return objectField(all, k);
    }

    /** Corrupt or unreadable state resets to empty; the client then re-snapshots, never crashes. */
    private JsonObject load() {
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) return new JsonObject();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (root.isJsonObject()) return root.getAsJsonObject();
        } catch (RuntimeException | IOException e) {
            // Fall through to a fresh state.
        }
        return new JsonObject();
    }

    private void save(JsonObject state) {
        beforePersist.run();
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(FILE + ".tmp");
            byte[] bytes = state.toString().getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel =
                    FileChannel.open(
                            tmp,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            Files.move(tmp, dir.resolve(FILE), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stringField(JsonObject root, String k, String fallback) {
        if (root == null || !root.has(k) || !root.get(k).isJsonPrimitive()) return fallback;
        try {
            return root.get(k).getAsString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long longField(JsonObject root, String k, long fallback) {
        if (root == null || !root.has(k) || !root.get(k).isJsonPrimitive()) return fallback;
        try {
            return root.get(k).getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long numberField(JsonObject root, String k, long fallback) {
        if (root == null || !root.has(k) || !root.get(k).isJsonPrimitive()) return fallback;
        try {
            if (!root.get(k).getAsJsonPrimitive().isNumber()) return fallback;
            return root.get(k).getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static JsonObject objectField(JsonObject root, String k) {
        if (root == null || !root.has(k) || !root.get(k).isJsonObject()) return null;
        return root.get(k).getAsJsonObject();
    }

    static String sha256(String text) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
