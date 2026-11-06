package com.lunares.blockfield.common.controlplane;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Durable consumer state: last-known-good settings, stream cursor and per-sequence receipts. Every
 * mutation is an atomic file replace or an append, so a crash between effect and receipt stays
 * reconcilable instead of half-applied.
 */
// shortcut: receipts.jsonl is never compacted; settings traffic is a few rows a day.
// Revisit with rotation once action traffic shares this store.
public final class CpStore {
    public record Cursor(String epoch, long sequence) {}

    public record Lkg(long revision, String sha256, String payloadJson) {}

    public record Receipt(String operationId, StateAndResult outcome) {}

    public record StateAndResult(CpAck.State state, CpAck.Result result) {}

    private final Path dir;

    public CpStore(Path dir) {
        this.dir = dir;
    }

    public synchronized Cursor loadCursor() {
        Path file = dir.resolve("cursor");
        if (!Files.isRegularFile(file)) return new Cursor("", 0);
        String[] parts = read(file).split(":", 2);
        if (parts.length != 2) return new Cursor("", 0);
        try {
            return new Cursor(parts[0], Long.parseLong(parts[1]));
        } catch (NumberFormatException e) {
            return new Cursor("", 0);
        }
    }

    /** Cursors only move forward; an epoch change resets the sequence space. */
    public synchronized void saveCursor(String epoch, long sequence) {
        Cursor current = loadCursor();
        if (current.epoch().equals(epoch) && sequence <= current.sequence()) {
            throw new IllegalStateException("cursor must move forward");
        }
        writeAtomic(dir.resolve("cursor"), epoch + ":" + sequence);
    }

    /**
     * Snapshot/stream resoaking: adopt the server cursor only when it moves us forward. A repeated
     * snapshot with an unchanged cursor is a no-op instead of a throw.
     */
    public synchronized void saveCursorIfForward(String epoch, long sequence) {
        Cursor current = loadCursor();
        if (current.epoch().equals(epoch) && sequence <= current.sequence()) return;
        writeAtomic(dir.resolve("cursor"), epoch + ":" + sequence);
    }

    /** Last-known-good and its revision, keyed by namespace/target pair. */
    public synchronized Lkg loadLkg(String namespace, String target) {
        JsonObject all = readJsonObject(dir.resolve("lkg.json"));
        if (all == null) return null;
        JsonElement entry = all.get(key(namespace, target));
        if (entry == null || !entry.isJsonObject()) return null;
        JsonObject root = entry.getAsJsonObject();
        return new Lkg(
                root.get("revision").getAsLong(),
                root.get("sha256").getAsString(),
                root.get("payload").toString());
    }

    public synchronized void saveLkg(
            String namespace, String target, long revision, JsonObject payload) {
        JsonObject all = readJsonObject(dir.resolve("lkg.json"));
        if (all == null) all = new JsonObject();
        JsonObject root = new JsonObject();
        root.addProperty("revision", revision);
        root.addProperty("sha256", sha256(payload.toString()));
        root.add("payload", payload);
        all.add(key(namespace, target), root);
        writeAtomic(dir.resolve("lkg.json"), all.toString());
    }

    /** Snapshot revisions whose effect is uncertain: applied once, then skipped, never retried. */
    public synchronized void markOperatorNeeded(String namespace, String target, long revision) {
        JsonObject all = readJsonObject(dir.resolve("operator.json"));
        if (all == null) all = new JsonObject();
        String k = key(namespace, target);
        long marked = all.has(k) ? all.get(k).getAsLong() : Long.MIN_VALUE;
        if (revision > marked) {
            all.addProperty(k, revision);
            writeAtomic(dir.resolve("operator.json"), all.toString());
        }
    }

    public synchronized boolean isOperatorNeeded(String namespace, String target, long revision) {
        JsonObject all = readJsonObject(dir.resolve("operator.json"));
        return all != null
                && all.has(key(namespace, target))
                && revision <= all.get(key(namespace, target)).getAsLong();
    }

    public synchronized boolean hasOperatorNeeded() {
        JsonObject all = readJsonObject(dir.resolve("operator.json"));
        return all != null && !all.isEmpty();
    }

    /** A newer applied revision moots older uncertainty markers for the pair. */
    public synchronized void clearOperatorNeeded(String namespace, String target) {
        JsonObject all = readJsonObject(dir.resolve("operator.json"));
        if (all != null && all.has(key(namespace, target))) {
            all.remove(key(namespace, target));
            writeAtomic(dir.resolve("operator.json"), all.toString());
        }
    }

    /** Ack chain generated but not yet accepted by the server; flushed before the next stream. */
    public synchronized void savePendingAcks(List<String> ackJson) {
        JsonArray array = new JsonArray();
        for (String body : ackJson) array.add(JsonParser.parseString(body));
        writeAtomic(dir.resolve("pending-acks.json"), array.toString());
    }

    public synchronized List<String> loadPendingAcks() {
        Path file = dir.resolve("pending-acks.json");
        if (!Files.isRegularFile(file)) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement entry : JsonParser.parseString(read(file)).getAsJsonArray()) {
            out.add(entry.toString());
        }
        return out;
    }

    public synchronized void clearPendingAcks() {
        try {
            Files.deleteIfExists(dir.resolve("pending-acks.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void recordReceipt(
            String operationId, long sequence, CpAck.State state, CpAck.Result result) {
        JsonObject root = new JsonObject();
        root.addProperty("operationId", operationId);
        root.addProperty("sequence", sequence);
        root.addProperty("state", state.name());
        root.addProperty("result", result.name());
        try {
            Files.createDirectories(dir);
            Files.writeString(
                    dir.resolve("receipts.jsonl"),
                    root + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Latest receipt for a sequence, or null when the frame was never decided. */
    public synchronized Receipt lastReceipt(long sequence) {
        Path file = dir.resolve("receipts.jsonl");
        if (!Files.isRegularFile(file)) return null;
        Receipt found = null;
        for (String line : read(file).split("\n")) {
            if (line.isBlank()) continue;
            JsonObject root = JsonParser.parseString(line).getAsJsonObject();
            if (root.get("sequence").getAsLong() != sequence) continue;
            found =
                    new Receipt(
                            root.get("operationId").getAsString(),
                            new StateAndResult(
                                    CpAck.State.valueOf(root.get("state").getAsString()),
                                    CpAck.Result.valueOf(root.get("result").getAsString())));
        }
        return found;
    }

    public synchronized List<Receipt> receipts() {
        Path file = dir.resolve("receipts.jsonl");
        List<Receipt> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        for (String line : read(file).split("\n")) {
            if (line.isBlank()) continue;
            JsonObject root = JsonParser.parseString(line).getAsJsonObject();
            out.add(
                    new Receipt(
                            root.get("operationId").getAsString(),
                            new StateAndResult(
                                    CpAck.State.valueOf(root.get("state").getAsString()),
                                    CpAck.Result.valueOf(root.get("result").getAsString()))));
        }
        return out;
    }

    private String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String key(String namespace, String target) {
        return namespace + "/" + target;
    }

    private JsonObject readJsonObject(Path file) {
        if (!Files.isRegularFile(file)) return null;
        JsonElement root = JsonParser.parseString(read(file));
        return root.isJsonObject() ? root.getAsJsonObject() : null;
    }

    private void writeAtomic(Path file, String text) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
