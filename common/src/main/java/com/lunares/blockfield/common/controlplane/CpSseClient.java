package com.lunares.blockfield.common.controlplane;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JDK-only HTTP drive for {@link CpConsumer}: snapshot fetch, persistent SSE stream with
 * cursor/epoch resume, and ordered ack posts. No third-party client; no work ever happens on the
 * calling thread except this loop, so the game must run it on a background executor and apply
 * through the applier at its own safe boundary.
 */
public final class CpSseClient {
    public interface Reporter {
        void info(String message);

        void warn(String message, Throwable error);
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    private static final long BACKOFF_MIN_MILLIS = 1_000;
    private static final long BACKOFF_MAX_MILLIS = 30_000;

    private final String baseUrl;
    private final String agentId;
    private final String bearer;
    private final CpStore store;
    private final CpConsumer consumer;
    private final Reporter reporter;
    private final Clock clock;
    private final HttpClient http;

    public CpSseClient(
            String baseUrl,
            String agentId,
            String bearer,
            CpStore store,
            CpConsumer consumer,
            Reporter reporter,
            Clock clock) {
        this.baseUrl = baseUrl;
        this.agentId = agentId;
        this.bearer = bearer;
        this.store = store;
        this.consumer = consumer;
        this.reporter = reporter;
        this.clock = clock;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    /**
     * Snapshot, then stream until {@code stop} is set. Io failures back off and resume from the
     * durable cursor; epoch/generation changes re-snapshot. Returns when stopped.
     */
    public void run(AtomicBoolean stop) {
        long backoff = BACKOFF_MIN_MILLIS;
        while (!stop.get()) {
            try {
                CpStore.Cursor cursor = store.loadCursor();
                if (cursor.sequence() == 0 || cursor.epoch().isEmpty()) {
                    resnapshot();
                }
                stream(stop);
                backoff = BACKOFF_MIN_MILLIS;
            } catch (CpResyncException e) {
                reporter.warn("control-plane resync: " + e.getMessage(), e);
                backoff = BACKOFF_MIN_MILLIS;
            } catch (IOException | InterruptedException e) {
                if (stop.get() || e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                reporter.warn("control-plane stream failed, retrying", e);
                if (!sleep(backoff, stop)) return;
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MILLIS);
            }
        }
    }

    private void resnapshot() throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl + "/v2/agents/" + agentId + "/snapshot"))
                        .timeout(READ_TIMEOUT)
                        .header("Authorization", "Bearer " + bearer)
                        .GET()
                        .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409
                || response.statusCode() == 401
                || response.statusCode() == 403) {
            throw new CpResyncException("snapshot refused: HTTP " + response.statusCode());
        }
        if (response.statusCode() != 200) {
            throw new IOException("snapshot HTTP " + response.statusCode());
        }
        CpConsumer.SnapshotReport report = consumer.onSnapshot(response.body());
        for (String error : report.errors()) reporter.info("control-plane snapshot: " + error);
    }

    private void stream(AtomicBoolean stop) throws IOException, InterruptedException {
        CpStore.Cursor cursor = store.loadCursor();
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(
                                        baseUrl
                                                + "/v2/agents/"
                                                + agentId
                                                + "/events?epoch="
                                                + cursor.epoch()
                                                + "&cursor="
                                                + cursor.sequence()))
                        .timeout(READ_TIMEOUT)
                        .header("Authorization", "Bearer " + bearer)
                        .header("Accept", "text/event-stream")
                        .header("Last-Event-ID", cursor.epoch() + ":" + cursor.sequence())
                        .GET()
                        .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409) {
            store.saveCursor(cursor.epoch(), cursor.sequence());
            throw new CpResyncException("events refused, snapshot required");
        }
        if (response.statusCode() != 200) {
            throw new IOException("events HTTP " + response.statusCode());
        }
        dispatchEvents(response.body(), stop);
    }

    void dispatchEvents(String body, AtomicBoolean stop) throws IOException, InterruptedException {
        String event = "message";
        List<String> data = new ArrayList<>();
        try (BufferedReader lines = new BufferedReader(new StringReader(body))) {
            String line;
            while (!stop.get() && (line = lines.readLine()) != null) {
                if (line.startsWith(":")) continue;
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.add(line.substring("data:".length()).trim());
                } else if (line.isEmpty()) {
                    dispatch(event, String.join("\n", data));
                    event = "message";
                    data.clear();
                }
            }
        }
    }

    private void dispatch(String event, String data) throws IOException, InterruptedException {
        if (data.isEmpty()) return;
        if ("resync".equals(event)) throw new CpResyncException("server asked to resync");
        if (!"control".equals(event)) return;
        List<CpAck> acks = consumer.onFrame(data);
        for (CpAck ack : acks) postAck(ack);
    }

    void postAck(CpAck ack) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl + "/v2/agents/" + agentId + "/acks"))
                        .timeout(READ_TIMEOUT)
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(ack.toJson()))
                        .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409) {
            throw new CpResyncException("ack rejected: " + response.body());
        }
        if (response.statusCode() != 200) {
            throw new IOException("ack HTTP " + response.statusCode());
        }
    }

    private boolean sleep(long millis, AtomicBoolean stop) {
        long deadline = clock.millis() + millis;
        while (!stop.get() && clock.millis() < deadline) {
            try {
                Thread.sleep(Math.min(200, deadline - clock.millis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !stop.get();
    }
}
