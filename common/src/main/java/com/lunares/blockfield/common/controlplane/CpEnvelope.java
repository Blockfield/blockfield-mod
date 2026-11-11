package com.lunares.blockfield.common.controlplane;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * One control-plane delivery envelope: the {@code data} payload of an SSE {@code control} event
 * (control-plane origin/main 6df1f95, {@code cpv2/delivery.py:batch}). Unknown extra keys are
 * ignored so a newer server stays readable; the {@code schema} gate rejects anything we cannot
 * interpret.
 */
public final class CpEnvelope {
    static final int SCHEMA = 2;

    public final UUID epoch;
    public final long sequence;
    public final String operationId;
    public final String audience;
    public final String generation;

    /** Null for action frames, which carry no settings revision. */
    public final Long revision;

    public final String payloadHash;
    public final long authorizationEpoch;
    public final OffsetDateTime expiresAt;
    public final boolean executable;

    /** Server verdict for non-executable frames (expired/rejected/superseded), else null. */
    public final String reason;

    /** Present exactly when {@link #executable} is true. */
    public final JsonObject envelopePayload;

    private CpEnvelope(
            UUID epoch,
            long sequence,
            String operationId,
            String audience,
            String generation,
            Long revision,
            String payloadHash,
            long authorizationEpoch,
            OffsetDateTime expiresAt,
            boolean executable,
            String reason,
            JsonObject envelopePayload) {
        this.epoch = epoch;
        this.sequence = sequence;
        this.operationId = operationId;
        this.audience = audience;
        this.generation = generation;
        this.revision = revision;
        this.payloadHash = payloadHash;
        this.authorizationEpoch = authorizationEpoch;
        this.expiresAt = expiresAt;
        this.executable = executable;
        this.reason = reason;
        this.envelopePayload = envelopePayload;
    }

    /** Matches the {@code Last-Event-ID} / {@code id} wire form {@code epoch:sequence}. */
    public String cursorId() {
        return epoch + ":" + sequence;
    }

    public static CpEnvelope parse(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new CpContractException("frame is not JSON: " + e.getMessage());
        }
        if (!root.isJsonObject()) throw new CpContractException("frame is not an object");
        JsonObject frame = root.getAsJsonObject();
        if (requiredLong(frame, "schema", 0) != SCHEMA) {
            throw new CpContractException("unsupported frame schema");
        }
        UUID epoch = requiredUuid(frame, "epoch");
        long sequence = requiredLong(frame, "sequence", 1);
        String operationId = requiredString(frame, "operationId");
        String audience = requiredString(frame, "audience");
        String generation = requiredString(frame, "generation");
        Long revision =
                frame.has("revision") && !frame.get("revision").isJsonNull()
                        ? frame.get("revision").getAsLong()
                        : null;
        String payloadHash = requiredString(frame, "payloadHash");
        if (!payloadHash.matches("[0-9a-f]{64}")) throw new CpContractException("bad payloadHash");
        // Service-issued frames carry no account, so CP emits explicit null; the
        // consumer never reads the epoch, and 0 keeps the primitive as the sentinel.
        long authorizationEpoch =
                frame.has("authorizationEpoch") && frame.get("authorizationEpoch").isJsonNull()
                        ? 0
                        : requiredLong(frame, "authorizationEpoch", 0);
        OffsetDateTime expiresAt;
        try {
            expiresAt = OffsetDateTime.parse(requiredString(frame, "expiresAt"));
        } catch (DateTimeParseException e) {
            throw new CpContractException("bad expiresAt");
        }
        if (!frame.has("executable") || !frame.get("executable").isJsonPrimitive()) {
            throw new CpContractException("frame misses executable");
        }
        boolean executable = frame.get("executable").getAsBoolean();
        String reason =
                frame.has("reason") && !frame.get("reason").isJsonNull()
                        ? frame.get("reason").getAsString()
                        : null;
        JsonObject envelopePayload =
                frame.has("payload") && frame.get("payload").isJsonObject()
                        ? frame.get("payload").getAsJsonObject()
                        : null;
        if (executable && envelopePayload == null) {
            throw new CpContractException("executable frame misses payload");
        }
        if (!executable && envelopePayload != null) {
            throw new CpContractException("non-executable frame carries a payload");
        }
        return new CpEnvelope(
                epoch,
                sequence,
                operationId,
                audience,
                generation,
                revision,
                payloadHash,
                authorizationEpoch,
                expiresAt,
                executable,
                reason,
                envelopePayload);
    }

    static String requiredString(JsonObject frame, String key) {
        if (!frame.has(key) || !frame.get(key).isJsonPrimitive()) {
            throw new CpContractException("frame misses " + key);
        }
        return frame.get(key).getAsString();
    }

    static long requiredLong(JsonObject frame, String key, long min) {
        if (!frame.has(key) || !frame.get(key).isJsonPrimitive()) {
            throw new CpContractException("frame misses " + key);
        }
        try {
            long value = frame.get(key).getAsLong();
            if (value < min) throw new CpContractException("frame has out-of-range " + key);
            return value;
        } catch (NumberFormatException e) {
            throw new CpContractException("frame has bad " + key);
        }
    }

    static UUID requiredUuid(JsonObject frame, String key) {
        try {
            return UUID.fromString(requiredString(frame, key));
        } catch (IllegalArgumentException e) {
            throw new CpContractException("frame has bad " + key);
        }
    }
}
