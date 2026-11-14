package com.lunares.blockfield.common.controlplane;

import com.google.gson.JsonObject;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * One {@code POST /v2/agents/{id}/acks} body (control-plane origin/main 6df1f95, {@code
 * cpv2/models.py:Ack}). The envelope identity (epoch/sequence/generation/revision/hash) is echoed
 * back; the server rejects anything that does not match its outbox row.
 */
public final class CpAck {
    /** Wire order matters: the server enforces received first, then each step in order. */
    public enum State {
        RECEIVED,
        VALIDATED,
        APPLIED,
        EFFECTIVE,
        REJECTED,
        UNKNOWN;

        public boolean terminal() {
            return this == REJECTED || this == UNKNOWN || this == EFFECTIVE;
        }
    }

    public enum Result {
        OK,
        INVALID_SCHEMA,
        STALE_GENERATION,
        APPLY_FAILED,
        EFFECT_UNCERTAIN;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public final UUID epoch;
    public final long sequence;
    public final String generation;
    public final Long revision;
    public final String payloadHash;
    public final State state;
    public final OffsetDateTime actualAt;
    public final Result result;

    /** Command output for the control plane (informational, not part of the receipt), or null. */
    public final String output;

    /**
     * Frame identity for post-ack hook matching (epoch/sequence/operationId). Never serialized:
     * older control planes refuse unknown ack keys, so the wire body stays exactly the frozen
     * envelope identity.
     */
    public final String operationId;

    public CpAck(
            UUID epoch,
            long sequence,
            String generation,
            Long revision,
            String payloadHash,
            State state,
            OffsetDateTime actualAt,
            Result result) {
        this(epoch, sequence, generation, revision, payloadHash, state, actualAt, result, null);
    }

    public CpAck(
            UUID epoch,
            long sequence,
            String generation,
            Long revision,
            String payloadHash,
            State state,
            OffsetDateTime actualAt,
            Result result,
            String output) {
        this(
                epoch,
                sequence,
                generation,
                revision,
                payloadHash,
                state,
                actualAt,
                result,
                output,
                null);
    }

    public CpAck(
            UUID epoch,
            long sequence,
            String generation,
            Long revision,
            String payloadHash,
            State state,
            OffsetDateTime actualAt,
            Result result,
            String output,
            String operationId) {
        this.output = output;
        this.operationId = operationId;
        this.epoch = epoch;
        this.sequence = sequence;
        this.generation = generation;
        this.revision = revision;
        this.payloadHash = payloadHash;
        this.state = state;
        this.actualAt = actualAt;
        this.result = result;
    }

    public static CpAck forEnvelope(CpEnvelope envelope, State state, OffsetDateTime now) {
        return forEnvelope(envelope, state, Result.OK, now);
    }

    public static CpAck forEnvelope(
            CpEnvelope envelope, State state, Result result, OffsetDateTime now) {
        return forEnvelope(envelope, state, result, now, null);
    }

    public static CpAck forEnvelope(
            CpEnvelope envelope, State state, Result result, OffsetDateTime now, String output) {
        return new CpAck(
                envelope.epoch,
                envelope.sequence,
                envelope.generation,
                envelope.revision,
                envelope.payloadHash,
                state,
                now,
                result,
                output,
                envelope.operationId);
    }

    public String toJson() {
        JsonObject body = new JsonObject();
        body.addProperty("epoch", epoch.toString());
        body.addProperty("sequence", sequence);
        body.addProperty("generation", generation);
        if (revision == null) body.add("revision", null);
        else body.addProperty("revision", revision);
        body.addProperty("payloadHash", payloadHash);
        body.addProperty("state", state.name().toLowerCase(Locale.ROOT));
        body.addProperty("actualAt", actualAt.toString());
        body.addProperty("result", result.wire());
        // Absent, not null: control planes before the output field reject unknown keys.
        if (output != null) body.addProperty("output", output);
        return body.toString();
    }
}
