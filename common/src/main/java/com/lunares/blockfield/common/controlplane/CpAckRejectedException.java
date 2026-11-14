package com.lunares.blockfield.common.controlplane;

/**
 * The control plane permanently rejected an ack: a validation-class HTTP status ({@code 400},
 * {@code 404}, {@code 422}) that retrying the same payload will not fix. When the server already
 * accepted {@code received} (advancing its cursor), the client attempts one fallback {@code
 * unknown} ack so the row reaches a terminal state before saving the local cursor; otherwise (or if
 * the fallback itself is rejected), the local cursor does not advance and the client triggers a
 * resync instead of stranding the frame. In all terminal-rejection cases the post-ack hook fails
 * with this exception; every other ack-post failure (HTTP 5xx, {@code 408}/{@code 429}, other
 * {@code 4xx}, IO, timeouts) replays the receipt with the hook pending, and {@code 409} re-syncs.
 * See {@code CpSseClient} for the full status table.
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
