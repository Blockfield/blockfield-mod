package com.lunares.blockfield.common.controlplane;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    void poisonFrameLogsOneLineWithoutStack() {
        eventsBody =
                cursor ->
                        "id: "
                                + EPOCH
                                + ":42\nevent: control\ndata: "
                                + frame().replace("\"schema\":2", "\"schema\":3")
                                + "\n\n";
        CpStore store = new CpStore(directory.resolve("cp-poison-once"));
        CpConsumer consumer =
                new CpConsumer(
                        store,
                        (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                        CLOCK);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        List<String> infos = new CopyOnWriteArrayList<>();
        List<String> warns = new CopyOnWriteArrayList<>();
        List<String> warnLines = new CopyOnWriteArrayList<>();
        CpSseClient.Reporter recording =
                new CpSseClient.Reporter() {
                    @Override
                    public void info(String message) {
                        infos.add(message);
                    }

                    @Override
                    public void warn(String message, Throwable error) {
                        warns.add(
                                message
                                        + "|"
                                        + (error == null
                                                ? "null"
                                                : error.getClass().getSimpleName()));
                    }

                    @Override
                    public void warn(String message) {
                        warnLines.add(message);
                    }
                };
        Clock fixed =
                Clock.fixed(
                        java.time.Instant.parse("2026-11-06T12:00:00Z"), java.time.ZoneOffset.UTC);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger sleeps = new AtomicInteger();
        CpSseClient client =
                new CpSseClient(
                        baseUrl,
                        "game-1",
                        "bearer",
                        store,
                        consumer,
                        recording,
                        fixed,
                        CpSseClient.IDLE_TIMEOUT,
                        (millis, stopFlag) -> {
                            if (sleeps.incrementAndGet() >= 3) {
                                stopFlag.set(true);
                                return false;
                            }
                            return true;
                        });

        client.run(stop);

        assertEquals(3, sleeps.get(), "poison frame did not retry with backoff");
        assertEquals(1, warnLines.size(), "rejected frame must log one line, not one per retry");
        assertTrue(warns.isEmpty(), "rejected frame must not log a stack trace per retry");
        assertTrue(infos.isEmpty(), "rejected frame stays at WARN so a stuck cursor is visible");
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
    void restartResumesPendingChainWithoutRedelivery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ackStatus = body -> calls.incrementAndGet() == 2 ? 500 : 200;
        stopAfterAcks.set(5);
        CpStore store = new CpStore(directory.resolve("cp-restart"));
        List<Long> applied = new CopyOnWriteArrayList<>();
        CpConsumer first = new CpConsumer(store, recordingApplier(applied), CLOCK);
        first.onSnapshot(snapshot());
        eventsBody = cursor -> sse(frame()) + sse(frame());
        CpSseClient firstClient = newClient(store, first);
        assertThrows(IOException.class, () -> firstClient.stream(new AtomicBoolean()));
        assertEquals(1, store.loadPendingAcks().sent());

        eventsBody = cursor -> "";
        CpStore reopened = new CpStore(directory.resolve("cp-restart"));
        CpConsumer second = new CpConsumer(reopened, recordingApplier(applied), CLOCK);
        runToStop(newClient(reopened, second));

        assertEquals(List.of(41L, 42L), applied);
        assertEquals(42, reopened.loadCursor().sequence());
        assertNull(reopened.loadPendingAcks());
        eventsBody = cursor -> sse(frame());
        newClient(reopened, second).stream(new AtomicBoolean());
        assertEquals(5, ackBodies.size(), "redelivery after retry must not repost or reapply");
        assertEquals(List.of(41L, 42L), applied);
        assertEquals(
                List.of("received", "validated", "validated", "applied", "effective"),
                ackBodies.stream()
                        .map(
                                body ->
                                        JsonParser.parseString(body)
                                                .getAsJsonObject()
                                                .get("state")
                                                .getAsString())
                        .toList());
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

    @Test
    void workshopGameStopHookRunsStrictlyAfterFinalAckAccepted() throws Exception {
        HttpServer wsServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicBoolean effectiveAckObservedBeforeHalt = new AtomicBoolean();
        CountDownLatch halted = new CountDownLatch(1);
        wsServer.createContext(
                "/v2/agents/ws-game/snapshot",
                exchange -> respond(exchange, 200, snapshotFor("ws-game", EPOCH, 41, 41)));
        wsServer.createContext(
                "/v2/agents/ws-game/events",
                exchange -> {
                    if (queryCursor(exchange) < 42) {
                        respond(
                                exchange,
                                200,
                                "id: "
                                        + EPOCH
                                        + ":42\nevent: control\ndata: "
                                        + workshopStopFrame(EPOCH, 42)
                                        + "\n\n");
                    } else {
                        respond(exchange, 200, "");
                    }
                });
        wsServer.createContext(
                "/v2/agents/ws-game/acks",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    String state =
                            com.google.gson.JsonParser.parseString(body)
                                    .getAsJsonObject()
                                    .get("state")
                                    .getAsString();
                    events.add("ack:" + state);
                    if ("effective".equals(state)) {
                        // Before CP accepts the final ack, the post-ack action must not have run.
                        effectiveAckObservedBeforeHalt.set(!events.contains("halt"));
                    }
                    respond(exchange, 200, "{\"state\":\"" + state + "\"}");
                });
        wsServer.setExecutor(Executors.newCachedThreadPool());
        wsServer.start();
        AtomicBoolean clientStop = new AtomicBoolean();
        try {
            CpStore store = new CpStore(directory.resolve("cp-ws-stop"));
            CpActionHandler handler =
                    new CpActionHandler() {
                        @Override
                        public CpApplier.Outcome handleAction(
                                String kind, String target, String playerId, String reason) {
                            return CpApplier.Outcome.rejected(
                                    CpAck.Result.INVALID_SCHEMA, "unexpected");
                        }

                        @Override
                        public CpApplier.Outcome handleWorkshopGame(
                                String target,
                                String gameOp,
                                String job,
                                CompletableFuture<CpAck> acked) {
                            events.add("handler:" + gameOp);
                            acked.whenComplete(
                                    (ack, error) -> {
                                        if (error == null && ack.state == CpAck.State.EFFECTIVE) {
                                            events.add("cursor:" + store.loadCursor().sequence());
                                            events.add("halt");
                                            halted.countDown();
                                            clientStop.set(true);
                                        }
                                    });
                            return CpApplier.Outcome.ok();
                        }
                    };
            CpConsumer consumer =
                    new CpConsumer(
                            store,
                            (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                            handler,
                            CLOCK,
                            "ws-game");
            CpSseClient client =
                    new CpSseClient(
                            "http://127.0.0.1:" + wsServer.getAddress().getPort(),
                            "ws-game",
                            "bearer",
                            store,
                            consumer,
                            new Quiet(),
                            CLOCK);
            ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                Future<?> done = runner.submit(() -> client.run(clientStop));
                assertTrue(halted.await(15, TimeUnit.SECONDS), "post-ack halt did not run");
                done.get(15, TimeUnit.SECONDS);
            } finally {
                runner.shutdownNow();
            }
        } finally {
            wsServer.stop(0);
        }

        assertTrue(
                effectiveAckObservedBeforeHalt.get(),
                "halt ran before the effective ack was accepted");
        assertEquals(
                List.of(
                        "handler:stop",
                        "ack:received",
                        "ack:validated",
                        "ack:applied",
                        "ack:effective",
                        "cursor:42",
                        "halt"),
                events);
    }

    @Test
    void postAckJsonClassifiesRejections() {
        CpStore store = new CpStore(directory.resolve("cp-ack-class"));
        CpConsumer consumer =
                new CpConsumer(
                        store,
                        (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                        CLOCK);
        CpSseClient client = newClient(store, consumer);

        // Validation-class statuses are terminal: no retry will fix the receipt.
        ackStatus = body -> 400;
        CpAckRejectedException rejected =
                assertThrows(CpAckRejectedException.class, () -> client.postAckJson("{}"));
        assertEquals(400, rejected.statusCode());

        ackStatus = body -> 404;
        assertEquals(
                404,
                assertThrows(CpAckRejectedException.class, () -> client.postAckJson("{}"))
                        .statusCode());

        ackStatus = body -> 422;
        assertEquals(
                422,
                assertThrows(CpAckRejectedException.class, () -> client.postAckJson("{}"))
                        .statusCode());

        // Any other 4xx (including transient auth failures) is retryable with the hook pending.
        ackStatus = body -> 401;
        assertThrows(IOException.class, () -> client.postAckJson("{}"));
        ackStatus = body -> 403;
        assertThrows(IOException.class, () -> client.postAckJson("{}"));
        ackStatus = body -> 405;
        assertThrows(IOException.class, () -> client.postAckJson("{}"));

        ackStatus = body -> 408;
        assertThrows(IOException.class, () -> client.postAckJson("{}"));
        ackStatus = body -> 429;
        assertThrows(IOException.class, () -> client.postAckJson("{}"));
        ackStatus = body -> 500;
        assertThrows(IOException.class, () -> client.postAckJson("{}"));
        ackStatus = body -> 409;
        assertThrows(CpResyncException.class, () -> client.postAckJson("{}"));
    }

    @Test
    void transientAckFailureStillDeliversPostAckHookOnce() throws Exception {
        HttpServer wsServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger ackCalls = new AtomicInteger();
        AtomicInteger eventCalls = new AtomicInteger();
        AtomicInteger hookSuccesses = new AtomicInteger();
        AtomicBoolean clientStop = new AtomicBoolean();
        CountDownLatch halted = new CountDownLatch(1);
        wsServer.createContext(
                "/v2/agents/ws-game/snapshot",
                exchange -> respond(exchange, 200, snapshotFor("ws-game", EPOCH, 41, 41)));
        wsServer.createContext(
                "/v2/agents/ws-game/events",
                exchange -> {
                    if (eventCalls.incrementAndGet() == 1) {
                        respond(
                                exchange,
                                200,
                                "id: "
                                        + EPOCH
                                        + ":42\nevent: control\ndata: "
                                        + workshopStopFrame(EPOCH, 42)
                                        + "\n\n");
                    } else {
                        respond(exchange, 200, "");
                    }
                });
        wsServer.createContext(
                "/v2/agents/ws-game/acks",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    if (ackCalls.incrementAndGet() == 2) {
                        // The old control plane will not redeliver after received.
                        respond(exchange, 500, "{}");
                        return;
                    }
                    events.add(
                            "ack:"
                                    + com.google.gson.JsonParser.parseString(body)
                                            .getAsJsonObject()
                                            .get("state")
                                            .getAsString());
                    respond(exchange, 200, "{\"state\":\"received\"}");
                });
        wsServer.setExecutor(Executors.newCachedThreadPool());
        wsServer.start();
        try {
            CpStore store = new CpStore(directory.resolve("cp-ws-retry"));
            CpActionHandler handler =
                    new CpActionHandler() {
                        @Override
                        public CpApplier.Outcome handleAction(
                                String kind, String target, String playerId, String reason) {
                            return CpApplier.Outcome.rejected(
                                    CpAck.Result.INVALID_SCHEMA, "unexpected");
                        }

                        @Override
                        public CpApplier.Outcome handleWorkshopGame(
                                String target,
                                String gameOp,
                                String job,
                                CompletableFuture<CpAck> acked) {
                            events.add("handler:" + gameOp);
                            acked.whenComplete(
                                    (ack, error) -> {
                                        if (error == null && ack.state == CpAck.State.EFFECTIVE) {
                                            hookSuccesses.incrementAndGet();
                                            events.add("cursor:" + store.loadCursor().sequence());
                                            events.add("halt");
                                            halted.countDown();
                                            clientStop.set(true);
                                        } else if (error != null) {
                                            events.add("hook-failed:" + error);
                                        }
                                    });
                            return CpApplier.Outcome.ok();
                        }
                    };
            CpConsumer consumer =
                    new CpConsumer(
                            store,
                            (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                            handler,
                            CLOCK,
                            "ws-game");
            CpSseClient client =
                    new CpSseClient(
                            "http://127.0.0.1:" + wsServer.getAddress().getPort(),
                            "ws-game",
                            "bearer",
                            store,
                            consumer,
                            new Quiet(),
                            CLOCK);
            ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                Future<?> done = runner.submit(() -> client.run(clientStop));
                assertTrue(halted.await(20, TimeUnit.SECONDS), "post-ack halt did not run");
                done.get(15, TimeUnit.SECONDS);
            } finally {
                runner.shutdownNow();
            }
        } finally {
            wsServer.stop(0);
        }

        assertEquals(1, hookSuccesses.get(), "post-ack hook must fire exactly once: " + events);
        assertEquals(1, events.stream().filter("handler:stop"::equals).count());
        assertTrue(events.stream().noneMatch(entry -> entry.startsWith("hook-failed:")));
        assertEquals(
                List.of(
                        "handler:stop",
                        "ack:received",
                        "ack:validated",
                        "ack:applied",
                        "ack:effective",
                        "cursor:42",
                        "halt"),
                events);
    }

    @Test
    void transient403Then200DeliversPostAckHookOnce() throws Exception {
        HttpServer wsServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger ackCalls = new AtomicInteger();
        AtomicInteger hookSuccesses = new AtomicInteger();
        AtomicBoolean clientStop = new AtomicBoolean();
        CountDownLatch halted = new CountDownLatch(1);
        wsServer.createContext(
                "/v2/agents/ws-game/snapshot",
                exchange -> respond(exchange, 200, snapshotFor("ws-game", EPOCH, 41, 41)));
        wsServer.createContext(
                "/v2/agents/ws-game/events",
                exchange -> {
                    if (queryCursor(exchange) < 42) {
                        respond(
                                exchange,
                                200,
                                "id: "
                                        + EPOCH
                                        + ":42\nevent: control\ndata: "
                                        + workshopStopFrame(EPOCH, 42)
                                        + "\n\n");
                    } else {
                        respond(exchange, 200, "");
                    }
                });
        wsServer.createContext(
                "/v2/agents/ws-game/acks",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    if (ackCalls.incrementAndGet() == 1) {
                        // Transient 403 on the first ack post: the chain drops, the hook waits.
                        respond(exchange, 403, "{}");
                        return;
                    }
                    events.add(
                            "ack:"
                                    + com.google.gson.JsonParser.parseString(body)
                                            .getAsJsonObject()
                                            .get("state")
                                            .getAsString());
                    respond(exchange, 200, "{\"state\":\"received\"}");
                });
        wsServer.setExecutor(Executors.newCachedThreadPool());
        wsServer.start();
        try {
            CpStore store = new CpStore(directory.resolve("cp-ws-403"));
            CpActionHandler handler =
                    new CpActionHandler() {
                        @Override
                        public CpApplier.Outcome handleAction(
                                String kind, String target, String playerId, String reason) {
                            return CpApplier.Outcome.rejected(
                                    CpAck.Result.INVALID_SCHEMA, "unexpected");
                        }

                        @Override
                        public CpApplier.Outcome handleWorkshopGame(
                                String target,
                                String gameOp,
                                String job,
                                CompletableFuture<CpAck> acked) {
                            events.add("handler:" + gameOp);
                            acked.whenComplete(
                                    (ack, error) -> {
                                        if (error == null && ack.state == CpAck.State.EFFECTIVE) {
                                            hookSuccesses.incrementAndGet();
                                            events.add("cursor:" + store.loadCursor().sequence());
                                            events.add("halt");
                                            halted.countDown();
                                            clientStop.set(true);
                                        } else if (error != null) {
                                            events.add("hook-failed:" + error);
                                        }
                                    });
                            return CpApplier.Outcome.ok();
                        }
                    };
            CpConsumer consumer =
                    new CpConsumer(
                            store,
                            (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                            handler,
                            CLOCK,
                            "ws-game");
            CpSseClient client =
                    new CpSseClient(
                            "http://127.0.0.1:" + wsServer.getAddress().getPort(),
                            "ws-game",
                            "bearer",
                            store,
                            consumer,
                            new Quiet(),
                            CLOCK);
            ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                Future<?> done = runner.submit(() -> client.run(clientStop));
                assertTrue(halted.await(20, TimeUnit.SECONDS), "post-ack halt did not run");
                done.get(15, TimeUnit.SECONDS);
            } finally {
                runner.shutdownNow();
            }
        } finally {
            wsServer.stop(0);
        }

        assertEquals(1, hookSuccesses.get(), "post-ack hook must fire exactly once: " + events);
        assertEquals(1, events.stream().filter("handler:stop"::equals).count());
        assertTrue(events.stream().noneMatch(entry -> entry.startsWith("hook-failed:")));
        assertEquals(
                List.of(
                        "handler:stop",
                        "ack:received",
                        "ack:validated",
                        "ack:applied",
                        "ack:effective",
                        "cursor:42",
                        "halt"),
                events);
    }

    @Test
    void initialAckRejectionTriggersResyncWithoutAdvancingCursorAndFailsHook() throws Exception {
        HttpServer wsServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger ackPosts = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();
        AtomicReference<Throwable> hookError = new AtomicReference<>();
        AtomicBoolean clientStop = new AtomicBoolean();
        CountDownLatch resynced = new CountDownLatch(1);
        wsServer.createContext(
                "/v2/agents/ws-game/snapshot",
                exchange -> {
                    int hit = snapshots.incrementAndGet();
                    events.add("snapshot:" + hit);
                    respond(exchange, 200, snapshotFor("ws-game", EPOCH, 41, 41));
                    if (hit >= 2) {
                        clientStop.set(true);
                        resynced.countDown();
                    }
                });
        wsServer.createContext(
                "/v2/agents/ws-game/events",
                exchange -> {
                    if (queryCursor(exchange) < 42) {
                        respond(
                                exchange,
                                200,
                                "id: "
                                        + EPOCH
                                        + ":42\nevent: control\ndata: "
                                        + workshopStopFrame(EPOCH, 42)
                                        + "\n\n");
                    } else {
                        respond(exchange, 200, "");
                    }
                });
        wsServer.createContext(
                "/v2/agents/ws-game/acks",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    events.add("ack:" + ackPosts.incrementAndGet());
                    respond(exchange, 422, "{\"error\":\"unknown outbox row\"}");
                });
        wsServer.setExecutor(Executors.newCachedThreadPool());
        wsServer.start();
        try {
            CpStore store = new CpStore(directory.resolve("cp-ws-422"));
            CpActionHandler handler =
                    new CpActionHandler() {
                        @Override
                        public CpApplier.Outcome handleAction(
                                String kind, String target, String playerId, String reason) {
                            return CpApplier.Outcome.rejected(
                                    CpAck.Result.INVALID_SCHEMA, "unexpected");
                        }

                        @Override
                        public CpApplier.Outcome handleWorkshopGame(
                                String target,
                                String gameOp,
                                String job,
                                CompletableFuture<CpAck> acked) {
                            events.add("handler:" + gameOp);
                            acked.whenComplete(
                                    (ack, error) -> {
                                        if (error != null) {
                                            hookError.set(error);
                                            events.add("hook-failed");
                                        }
                                    });
                            return CpApplier.Outcome.ok();
                        }
                    };
            CpConsumer consumer =
                    new CpConsumer(
                            store,
                            (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                            handler,
                            CLOCK,
                            "ws-game");
            CpSseClient client =
                    new CpSseClient(
                            "http://127.0.0.1:" + wsServer.getAddress().getPort(),
                            "ws-game",
                            "bearer",
                            store,
                            consumer,
                            new Quiet(),
                            CLOCK,
                            CpSseClient.IDLE_TIMEOUT,
                            (millis, s) -> !s.get());
            ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                Future<?> done = runner.submit(() -> client.run(clientStop));
                assertTrue(resynced.await(20, TimeUnit.SECONDS), "422 must trigger resync");
                done.get(15, TimeUnit.SECONDS);
            } finally {
                runner.shutdownNow();
            }

            assertInstanceOf(CpAckRejectedException.class, hookError.get());
            assertEquals(422, ((CpAckRejectedException) hookError.get()).statusCode());
            assertEquals(1, ackPosts.get());
            assertTrue(snapshots.get() >= 2, "expected fresh snapshot resync: " + snapshots.get());
            assertEquals(
                    41,
                    store.loadCursor().sequence(),
                    "cursor must not advance when terminal outcome was never recorded");
            assertEquals(1, events.stream().filter("handler:stop"::equals).count());
        } finally {
            wsServer.stop(0);
        }
    }

    @Test
    void finalAckRejectionSettlesUnknownAndAdvancesCursorOnlyAfterAccepted() throws Exception {
        HttpServer wsServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> events = new CopyOnWriteArrayList<>();
        List<JsonObject> postedAcks = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> hookError = new AtomicReference<>();
        AtomicBoolean clientStop = new AtomicBoolean();
        CountDownLatch settled = new CountDownLatch(1);
        wsServer.createContext(
                "/v2/agents/ws-game/snapshot",
                exchange -> respond(exchange, 200, snapshotFor("ws-game", EPOCH, 41, 41)));
        wsServer.createContext(
                "/v2/agents/ws-game/events",
                exchange -> {
                    if (queryCursor(exchange) < 42) {
                        respond(
                                exchange,
                                200,
                                "id: "
                                        + EPOCH
                                        + ":42\nevent: control\ndata: "
                                        + workshopStopFrame(EPOCH, 42)
                                        + "\n\n");
                    } else {
                        respond(exchange, 200, "");
                        clientStop.set(true);
                        settled.countDown();
                    }
                });
        CpStore store = new CpStore(directory.resolve("cp-ws-final-422-unknown"));
        wsServer.createContext(
                "/v2/agents/ws-game/acks",
                exchange -> {
                    String raw =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    JsonObject parsed = JsonParser.parseString(raw).getAsJsonObject();
                    postedAcks.add(parsed);
                    String state = parsed.get("state").getAsString();
                    events.add("ack:" + state + ":cursor=" + store.loadCursor().sequence());
                    if ("effective".equals(state)) {
                        respond(exchange, 422, "{\"error\":\"output rejected\"}");
                        return;
                    }
                    respond(exchange, 200, "{}");
                });
        wsServer.setExecutor(Executors.newCachedThreadPool());
        wsServer.start();
        try {
            CpActionHandler handler =
                    new CpActionHandler() {
                        @Override
                        public CpApplier.Outcome handleAction(
                                String kind, String target, String playerId, String reason) {
                            return CpApplier.Outcome.rejected(
                                    CpAck.Result.INVALID_SCHEMA, "unexpected");
                        }

                        @Override
                        public CpApplier.Outcome handleWorkshopGame(
                                String target,
                                String gameOp,
                                String job,
                                CompletableFuture<CpAck> acked) {
                            events.add("handler:" + gameOp);
                            acked.whenComplete(
                                    (ack, error) -> {
                                        if (error != null) {
                                            hookError.set(error);
                                            events.add(
                                                    "hook-failed:cursor="
                                                            + store.loadCursor().sequence());
                                        }
                                    });
                            return CpApplier.Outcome.ok("stop output");
                        }
                    };
            CpConsumer consumer =
                    new CpConsumer(
                            store,
                            (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                            handler,
                            CLOCK,
                            "ws-game");
            CpSseClient client =
                    new CpSseClient(
                            "http://127.0.0.1:" + wsServer.getAddress().getPort(),
                            "ws-game",
                            "bearer",
                            store,
                            consumer,
                            new Quiet(),
                            CLOCK,
                            CpSseClient.IDLE_TIMEOUT,
                            (millis, s) -> !s.get());
            ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                Future<?> done = runner.submit(() -> client.run(clientStop));
                assertTrue(
                        settled.await(20, TimeUnit.SECONDS),
                        "expected stream resume after unknown settlement");
                done.get(15, TimeUnit.SECONDS);
            } finally {
                runner.shutdownNow();
            }

            assertInstanceOf(CpAckRejectedException.class, hookError.get());
            assertEquals(422, ((CpAckRejectedException) hookError.get()).statusCode());
            assertEquals(
                    List.of("received", "validated", "applied", "effective", "unknown"),
                    postedAcks.stream().map(o -> o.get("state").getAsString()).toList());
            JsonObject unknownBody = postedAcks.get(4);
            assertEquals("effect_uncertain", unknownBody.get("result").getAsString());
            assertFalse(unknownBody.has("output"));
            assertTrue(consumer.needsOperator());
            CpStore.Terminal terminal =
                    store.lastTerminal(EPOCH, "123e4567-e89b-12d3-a456-426614170042");
            assertNotNull(terminal);
            assertEquals(CpAck.State.UNKNOWN, terminal.state());
            assertEquals(CpAck.Result.EFFECT_UNCERTAIN, terminal.result());
            assertEquals(42, store.loadCursor().sequence());
            assertEquals(
                    List.of(
                            "handler:stop",
                            "ack:received:cursor=41",
                            "ack:validated:cursor=41",
                            "ack:applied:cursor=41",
                            "ack:effective:cursor=41",
                            "ack:unknown:cursor=41",
                            "hook-failed:cursor=42"),
                    events);
        } finally {
            wsServer.stop(0);
        }
    }

    @Test
    void finalAckAndFallbackUnknownRejectedDoesNotAdvanceCursorAndResyncs() throws Exception {
        HttpServer wsServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> postedStates = new CopyOnWriteArrayList<>();
        AtomicInteger snapshots = new AtomicInteger();
        AtomicReference<Throwable> hookError = new AtomicReference<>();
        AtomicBoolean clientStop = new AtomicBoolean();
        CountDownLatch resynced = new CountDownLatch(1);
        wsServer.createContext(
                "/v2/agents/ws-game/snapshot",
                exchange -> {
                    int hit = snapshots.incrementAndGet();
                    respond(exchange, 200, snapshotFor("ws-game", EPOCH, 41, 41));
                    if (hit >= 2) {
                        clientStop.set(true);
                        resynced.countDown();
                    }
                });
        wsServer.createContext(
                "/v2/agents/ws-game/events",
                exchange -> {
                    if (queryCursor(exchange) < 42) {
                        respond(
                                exchange,
                                200,
                                "id: "
                                        + EPOCH
                                        + ":42\nevent: control\ndata: "
                                        + workshopStopFrame(EPOCH, 42)
                                        + "\n\n");
                    } else {
                        respond(exchange, 200, "");
                    }
                });
        wsServer.createContext(
                "/v2/agents/ws-game/acks",
                exchange -> {
                    String raw =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    String state =
                            JsonParser.parseString(raw)
                                    .getAsJsonObject()
                                    .get("state")
                                    .getAsString();
                    postedStates.add(state);
                    if ("effective".equals(state) || "unknown".equals(state)) {
                        respond(exchange, 422, "{\"error\":\"receipt_time\"}");
                        return;
                    }
                    respond(exchange, 200, "{}");
                });
        wsServer.setExecutor(Executors.newCachedThreadPool());
        wsServer.start();
        try {
            CpStore store = new CpStore(directory.resolve("cp-ws-final-422-resync"));
            CpActionHandler handler =
                    new CpActionHandler() {
                        @Override
                        public CpApplier.Outcome handleAction(
                                String kind, String target, String playerId, String reason) {
                            return CpApplier.Outcome.rejected(
                                    CpAck.Result.INVALID_SCHEMA, "unexpected");
                        }

                        @Override
                        public CpApplier.Outcome handleWorkshopGame(
                                String target,
                                String gameOp,
                                String job,
                                CompletableFuture<CpAck> acked) {
                            acked.whenComplete(
                                    (ack, error) -> {
                                        if (error != null) hookError.set(error);
                                    });
                            return CpApplier.Outcome.ok("stop output");
                        }
                    };
            CpConsumer consumer =
                    new CpConsumer(
                            store,
                            (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                            handler,
                            CLOCK,
                            "ws-game");
            CpSseClient client =
                    new CpSseClient(
                            "http://127.0.0.1:" + wsServer.getAddress().getPort(),
                            "ws-game",
                            "bearer",
                            store,
                            consumer,
                            new Quiet(),
                            CLOCK,
                            CpSseClient.IDLE_TIMEOUT,
                            (millis, s) -> !s.get());
            ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                Future<?> done = runner.submit(() -> client.run(clientStop));
                assertTrue(
                        resynced.await(20, TimeUnit.SECONDS),
                        "fallback 422 must trigger resync without advancing cursor");
                done.get(15, TimeUnit.SECONDS);
            } finally {
                runner.shutdownNow();
            }

            assertInstanceOf(CpAckRejectedException.class, hookError.get());
            assertEquals(422, ((CpAckRejectedException) hookError.get()).statusCode());
            assertEquals(1, hookError.get().getSuppressed().length);
            assertEquals(
                    List.of("received", "validated", "applied", "effective", "unknown"),
                    postedStates);
            assertEquals(
                    41,
                    store.loadCursor().sequence(),
                    "cursor must never advance when fallback unknown is also rejected");
            assertTrue(snapshots.get() >= 2);
        } finally {
            wsServer.stop(0);
        }
    }

    @Test
    void finalAckRejectedThenFallback500And429KeepsHookPendingUntilRetrySettlesUnknown()
            throws Exception {
        HttpServer wsServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> postedStates = new CopyOnWriteArrayList<>();
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger unknownAttempts = new AtomicInteger();
        AtomicReference<Throwable> hookError = new AtomicReference<>();
        AtomicReference<CompletableFuture<CpAck>> hookRef = new AtomicReference<>();
        AtomicBoolean pendingDuringRetry = new AtomicBoolean(true);
        AtomicBoolean clientStop = new AtomicBoolean();
        CountDownLatch settled = new CountDownLatch(1);
        wsServer.createContext(
                "/v2/agents/ws-game/snapshot",
                exchange -> respond(exchange, 200, snapshotFor("ws-game", EPOCH, 41, 41)));
        wsServer.createContext(
                "/v2/agents/ws-game/events",
                exchange -> {
                    if (queryCursor(exchange) < 42) {
                        respond(
                                exchange,
                                200,
                                "id: "
                                        + EPOCH
                                        + ":42\nevent: control\ndata: "
                                        + workshopStopFrame(EPOCH, 42)
                                        + "\n\n");
                    } else {
                        respond(exchange, 200, "");
                        clientStop.set(true);
                        settled.countDown();
                    }
                });
        wsServer.createContext(
                "/v2/agents/ws-game/acks",
                exchange -> {
                    String raw =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    String state =
                            JsonParser.parseString(raw)
                                    .getAsJsonObject()
                                    .get("state")
                                    .getAsString();
                    postedStates.add(state);
                    if ("effective".equals(state)) {
                        respond(exchange, 422, "{\"error\":\"receipt_time\"}");
                        return;
                    }
                    if ("unknown".equals(state)) {
                        int attempt = unknownAttempts.incrementAndGet();
                        if (attempt == 1) {
                            CompletableFuture<CpAck> f = hookRef.get();
                            pendingDuringRetry.compareAndSet(true, f != null && !f.isDone());
                            respond(exchange, 500, "temporary error");
                            return;
                        }
                        if (attempt == 2) {
                            CompletableFuture<CpAck> f = hookRef.get();
                            pendingDuringRetry.compareAndSet(true, f != null && !f.isDone());
                            respond(exchange, 429, "rate limit");
                            return;
                        }
                    }
                    respond(exchange, 200, "{}");
                });
        wsServer.setExecutor(Executors.newCachedThreadPool());
        wsServer.start();
        try {
            CpStore store = new CpStore(directory.resolve("cp-ws-fallback-500-retry"));
            CpActionHandler handler =
                    new CpActionHandler() {
                        @Override
                        public CpApplier.Outcome handleAction(
                                String kind, String target, String playerId, String reason) {
                            return CpApplier.Outcome.rejected(
                                    CpAck.Result.INVALID_SCHEMA, "unexpected");
                        }

                        @Override
                        public CpApplier.Outcome handleWorkshopGame(
                                String target,
                                String gameOp,
                                String job,
                                CompletableFuture<CpAck> acked) {
                            events.add("handler:" + gameOp);
                            hookRef.set(acked);
                            acked.whenComplete(
                                    (ack, error) -> {
                                        if (error == null) {
                                            events.add("stop-ran");
                                        } else {
                                            hookError.set(error);
                                            events.add("hook-failed");
                                        }
                                    });
                            return CpApplier.Outcome.ok("stop output");
                        }
                    };
            CpConsumer consumer =
                    new CpConsumer(
                            store,
                            (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                            handler,
                            CLOCK,
                            "ws-game");
            CpSseClient client =
                    new CpSseClient(
                            "http://127.0.0.1:" + wsServer.getAddress().getPort(),
                            "ws-game",
                            "bearer",
                            store,
                            consumer,
                            new Quiet(),
                            CLOCK,
                            CpSseClient.IDLE_TIMEOUT,
                            (millis, s) -> !s.get());
            ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                Future<?> done = runner.submit(() -> client.run(clientStop));
                assertTrue(
                        settled.await(20, TimeUnit.SECONDS),
                        "expected retry to settle unknown and advance cursor");
                done.get(15, TimeUnit.SECONDS);
            } finally {
                runner.shutdownNow();
            }

            assertTrue(pendingDuringRetry.get(), "hook must stay pending across fallback 500/429");
            assertInstanceOf(CpAckRejectedException.class, hookError.get());
            assertEquals(422, ((CpAckRejectedException) hookError.get()).statusCode());
            assertEquals(
                    List.of(
                            "received",
                            "validated",
                            "applied",
                            "effective",
                            "unknown",
                            "unknown",
                            "unknown"),
                    postedStates);
            assertEquals(42, store.loadCursor().sequence());
            assertEquals(List.of("handler:stop", "hook-failed"), events);
            assertTrue(consumer.needsOperator());
        } finally {
            wsServer.stop(0);
        }
    }

    @Test
    void finalAckRejectedThenFallback409ResnapshotRedeliversAndSettlesUnknown() throws Exception {
        HttpServer wsServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> postedStates = new CopyOnWriteArrayList<>();
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger snapshots = new AtomicInteger();
        AtomicInteger unknownAttempts = new AtomicInteger();
        AtomicReference<Throwable> hookError = new AtomicReference<>();
        AtomicBoolean clientStop = new AtomicBoolean();
        CountDownLatch settled = new CountDownLatch(1);
        wsServer.createContext(
                "/v2/agents/ws-game/snapshot",
                exchange -> {
                    snapshots.incrementAndGet();
                    // CP never moves received_cursor past 41 until a terminal receipt lands.
                    respond(exchange, 200, snapshotFor("ws-game", EPOCH, 41, 41));
                });
        wsServer.createContext(
                "/v2/agents/ws-game/events",
                exchange -> {
                    if (queryCursor(exchange) < 42) {
                        respond(
                                exchange,
                                200,
                                "id: "
                                        + EPOCH
                                        + ":42\nevent: control\ndata: "
                                        + workshopStopFrame(EPOCH, 42)
                                        + "\n\n");
                    } else {
                        respond(exchange, 200, "");
                        clientStop.set(true);
                        settled.countDown();
                    }
                });
        wsServer.createContext(
                "/v2/agents/ws-game/acks",
                exchange -> {
                    String raw =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    String state =
                            JsonParser.parseString(raw)
                                    .getAsJsonObject()
                                    .get("state")
                                    .getAsString();
                    postedStates.add(state);
                    if ("effective".equals(state)) {
                        respond(exchange, 422, "{\"error\":\"receipt_time\"}");
                        return;
                    }
                    if ("unknown".equals(state) && unknownAttempts.incrementAndGet() == 1) {
                        respond(exchange, 409, "{\"error\":\"resync\"}");
                        return;
                    }
                    respond(exchange, 200, "{}");
                });
        wsServer.setExecutor(Executors.newCachedThreadPool());
        wsServer.start();
        try {
            CpStore store = new CpStore(directory.resolve("cp-ws-fallback-409-resnapshot"));
            CpActionHandler handler =
                    new CpActionHandler() {
                        @Override
                        public CpApplier.Outcome handleAction(
                                String kind, String target, String playerId, String reason) {
                            return CpApplier.Outcome.rejected(
                                    CpAck.Result.INVALID_SCHEMA, "unexpected");
                        }

                        @Override
                        public CpApplier.Outcome handleWorkshopGame(
                                String target,
                                String gameOp,
                                String job,
                                CompletableFuture<CpAck> acked) {
                            events.add("handler:" + gameOp);
                            acked.whenComplete(
                                    (ack, error) -> {
                                        if (error == null) {
                                            events.add("stop-ran");
                                        } else {
                                            hookError.set(error);
                                            events.add("hook-failed");
                                        }
                                    });
                            return CpApplier.Outcome.ok("stop output");
                        }
                    };
            CpConsumer consumer =
                    new CpConsumer(
                            store,
                            (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                            handler,
                            CLOCK,
                            "ws-game");
            CpSseClient client =
                    new CpSseClient(
                            "http://127.0.0.1:" + wsServer.getAddress().getPort(),
                            "ws-game",
                            "bearer",
                            store,
                            consumer,
                            new Quiet(),
                            CLOCK,
                            CpSseClient.IDLE_TIMEOUT,
                            (millis, s) -> !s.get());
            ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                Future<?> done = runner.submit(() -> client.run(clientStop));
                assertTrue(
                        settled.await(20, TimeUnit.SECONDS),
                        "expected resnapshot + redelivery to settle unknown");
                done.get(15, TimeUnit.SECONDS);
            } finally {
                runner.shutdownNow();
            }

            assertTrue(snapshots.get() >= 2);
            assertInstanceOf(CpAckRejectedException.class, hookError.get());
            assertEquals(422, ((CpAckRejectedException) hookError.get()).statusCode());
            assertEquals(
                    List.of("received", "validated", "applied", "effective", "unknown", "unknown"),
                    postedStates);
            assertEquals(42, store.loadCursor().sequence());
            assertEquals(List.of("handler:stop", "hook-failed"), events);
        } finally {
            wsServer.stop(0);
        }
    }

    @Test
    void initialAckRejectionResnapshotRedeliversNonTerminalOpWithoutReplayingEffective()
            throws Exception {
        HttpServer wsServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> postedStates = new CopyOnWriteArrayList<>();
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger snapshots = new AtomicInteger();
        AtomicReference<Throwable> hookError = new AtomicReference<>();
        AtomicBoolean clientStop = new AtomicBoolean();
        CountDownLatch settled = new CountDownLatch(1);
        wsServer.createContext(
                "/v2/agents/ws-game/snapshot",
                exchange -> {
                    snapshots.incrementAndGet();
                    respond(exchange, 200, snapshotFor("ws-game", EPOCH, 41, 41));
                });
        wsServer.createContext(
                "/v2/agents/ws-game/events",
                exchange -> {
                    if (queryCursor(exchange) < 42) {
                        respond(
                                exchange,
                                200,
                                "id: "
                                        + EPOCH
                                        + ":42\nevent: control\ndata: "
                                        + workshopStopFrame(EPOCH, 42)
                                        + "\n\n");
                    } else {
                        respond(exchange, 200, "");
                        clientStop.set(true);
                        settled.countDown();
                    }
                });
        wsServer.createContext(
                "/v2/agents/ws-game/acks",
                exchange -> {
                    String raw =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    String state =
                            JsonParser.parseString(raw)
                                    .getAsJsonObject()
                                    .get("state")
                                    .getAsString();
                    postedStates.add(state);
                    if ("received".equals(state)) {
                        respond(exchange, 422, "{\"error\":\"receipt_time\"}");
                        return;
                    }
                    respond(exchange, 200, "{}");
                });
        wsServer.setExecutor(Executors.newCachedThreadPool());
        wsServer.start();
        try {
            CpStore store = new CpStore(directory.resolve("cp-ws-initial-422-redeliver"));
            CpActionHandler handler =
                    new CpActionHandler() {
                        @Override
                        public CpApplier.Outcome handleAction(
                                String kind, String target, String playerId, String reason) {
                            return CpApplier.Outcome.rejected(
                                    CpAck.Result.INVALID_SCHEMA, "unexpected");
                        }

                        @Override
                        public CpApplier.Outcome handleWorkshopGame(
                                String target,
                                String gameOp,
                                String job,
                                CompletableFuture<CpAck> acked) {
                            events.add("handler:" + gameOp);
                            acked.whenComplete(
                                    (ack, error) -> {
                                        if (error == null) {
                                            events.add("stop-ran");
                                        } else {
                                            hookError.set(error);
                                            events.add("hook-failed");
                                        }
                                    });
                            return CpApplier.Outcome.ok();
                        }
                    };
            CpConsumer consumer =
                    new CpConsumer(
                            store,
                            (namespace, target, revision, value) -> CpApplier.Outcome.ok(),
                            handler,
                            CLOCK,
                            "ws-game");
            CpSseClient client =
                    new CpSseClient(
                            "http://127.0.0.1:" + wsServer.getAddress().getPort(),
                            "ws-game",
                            "bearer",
                            store,
                            consumer,
                            new Quiet(),
                            CLOCK,
                            CpSseClient.IDLE_TIMEOUT,
                            (millis, s) -> !s.get());
            ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                Future<?> done = runner.submit(() -> client.run(clientStop));
                assertTrue(
                        settled.await(20, TimeUnit.SECONDS),
                        "expected resnapshot redelivery to settle as unknown");
                done.get(15, TimeUnit.SECONDS);
            } finally {
                runner.shutdownNow();
            }

            assertTrue(snapshots.get() >= 2);
            assertInstanceOf(CpAckRejectedException.class, hookError.get());
            assertEquals(422, ((CpAckRejectedException) hookError.get()).statusCode());
            assertEquals(List.of("received", "unknown"), postedStates);
            assertEquals(42, store.loadCursor().sequence());
            assertEquals(List.of("handler:stop", "hook-failed"), events);
        } finally {
            wsServer.stop(0);
        }
    }

    @Test
    void conflictResnapshotsBeforeRetryAndDropsSupersededChain() throws Exception {
        CpStore store = new CpStore(directory.resolve("conflict-epoch"));
        AtomicInteger failed = new AtomicInteger();
        CpActionHandler handler =
                new CpActionHandler() {
                    public CpApplier.Outcome handleAction(
                            String kind, String target, String playerId, String reason) {
                        return CpApplier.Outcome.ok();
                    }

                    public CpApplier.Outcome handleWorkshopGame(
                            String target, String op, String job, CompletableFuture<CpAck> acked) {
                        acked.whenComplete(
                                (ack, error) -> {
                                    if (error != null) failed.incrementAndGet();
                                });
                        return CpApplier.Outcome.ok();
                    }
                };
        CpConsumer consumer =
                new CpConsumer(store, (n, t, r, v) -> CpApplier.Outcome.ok(), handler, CLOCK);
        consumer.onSnapshot(snapshot());
        String action = workshopStopFrame(EPOCH, 42).replace("ws-game", "game-1");
        eventsBody = cursor -> sse(action);
        ackStatus = body -> 409;
        CpSseClient client = newClient(store, consumer);
        assertThrows(CpResyncException.class, () -> client.stream(new AtomicBoolean()));
        snapshotBody = () -> snapshotAt("223e4567-e89b-12d3-a456-426614174000", 0, 0);
        stopAfterSnapshots.set(1);
        runToStop(client);
        assertEquals(1, ackBodies.size());
        assertNull(store.loadPendingAcks());
        assertEquals(1, failed.get());
    }

    @Test
    void restartDemotesChainWithLostHook() throws Exception {
        CpStore store = new CpStore(directory.resolve("restart-hook"));
        CpActionHandler handler =
                new CpActionHandler() {
                    public CpApplier.Outcome handleAction(
                            String kind, String target, String playerId, String reason) {
                        return CpApplier.Outcome.ok();
                    }

                    public CpApplier.Outcome handleWorkshopGame(
                            String target, String op, String job, CompletableFuture<CpAck> acked) {
                        acked.thenAccept(ack -> {});
                        return CpApplier.Outcome.ok();
                    }
                };
        CpConsumer first =
                new CpConsumer(store, (n, t, r, v) -> CpApplier.Outcome.ok(), handler, CLOCK);
        first.onSnapshot(snapshot());
        eventsBody = cursor -> sse(workshopStopFrame(EPOCH, 42).replace("ws-game", "game-1"));
        AtomicInteger calls = new AtomicInteger();
        ackStatus = body -> calls.incrementAndGet() == 2 ? 500 : 200;
        assertThrows(IOException.class, () -> newClient(store, first).stream(new AtomicBoolean()));
        assertTrue(store.loadPendingAcks().hadHook());
        stopAfterAcks.set(5);
        eventsBody = cursor -> "";
        CpStore reopened = new CpStore(directory.resolve("restart-hook"));
        CpConsumer second = new CpConsumer(reopened, (n, t, r, v) -> CpApplier.Outcome.ok(), CLOCK);
        runToStop(newClient(reopened, second));
        assertEquals(
                List.of("received", "validated", "validated", "applied", "unknown"),
                ackBodies.stream()
                        .map(
                                body ->
                                        JsonParser.parseString(body)
                                                .getAsJsonObject()
                                                .get("state")
                                                .getAsString())
                        .toList());
        assertEquals(
                CpAck.State.UNKNOWN,
                reopened.lastTerminal(EPOCH, "123e4567-e89b-12d3-a456-426614170042").state());
    }

    private String snapshotFor(String audience, String epoch, long cursor, long revision) {
        JsonObject entry = new JsonObject();
        entry.addProperty("namespace", "gameplay");
        entry.addProperty("target", "*");
        entry.addProperty("revision", revision);
        entry.add("value", new JsonObject());
        entry.addProperty("value_hash", "snapshot");
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        root.addProperty("audience", audience);
        root.addProperty("epoch", epoch);
        root.addProperty("generation", GENERATION);
        root.addProperty("cursor", cursor);
        JsonArray settings = new JsonArray();
        settings.add(entry);
        root.add("settings", settings);
        root.addProperty("hash", "snapshot");
        return root.toString();
    }

    private String workshopStopFrame(String epoch, long sequence) {
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", "workshop.game");
        payload.addProperty("target", "workshop");
        payload.addProperty("gameOp", "stop");
        JsonObject frame = new JsonObject();
        frame.addProperty("schema", 2);
        frame.addProperty("epoch", epoch);
        frame.addProperty("sequence", sequence);
        frame.addProperty(
                "operationId",
                "123e4567-e89b-12d3-a456-42661417" + String.format("%04d", sequence));
        frame.addProperty("audience", "ws-game");
        frame.addProperty("generation", GENERATION);
        frame.add("revision", null);
        frame.addProperty("payloadHash", "a".repeat(64));
        frame.add("authorizationEpoch", null);
        frame.addProperty("expiresAt", OffsetDateTime.now(CLOCK).plusMinutes(5).toString());
        frame.addProperty("executable", true);
        frame.add("reason", null);
        frame.add("payload", payload);
        return frame.toString();
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
