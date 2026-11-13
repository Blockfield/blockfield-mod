package com.lunares.blockfield.common.controlplane;

/**
 * The two service bearers a game consumer holds: the command credential for the control stream
 * (snapshot/events/acks) and a strictly separate read-only credential for console log/suggest
 * reads. Reusing one secret for both would give every console reader full command authority
 * (today's shared-RCON-password flaw), so equal or blank values are rejected at construction. The
 * server repo wires the console half into ConsoleLogPush; this package spends only the command half.
 */
public record CpCredentials(String commandBearer, String consoleReadBearer) {
    public CpCredentials {
        if (commandBearer == null
                || commandBearer.isBlank()
                || consoleReadBearer == null
                || consoleReadBearer.isBlank()) {
            throw new IllegalArgumentException("both control-plane bearers must be set");
        }
        if (commandBearer.equals(consoleReadBearer)) {
            throw new IllegalArgumentException(
                    "console-read bearer must differ from command bearer");
        }
    }
}
