package com.lunares.blockfield.common.controlplane;

/** A frame we cannot interpret or continue from: re-snapshot, then resume the stream. */
public final class CpResyncException extends RuntimeException {
    public CpResyncException(String message) {
        super(message);
    }
}
