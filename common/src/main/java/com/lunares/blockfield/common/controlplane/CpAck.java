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

    public CpAck(
            UUID epoch,
            long sequence,
            String generation,
            Long revision,
            String payloadHash,
            State state,
            OffsetDateTime actualAt,
            Result result) {
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
        return new CpAck(
                envelope.epoch,
                envelope.sequence,
                envelope.generation,
                envelope.revision,
                envelope.payloadHash,
                state,
                now,
                result);
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
        return body.toString();
    }
}
