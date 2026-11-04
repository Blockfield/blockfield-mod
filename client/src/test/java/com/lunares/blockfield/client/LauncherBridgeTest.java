package com.lunares.blockfield.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class LauncherBridgeTest {
    @Test
    void invitationsAcceptLobbyAndRoomButRejectExpiredAndForeignTargets() {
        JsonObject request = new JsonObject();
        request.addProperty("createdAt", 1_000_000L);
        for (String target : new String[] {"lobby", "12345678-1234-1234-1234-123456789abc"}) {
            request.addProperty("room", target);
            assertTrue(LauncherBridge.validRequest(request, 1_000_000L));
            assertTrue(LauncherBridge.validRequest(request, 1_600_000L));
            assertFalse(LauncherBridge.validRequest(request, 1_600_001L));
            assertFalse(LauncherBridge.validRequest(request, 999_999L));
        }
        for (String target :
                new String[] {"1", "lobby:evil", "../lobby", "lobby\n", "https://evil"}) {
            request.addProperty("room", target);
            assertFalse(LauncherBridge.validRequest(request, 1_000_000L));
        }
    }
}
