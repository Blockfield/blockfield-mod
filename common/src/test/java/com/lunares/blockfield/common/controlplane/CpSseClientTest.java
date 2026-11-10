package com.lunares.blockfield.common.controlplane;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Transport round trip against a local stub: snapshot, SSE frame, ordered acks. */
class CpSseClientTest {
    private static final Clock CLOCK = Clock.systemUTC();
    private static final String EPOCH = "123e4567-e89b-12d3-a456-426614174000";
    private static final String GENERATION = "123e4567-e89b-12d3-a456-426614174001";

    @TempDir Path directory;

    private HttpServer server;
    private final List<String> ackBodies = new CopyOnWriteArrayList<>();
    private final List<String> seenAuth = new CopyOnWriteArrayList<>();
    private final List<String> seenResume = new CopyOnWriteArrayList<>();
    private final List<String> seenUpgrade = new CopyOnWriteArrayList<>();
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicBoolean ackSeenWhileOpen = new AtomicBoolean();
    private final AtomicInteger snapshotHits = new AtomicInteger();
    private final AtomicInteger eventsHits = new AtomicInteger();
    private final AtomicInteger stopAfterAcks = new AtomicInteger(4);
    private final AtomicInteger stopAfterSnapshots = new AtomicInteger(Integer.MAX_VALUE);
    private volatile int snapshotStatus = 200;
    private volatile int eventsStatus = 200;
    private volatile java.util.function.Supplier<String> snapshotBody = this::snapshot;
    private volatile java.util.function.Function<Long, String> eventsBody =
            cursor -> "id: " + EPOCH + ":42\nevent: control\ndata: " + frame() + "\n\n";
    private volatile java.util.function.Function<String, Integer> ackStatus = body -> 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v2/agents/game-1/snapshot",
                exchange -> {
                    seenAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    seenUpgrade.add(exchange.getRequestHeaders().getFirst("Upgrade"));
                    snapshotHits.incrementAndGet();
                    if (snapshotHits.get() >= stopAfterSnapshots.get()) stop.set(true);
                    respond(exchange, snapshotStatus, snapshotBody.get());
                });
        server.createContext(
                "/v2/agents/game-1/events",
                exchange -> {
                    seenAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    seenUpgrade.add(exchange.getRequestHeaders().getFirst("Upgrade"));
                    seenResume.add(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
                    eventsHits.incrementAndGet();
                    respond(exchange, eventsStatus, eventsBody.apply(queryCursor(exchange)));
                });
        server.createContext(
                "/v2/agents/game-1/acks",
                exchange -> {
                    seenUpgrade.add(exchange.getRequestHeaders().getFirst("Upgrade"));
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    ackBodies.add(body);
                    if (ackBodies.size() >= stopAfterAcks.get()) stop.set(true);
                    respond(exchange, ackStatus.apply(body), "{\"state\":\"received\"}");
                });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private static long queryCursor(com.sun.net.httpserver.HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return 0;
        for (String part : query.split("&")) {
            if (part.startsWith("cursor=")) {
                try {
                    return Long.parseLong(part.substring("cursor=".length()));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
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

        runToStop(client);

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
        assertEquals(2, applied.size());
        assertEquals(42, store.loadLkg("gameplay", "*").revision());
        assertEquals(42, store.loadCursor().sequence());
        assertTrue(ackBodies.stream().allMatch(body -> body.contains(EPOCH)));
    }

    @Test
    void noH2cUpgradeHeaderOnPlainHttp() throws Exception {
        CpStore store = new CpStore(directory.resolve("cp-http11"));
        CpConsumer consumer =
                new CpConsumer(
                        store,
                        (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                        CLOCK);
        CpSseClient client = newClient(store, consumer);

        client.postAckJson("{}");

        assertFalse(seenUpgrade.isEmpty(), "ack request never reached the stub");
        assertTrue(
                seenUpgrade.stream().allMatch(header -> header == null),
                "h2c Upgrade header on plain HTTP: " + seenUpgrade);
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

    @Test
    void eventsConflictResnapshotsInsteadOfDying() throws Exception {
        eventsStatus = 409;
        stopAfterSnapshots.set(2);
        CpStore store = new CpStore(directory.resolve("cp"));
        CpConsumer consumer =
                new CpConsumer(
                        store,
                        (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                        CLOCK);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        CpSseClient client =
                new CpSseClient(baseUrl, "game-1", "bearer", store, consumer, new Quiet(), CLOCK);

        runToStop(client);

        assertTrue(snapshotHits.get() >= 2);
        assertTrue(eventsHits.get() >= 2);
    }

    @Test
    void restartResnapshotsAndApplies() throws Exception {
        eventsBody =
                cursor ->
                        cursor < 43
                                ? "id: "
                                        + EPOCH
                                        + ":"
                                        + (cursor + 1)
                                        + "\nevent: control\ndata: "
                                        + frameAt(EPOCH, cursor + 1)
                                        + "\n\n"
                                : "";
        List<Long> appliedRevisions = new CopyOnWriteArrayList<>();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        stopAfterAcks.set(4);
        CpStore store = new CpStore(directory.resolve("cp"));
        CpSseClient first =
                new CpSseClient(
                        baseUrl,
                        "game-1",
                        "bearer",
                        store,
                        new CpConsumer(store, recordingApplier(appliedRevisions), CLOCK),
                        new Quiet(),
                        CLOCK);
        runToStop(first);
        assertEquals(42, store.loadCursor().sequence());

        stop.set(false);
        stopAfterAcks.set(8);
        CpStore restarted = new CpStore(directory.resolve("cp"));
        CpSseClient second =
                new CpSseClient(
                        baseUrl,
                        "game-1",
                        "bearer",
                        restarted,
                        new CpConsumer(restarted, recordingApplier(appliedRevisions), CLOCK),
                        new Quiet(),
                        CLOCK);
        runToStop(second);

        assertEquals(List.of(41L, 42L, 43L), appliedRevisions);
        assertEquals(43, restarted.loadCursor().sequence());
    }

    @Test
    void framesAreDispatchedBeforeStreamCloses() throws Exception {
        HttpServer holding = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean served = new AtomicBoolean();
        holding.createContext(
                "/v2/agents/game-1/snapshot", exchange -> respond(exchange, 200, snapshot()));
        holding.createContext(
                "/v2/agents/game-1/events",
                exchange -> {
                    if (!served.getAndSet(true)) {
                        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        OutputStream out = exchange.getResponseBody();
                        out.write(
                                ("id: " + EPOCH + ":42\nevent: control\ndata: " + frame() + "\n\n")
                                        .getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        long deadline = System.currentTimeMillis() + 15000;
                        while (ackBodies.size() < 4 && System.currentTimeMillis() < deadline) {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        ackSeenWhileOpen.set(ackBodies.size() >= 4);
                        out.close();
                    } else {
                        respond(exchange, 200, "");
                    }
                    exchange.close();
                });
        holding.createContext(
                "/v2/agents/game-1/acks",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    ackBodies.add("ack");
                    respond(exchange, 200, "{\"state\":\"received\"}");
                });
        holding.setExecutor(Executors.newCachedThreadPool());
        holding.start();
        try {
            CpStore store = new CpStore(directory.resolve("cp-hold"));
            CpConsumer consumer =
                    new CpConsumer(
                            store,
                            (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                            CLOCK);
            String baseUrl = "http://127.0.0.1:" + holding.getAddress().getPort();
            CpSseClient client =
                    new CpSseClient(
                            baseUrl, "game-1", "bearer", store, consumer, new Quiet(), CLOCK);
            ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                Future<?> done = runner.submit(() -> client.run(stop));
                long deadline = System.currentTimeMillis() + 20000;
                while (!ackSeenWhileOpen.get() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                stop.set(true);
                done.get(15, TimeUnit.SECONDS);
            } finally {
                runner.shutdownNow();
            }
        } finally {
            holding.stop(0);
        }

        assertTrue(ackSeenWhileOpen.get(), "frame was not dispatched until the stream closed");
    }

    @Test
    void poisonFrameDoesNotKillLoop() throws Exception {
        eventsBody =
                cursor ->
                        "id: "
                                + EPOCH
                                + ":42\nevent: control\ndata: "
                                + frame().replace("\"schema\":2", "\"schema\":3")
                                + "\n\n";
        CpStore store = new CpStore(directory.resolve("cp-poison"));
        CpConsumer consumer =
                new CpConsumer(
                        store,
                        (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                        CLOCK);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        CpSseClient client =
                new CpSseClient(baseUrl, "game-1", "bearer", store, consumer, new Quiet(), CLOCK);

        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            Future<?> done = runner.submit(() -> client.run(stop));
            Thread.sleep(3500);
            assertFalse(done.isDone(), "poison frame killed the sync loop");
            stop.set(true);
            done.get(15, TimeUnit.SECONDS);
        } finally {
            runner.shutdownNow();
        }
        assertEquals(41, store.loadCursor().sequence());
    }

    @Test
    void resyncLoopBacksOff() {
        eventsStatus = 409;
        CpStore store = new CpStore(directory.resolve("cp-backoff"));
        CpConsumer consumer =
                new CpConsumer(
                        store,
                        (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                        CLOCK);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        List<Long> requestedBackoffs = new CopyOnWriteArrayList<>();
        AtomicBoolean stop = new AtomicBoolean();
        CpSseClient client =
                new CpSseClient(
                        baseUrl,
                        "game-1",
                        "bearer",
                        store,
                        consumer,
                        new Quiet(),
                        CLOCK,
                        CpSseClient.IDLE_TIMEOUT,
                        (millis, stopFlag) -> {
                            requestedBackoffs.add(millis);
                            if (requestedBackoffs.size() >= 3) {
                                stopFlag.set(true);
                                return false;
                            }
                            return true;
                        });

        client.run(stop);

        assertEquals(3, snapshotHits.get());
        assertEquals(List.of(1000L, 2000L, 4000L), requestedBackoffs);
    }

    @Test
    void keepAliveCommentsMarkStreamActive() throws Exception {
        eventsBody = cursor -> ":keep-alive\n\n:keep-alive\n\n";
        CpStore store = new CpStore(directory.resolve("cp-keepalive"));
        CpConsumer consumer =
                new CpConsumer(
                        store,
                        (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                        CLOCK);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        CpSseClient client =
                new CpSseClient(baseUrl, "game-1", "bearer", store, consumer, new Quiet(), CLOCK);

        CpSseClient.StreamOutcome outcome = client.stream(new AtomicBoolean());

        assertEquals(0, outcome.frames());
        assertTrue(outcome.active());
    }

    @Test
    void silentCloseIsInactive() throws Exception {
        eventsBody = cursor -> "";
        CpStore store = new CpStore(directory.resolve("cp-silent"));
        CpConsumer consumer =
                new CpConsumer(
                        store,
                        (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                        CLOCK);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        CpSseClient client =
                new CpSseClient(baseUrl, "game-1", "bearer", store, consumer, new Quiet(), CLOCK);

        CpSseClient.StreamOutcome outcome = client.stream(new AtomicBoolean());

        assertEquals(0, outcome.frames());
        assertFalse(outcome.active());
    }

    @Test
    void ackConflictDropsAndResyncs() throws Exception {
        AtomicInteger ackCalls = new AtomicInteger();
        ackStatus = body -> ackCalls.incrementAndGet() <= 2 ? 409 : 200;
        eventsBody = cursor -> cursor < 42 ? sse(frame()) : "";
        stopAfterSnapshots.set(8);
        CpStore store = new CpStore(directory.resolve("cp-conflict"));
        CpConsumer consumer =
                new CpConsumer(
                        store,
                        (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                        CLOCK);
        CpSseClient client = newClient(store, consumer);

        runToStop(client);

        assertEquals(42, store.loadCursor().sequence(), "client wedged on 409 ack loop");
        assertTrue(
                ackBodies.stream().anyMatch(body -> body.contains("\"state\":\"effective\"")),
                "EFFECTIVE never delivered after transient 409");
    }

    @Test
    void failedAckRepostsDecidedChainWithoutReapply() throws Exception {
        AtomicBoolean firstAck = new AtomicBoolean(true);
        ackStatus = body -> firstAck.getAndSet(false) ? 500 : 200;
        eventsBody = cursor -> cursor < 42 ? sse(frame()) : "";
        List<Long> appliedRevisions = new CopyOnWriteArrayList<>();
        stopAfterAcks.set(5);
        CpStore store = new CpStore(directory.resolve("cp-retry"));
        CpConsumer consumer = new CpConsumer(store, recordingApplier(appliedRevisions), CLOCK);
        CpSseClient client = newClient(store, consumer);

        runToStop(client);

        assertEquals(List.of(41L, 42L), appliedRevisions, "decided frame re-applied on ack retry");
        assertEquals(42, store.loadCursor().sequence());
        assertTrue(ackBodies.stream().anyMatch(body -> body.contains("\"state\":\"effective\"")));
    }

    @Test
    void stopInterruptsBlockedStream() throws Exception {
        HttpServer holding = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        holding.createContext(
                "/v2/agents/game-1/snapshot", exchange -> respond(exchange, 200, snapshot()));
        holding.createContext(
                "/v2/agents/game-1/events",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        Thread.sleep(20000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        holding.setExecutor(Executors.newCachedThreadPool());
        holding.start();
        AtomicBoolean holdStop = new AtomicBoolean();
        try {
            CpStore store = new CpStore(directory.resolve("cp-stop"));
            CpConsumer consumer =
                    new CpConsumer(
                            store,
                            (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                            CLOCK);
            CpSseClient client =
                    new CpSseClient(
                            "http://127.0.0.1:" + holding.getAddress().getPort(),
                            "game-1",
                            "bearer",
                            store,
                            consumer,
                            new Quiet(),
                            CLOCK);
            ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                Future<?> running = runner.submit(() -> client.stream(holdStop));
                Thread.sleep(2000);
                assertFalse(running.isDone(), "stream returned before stop");
                holdStop.set(true);
                try {
                    running.get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    fail("blocked SSE read ignores stop()");
                }
            } finally {
                runner.shutdownNow();
            }
        } finally {
            holding.stop(0);
        }
    }

    @Test
    void idleTimeoutReconnects() throws Exception {
        HttpServer holding = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        List<String> applied = new CopyOnWriteArrayList<>();
        holding.createContext(
                "/v2/agents/game-1/snapshot", exchange -> respond(exchange, 200, snapshot()));
        holding.createContext(
                "/v2/agents/game-1/events",
                exchange -> {
                    if (hits.getAndIncrement() == 0) {
                        // Keep-alive, then a half-open stall: no close, no more bytes.
                        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        OutputStream out = exchange.getResponseBody();
                        out.write(":keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        try {
                            Thread.sleep(30000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            exchange.close();
                        }
                    } else {
                        respond(exchange, 200, sse(frame()));
                    }
                });
        holding.createContext(
                "/v2/agents/game-1/acks",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    ackBodies.add("ack");
                    if (ackBodies.size() >= 4) stop.set(true);
                    respond(exchange, 200, "{\"state\":\"received\"}");
                });
        holding.setExecutor(Executors.newCachedThreadPool());
        holding.start();
        AtomicBoolean idleStop = new AtomicBoolean();
        try {
            CpStore store = new CpStore(directory.resolve("cp-idle"));
            CpConsumer consumer =
                    new CpConsumer(
                            store,
                            (namespace, target, revision, value) -> {
                                applied.add(value.toString());
                                return CpApplier.Outcome.ok();
                            },
                            CLOCK);
            CpSseClient client =
                    new CpSseClient(
                            "http://127.0.0.1:" + holding.getAddress().getPort(),
                            "game-1",
                            "bearer",
                            store,
                            consumer,
                            new Quiet(),
                            CLOCK,
                            Duration.ofMillis(500));
            ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                Future<?> done = runner.submit(() -> client.run(idleStop));
                long deadline = System.currentTimeMillis() + 25000;
                while (!stop.get() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                idleStop.set(true);
                done.get(15, TimeUnit.SECONDS);
            } finally {
                runner.shutdownNow();
            }
        } finally {
            holding.stop(0);
        }

        assertTrue(stop.get(), "stalled stream never reconnected to the live one");
        assertTrue(hits.get() >= 2, "no reconnect after idle timeout");
        assertEquals(2, applied.size(), "snapshot revision plus replayed frame");
    }

    private void runToStop(CpSseClient client) throws Exception {
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            runner.submit(() -> client.run(stop)).get(60, TimeUnit.SECONDS);
        } finally {
            runner.shutdownNow();
        }
    }

    private CpSseClient newClient(CpStore store, CpConsumer consumer) {
        return new CpSseClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "game-1",
                "bearer",
                store,
                consumer,
                new Quiet(),
                CLOCK);
    }

    private static String sse(String data) {
        return "event: control\ndata: " + data + "\n\n";
    }

    private static CpApplier recordingApplier(List<Long> appliedRevisions) {
        return (namespace, target, revision, value) -> {
            appliedRevisions.add(revision);
            return CpApplier.Outcome.ok();
        };
    }

    private String snapshot() {
        return snapshotAt(EPOCH, 41, 41);
    }

    private String snapshotAt(String epoch, long cursor, long revision) {
        JsonObject entry = new JsonObject();
        entry.addProperty("namespace", "gameplay");
        entry.addProperty("target", "*");
        entry.addProperty("revision", revision);
        entry.add("value", new JsonObject());
        entry.addProperty("value_hash", "snapshot");
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        root.addProperty("audience", "game-1");
        root.addProperty("epoch", epoch);
        root.addProperty("generation", GENERATION);
        root.addProperty("cursor", cursor);
        JsonArray settings = new JsonArray();
        settings.add(entry);
        root.add("settings", settings);
        root.addProperty("hash", "snapshot");
        return root.toString();
    }

    private String frame() {
        return frameAt(EPOCH, 42);
    }

    private String frameAt(String epoch, long sequence) {
        JsonObject payload = new JsonObject();
        payload.addProperty("namespace", "gameplay");
        payload.addProperty("target", "*");
        payload.add("value", new JsonObject());
        JsonObject frame = new JsonObject();
        frame.addProperty("schema", 2);
        frame.addProperty("epoch", epoch);
        frame.addProperty("sequence", sequence);
        frame.addProperty(
                "operationId",
                "123e4567-e89b-12d3-a456-42661417" + String.format("%04d", sequence));
        frame.addProperty("audience", "game-1");
        frame.addProperty("generation", GENERATION);
        frame.addProperty("revision", sequence);
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
