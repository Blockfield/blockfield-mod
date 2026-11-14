package com.lunares.blockfield.common.controlplane;

/**
 * The control plane permanently rejected an ack (non-retryable HTTP 4xx, excluding 408/409/429).
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
