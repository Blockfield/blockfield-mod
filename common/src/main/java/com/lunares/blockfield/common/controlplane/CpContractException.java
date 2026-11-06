package com.lunares.blockfield.common.controlplane;

/** A frame that violates the frozen v2 wire contract. Never acked: it carries no valid identity. */
public final class CpContractException extends RuntimeException {
    public CpContractException(String message) {
        super(message);
    }
}
