package com.lunares.blockfield.common.controlplane;

/**
 * The control plane permanently rejected an ack: a validation-class HTTP status ({@code 400},
 * {@code 404}, {@code 422}) that no retry will fix. The sender fails the post-ack hook, drops the
 * stored receipt and advances the cursor instead of retrying; every other ack-post failure (HTTP
 * 5xx, {@code 408}/{@code 429}, other {@code 4xx}, IO, timeouts) replays the receipt with the hook
 * pending, and {@code 409} re-syncs. See {@code CpSseClient} for the full status table.
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
