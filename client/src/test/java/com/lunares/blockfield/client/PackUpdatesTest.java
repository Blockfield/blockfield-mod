package com.lunares.blockfield.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunares.blockfield.client.PackUpdates.UpdateKind;
import org.junit.jupiter.api.Test;

class PackUpdatesTest {
    private static final String LOCAL = "a".repeat(64);
    private static final String REMOTE = "b".repeat(64);
    private static final String CONTRACT = "c".repeat(64);

    @Test
    void sameDigestNeedsNothing() {
        assertEquals(UpdateKind.NONE, PackUpdates.resolve(LOCAL, CONTRACT, LOCAL, CONTRACT));
    }

    @Test
    void malformedRemoteDigestIsIgnored() {
        assertEquals(UpdateKind.NONE, PackUpdates.resolve(LOCAL, CONTRACT, "2.78.1", CONTRACT));
        assertEquals(UpdateKind.NONE, PackUpdates.resolve(LOCAL, CONTRACT, "", CONTRACT));
        assertEquals(UpdateKind.NONE, PackUpdates.resolve(LOCAL, CONTRACT, null, CONTRACT));
    }

    @Test
    void sameContractIsAnOptionalUpdate() {
        // 2.78.1-style release: newer client files, unchanged compatibility contract.
        assertEquals(UpdateKind.OPTIONAL, PackUpdates.resolve(LOCAL, CONTRACT, REMOTE, CONTRACT));
    }

    @Test
    void changedContractIsMandatory() {
        assertEquals(
                UpdateKind.MANDATORY, PackUpdates.resolve(LOCAL, CONTRACT, REMOTE, "d".repeat(64)));
        // Only an exact, well-formed contract match is optional.
        assertEquals(
                UpdateKind.MANDATORY,
                PackUpdates.resolve(LOCAL, CONTRACT, REMOTE, CONTRACT.toUpperCase()));
        assertEquals(
                UpdateKind.MANDATORY, PackUpdates.resolve(LOCAL, CONTRACT, REMOTE, " " + CONTRACT));
    }

    @Test
    void missingContractIsFailSafeMandatory() {
        assertEquals(UpdateKind.MANDATORY, PackUpdates.resolve(LOCAL, CONTRACT, REMOTE, ""));
        assertEquals(UpdateKind.MANDATORY, PackUpdates.resolve(LOCAL, CONTRACT, REMOTE, null));
        assertEquals(UpdateKind.MANDATORY, PackUpdates.resolve(LOCAL, "", REMOTE, CONTRACT));
    }

    @Test
    void malformedCompatibilityNeverSuppressesTheMandatoryNotification() {
        assertEquals("", PackUpdates.remoteContract(new JsonObject()));
        for (String raw :
                new String[] {
                    "{\"compatibility\":null}",
                    "{\"compatibility\":{}}",
                    "{\"compatibility\":[]}",
                    "{\"compatibility\":42}",
                    "{\"compatibility\":true}",
                    "{\"compatibility\":\"\"}",
                    "{\"compatibility\":\"short\"}",
                    "{\"compatibility\":\"" + CONTRACT.toUpperCase() + "\"}",
                    "{\"compatibility\":\" " + CONTRACT + "\"}",
                    "{\"compatibility\":\"" + CONTRACT + "\\n\"}"
                }) {
            JsonObject build = JsonParser.parseString(raw).getAsJsonObject();
            String contract = PackUpdates.remoteContract(build);
            assertEquals("", contract, raw);
            assertEquals(
                    UpdateKind.MANDATORY,
                    PackUpdates.resolve(LOCAL, CONTRACT, REMOTE, contract),
                    raw);
        }
    }

    @Test
    void wellFormedEqualContractParsesThrough() {
        JsonObject build =
                JsonParser.parseString("{\"compatibility\":\"" + CONTRACT + "\"}")
                        .getAsJsonObject();
        assertEquals(CONTRACT, PackUpdates.remoteContract(build));
    }

    @Test
    void shortManifestNeverThrowsAndResolvesSafe() {
        // Fail-before: build.get("client") threw NPE on a short manifest and the
        // exceptionally swallowed it, so neither banner could render.
        assertEquals(UpdateKind.NONE, PackUpdates.resolveBuild(new JsonObject(), LOCAL, CONTRACT));
        for (String raw :
                new String[] {
                    "{\"client\":null}",
                    "{\"client\":{}}",
                    "{\"client\":42}",
                    "{\"client\":\"short\"}",
                    "{\"client\":\"" + REMOTE.toUpperCase() + "\"}"
                }) {
            assertEquals(
                    UpdateKind.NONE,
                    PackUpdates.resolveBuild(
                            JsonParser.parseString(raw).getAsJsonObject(), LOCAL, CONTRACT),
                    raw);
        }
    }

    @Test
    void buildResolvesLikeResolve() {
        JsonObject optional =
                JsonParser.parseString(
                                "{\"client\":\""
                                        + REMOTE
                                        + "\",\"compatibility\":\""
                                        + CONTRACT
                                        + "\",\"client_version\":\"2.78.1\"}")
                        .getAsJsonObject();
        assertEquals(UpdateKind.OPTIONAL, PackUpdates.resolveBuild(optional, LOCAL, CONTRACT));
        assertEquals("2.78.1", PackUpdates.label(optional, REMOTE));
        JsonObject mandatory =
                JsonParser.parseString(
                                "{\"client\":\""
                                        + REMOTE
                                        + "\",\"compatibility\":\""
                                        + "d".repeat(64)
                                        + "\"}")
                        .getAsJsonObject();
        assertEquals(UpdateKind.MANDATORY, PackUpdates.resolveBuild(mandatory, LOCAL, CONTRACT));
        // No version label: the banner still renders with the digest, never lost.
        assertEquals(REMOTE, PackUpdates.label(mandatory, REMOTE));
        JsonObject same =
                JsonParser.parseString("{\"client\":\"" + LOCAL + "\"}").getAsJsonObject();
        assertEquals(UpdateKind.NONE, PackUpdates.resolveBuild(same, LOCAL, CONTRACT));
    }
}
