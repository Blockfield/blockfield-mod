package com.lunares.blockfield.common.controlplane;

/**
 * The control plane permanently rejected an ack: a validation-class HTTP status ({@code 400},
 * {@code 404}, {@code 422}) that retrying the same payload will not fix. When {@code received} was
 * already accepted, the consumer demotes the durable state to {@code unknown} ({@code
 * effect_uncertain}) with the post-ack hook still pending and retries fallback {@code unknown}
 * across transient failures until the control plane records a terminal state before saving the
 * cursor; otherwise (or if the fallback itself is rejected), any durable {@code effective} state is
 * demoted to {@code unknown}, the hook fails with this exception, the local cursor does not
 * advance, and the client triggers a resync. Every transient failure (HTTP 5xx, {@code 408}/{@code
 * 429}, other {@code 4xx}, IO, timeouts) keeps the hook pending, and {@code 409} re-syncs. See
 * {@code CpSseClient} for the full status table.
 */
public final class CpAckRejectedException extends RuntimeException {
    private final int statusCode;

    public CpAckRejectedException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
