package com.lunares.blockfield.common.controlplane;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Command and console-read bearers must be distinct credentials, never one shared secret. */
class CpCredentialsTest {
    @Test
    void distinctBearersAccepted() {
        CpCredentials credentials = new CpCredentials("command-bearer", "console-bearer");

        assertEquals("command-bearer", credentials.commandBearer());
        assertEquals("console-bearer", credentials.consoleReadBearer());
    }

    @Test
    void reusedBearerRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CpCredentials("same", "same"));
    }

    @Test
    void blankBearerRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CpCredentials("", "console-bearer"));
        assertThrows(
                IllegalArgumentException.class, () -> new CpCredentials("command-bearer", " "));
    }
}
