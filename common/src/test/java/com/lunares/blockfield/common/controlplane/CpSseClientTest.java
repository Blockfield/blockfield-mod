package com.lunares.blockfield.common.controlplane;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Transport round trip against a local stub: snapshot, SSE frame, ordered acks. */
class CpSseClientTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
    private static final String EPOCH = "123e4567-e89b-12d3-a456-426614174000";
    private static final String GENERATION = "123e4567-e89b-12d3-a456-426614174001";

    @TempDir Path directory;

    private HttpServer server;
    private final List<String> ackBodies = new CopyOnWriteArrayList<>();
    private final List<String> seenAuth = new CopyOnWriteArrayList<>();
    private final List<String> seenResume = new CopyOnWriteArrayList<>();
    private final AtomicBoolean stop = new AtomicBoolean();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v2/agents/game-1/snapshot",
                exchange -> {
                    seenAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    respond(exchange, 200, snapshot());
                });
        server.createContext(
                "/v2/agents/game-1/events",
                exchange -> {
                    seenAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    seenResume.add(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
                    respond(
                            exchange,
                            200,
                            "id: " + EPOCH + ":42\nevent: control\ndata: " + frame() + "\n\n");
                });
        server.createContext(
                "/v2/agents/game-1/acks",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    ackBodies.add(body);
                    if (ackBodies.size() >= 4) stop.set(true);
                    respond(exchange, 200, "{\"state\":\"received\",\"sequence\":42}");
                });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void snapshotStreamAndAckChainRoundTrip() throws Exception {
        CpStore store = new CpStore(directory.resolve("cp"));
        List<JsonObject> applied = new ArrayList<>();
        CpConsumer consumer =
                new CpConsumer(
                        store,
                        (namespace, target, revision, value) -> {
                            applied.add(value);
                            return CpApplier.Outcome.ok();
                        },
                        CLOCK);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        CpSseClient client =
                new CpSseClient(
                        baseUrl, "game-1", "test-bearer", store, consumer, new Quiet(), CLOCK);

        var runner = Executors.newSingleThreadExecutor();
        try {
            runner.submit(() -> client.run(stop)).get(30, TimeUnit.SECONDS);
        } finally {
            runner.shutdownNow();
        }

        assertTrue(stop.get());
        assertEquals(4, ackBodies.size());
        assertEquals(
                List.of("received", "validated", "applied", "effective"),
                ackBodies.stream()
                        .map(
                                body ->
                                        com.google.gson.JsonParser.parseString(body)
                                                .getAsJsonObject()
                                                .get("state")
                                                .getAsString())
                        .toList());
        assertTrue(seenAuth.stream().allMatch("Bearer test-bearer"::equals));
        assertTrue(seenResume.contains(EPOCH + ":41"));
        assertEquals(1, applied.size());
        assertEquals(42, store.loadLkg().revision());
        assertEquals(42, store.loadCursor().sequence());
        assertTrue(ackBodies.stream().allMatch(body -> body.contains(EPOCH)));
    }

    @Test
    void directPostSurfacesConflictAsResync() throws Exception {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v2/agents/other/acks", exchange -> respond(exchange, 409, "{}"));
        CpStore store = new CpStore(directory.resolve("cp2"));
        CpConsumer consumer =
                new CpConsumer(
                        store,
                        (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                        CLOCK);
        CpSseClient client =
                new CpSseClient(baseUrl, "other", "bearer", store, consumer, new Quiet(), CLOCK);

        CpAck ack =
                new CpAck(
                        java.util.UUID.fromString(EPOCH),
                        1,
                        GENERATION,
                        2L,
                        "b".repeat(64),
                        CpAck.State.RECEIVED,
                        OffsetDateTime.now(CLOCK),
                        CpAck.Result.OK);
        assertThrows(CpResyncException.class, () -> client.postAck(ack));
    }

    private String snapshot() {
        JsonObject entry = new JsonObject();
        entry.addProperty("namespace", "gameplay");
        entry.addProperty("target", "*");
        entry.addProperty("revision", 41);
        entry.add("value", new JsonObject());
        entry.addProperty("value_hash", "snapshot");
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        root.addProperty("audience", "game-1");
        root.addProperty("epoch", EPOCH);
        root.addProperty("generation", GENERATION);
        root.addProperty("cursor", 41);
        JsonArray settings = new JsonArray();
        settings.add(entry);
        root.add("settings", settings);
        root.addProperty("hash", "snapshot");
        return root.toString();
    }

    private String frame() {
        JsonObject payload = new JsonObject();
        payload.addProperty("namespace", "gameplay");
        payload.addProperty("target", "*");
        payload.add("value", new JsonObject());
        JsonObject frame = new JsonObject();
        frame.addProperty("schema", 2);
        frame.addProperty("epoch", EPOCH);
        frame.addProperty("sequence", 42);
        frame.addProperty("operationId", "123e4567-e89b-12d3-a456-426614174042");
        frame.addProperty("audience", "game-1");
        frame.addProperty("generation", GENERATION);
        frame.addProperty("revision", 42);
        frame.addProperty("payloadHash", "a".repeat(64));
        frame.addProperty("authorizationEpoch", 3);
        frame.addProperty("expiresAt", OffsetDateTime.now(CLOCK).plusMinutes(5).toString());
        frame.addProperty("executable", true);
        frame.add("reason", null);
        frame.add("payload", payload);
        return frame.toString();
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            exchange.close();
        }
    }

    record Quiet() implements CpSseClient.Reporter {
        @Override
        public void info(String message) {}

        @Override
        public void warn(String message, Throwable error) {}
    }
}
