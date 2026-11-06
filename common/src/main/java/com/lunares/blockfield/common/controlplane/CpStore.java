package com.lunares.blockfield.common.controlplane;

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

    public synchronized Lkg loadLkg() {
        Path file = dir.resolve("lkg.json");
        if (!Files.isRegularFile(file)) return null;
        JsonObject root = JsonParser.parseString(read(file)).getAsJsonObject();
        return new Lkg(
                root.get("revision").getAsLong(),
                root.get("sha256").getAsString(),
                root.get("payload").toString());
    }

    public synchronized void saveLkg(long revision, JsonObject payload) {
        JsonObject root = new JsonObject();
        root.addProperty("revision", revision);
        root.addProperty("sha256", sha256(payload.toString()));
        root.add("payload", payload);
        writeAtomic(dir.resolve("lkg.json"), root.toString());
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
