package com.lunares.blockfield.common.controlplane;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    /** Outcome of one {@code /events} connection: frames applied, and any line seen. */
    record StreamOutcome(int frames, boolean active) {}

    /**
     * Split-credential form: the stream spends only the command bearer; the console-read bearer
     * belongs to the server-side ConsoleLink and never leaves with this client.
     */
    public CpSseClient(
            String baseUrl,
            String agentId,
            CpCredentials credentials,
            CpStore store,
            CpConsumer consumer,
            Reporter reporter,
            Clock clock) {
        this(baseUrl, agentId, credentials.commandBearer(), store, consumer, reporter, clock);
    }

    /**
     * Snapshot, then stream until {@code stop} is set. Io failures back off and resume from the
     * durable cursor; any resync (epoch/generation/gap/conflict) re-snapshots before resuming. A
     * poison frame that fails contract parsing is warned and retried with backoff, never fatal: the
     * cursor does not advance past it, so it replays until an operator reconciles it. Keep-alive
     * comments refresh liveness: a connection that carried any line resumes without growing the
     * backoff; only a totally silent close backs off. Returns when stopped.
     */
    public void run(AtomicBoolean stop) {
        long backoff = BACKOFF_MIN_MILLIS;
        boolean needsSnapshot = true;
        while (!stop.get()) {
            try {
                if (needsSnapshot) {
                    resnapshot();
                    needsSnapshot = false;
                    dropRetiredPendings();
                }
                flushPendingAcks();
                StreamOutcome outcome = stream(stop);
                if (outcome.frames() > 0) {
                    backoff = BACKOFF_MIN_MILLIS;
                } else if (outcome.active()) {
                    backoff = BACKOFF_MIN_MILLIS;
                    if (!sleep(backoff, stop)) return;
                } else {
                    if (!sleep(backoff, stop)) return;
                    backoff = Math.min(backoff * 2, BACKOFF_MAX_MILLIS);
                }
            } catch (CpResyncException e) {
                reporter.warn("control-plane resync: " + e.getMessage(), e);
                needsSnapshot = true;
                if (!sleep(backoff, stop)) return;
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MILLIS);
            } catch (RuntimeException e) {
                reporter.warn("control-plane consumer failed, retrying", e);
                if (!sleep(backoff, stop)) return;
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MILLIS);
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

    StreamOutcome stream(AtomicBoolean stop) throws IOException, InterruptedException {
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
        HttpResponse<InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 409) {
            response.body().close();
            throw new CpResyncException("events refused, snapshot required");
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("events HTTP " + response.statusCode());
        }
        int frames = 0;
        boolean active = false;
        try (InputStream in = response.body();
                BufferedReader lines =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String event = "message";
            List<String> data = new ArrayList<>();
            String line;
            while (!stop.get() && (line = lines.readLine()) != null) {
                active = true;
                if (line.startsWith(":")) continue;
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.add(line.substring("data:".length()).trim());
                } else if (line.isEmpty()) {
                    if (dispatch(event, String.join("\n", data))) frames++;
                    event = "message";
                    data.clear();
                }
            }
        }
        return new StreamOutcome(frames, active);
    }

    /**
     * Re-post ack chains that were generated but never accepted (crash or failed POST), in exact
     * bytes so server duplicates succeed. Runs before every stream; the cursor only advances in
     * {@link #dispatch} after all acks of a frame are accepted.
     */
    void flushPendingAcks() throws IOException, InterruptedException {
        List<String> pendings = store.loadPendingAcks();
        if (pendings.isEmpty()) return;
        for (String body : pendings) postAckJson(body);
        commit(firstIdentity(pendings.get(0)));
        store.clearPendingAcks();
    }

    /**
     * An epoch rotation retires old-epoch frames: the server only accepts terminal receipts with a
     * historical identity, never our non-terminal chain. Drop the retired pendings loudly; the
     * local terminal receipts stay in the journal and the fresh snapshot replays what is needed.
     */
    private void dropRetiredPendings() {
        List<String> pendings = store.loadPendingAcks();
        if (pendings.isEmpty()) return;
        String pendingEpoch = firstIdentity(pendings.get(0)).epoch();
        if (pendingEpoch.equals(consumer.epoch())) return;
        reporter.warn(
                "control-plane dropped " + pendings.size() + " retired pending acks",
                new CpResyncException(
                        "pending epoch " + pendingEpoch + " retired by " + consumer.epoch()));
        store.clearPendingAcks();
    }

    private static AckIdentity firstIdentity(String body) {
        JsonObject ack = JsonParser.parseString(body).getAsJsonObject();
        return new AckIdentity(ack.get("epoch").getAsString(), ack.get("sequence").getAsLong());
    }

    private record AckIdentity(String epoch, long sequence) {}

    /** Advance the cursor only after the full ack chain was accepted. Never moves backwards. */
    private void commit(AckIdentity identity) {
        store.saveCursorIfForward(identity.epoch(), identity.sequence());
    }

    private boolean dispatch(String event, String data) throws IOException, InterruptedException {
        if (data.isEmpty()) return false;
        if ("resync".equals(event)) throw new CpResyncException("server asked to resync");
        if (!"control".equals(event)) return false;
        List<CpAck> acks = consumer.onFrame(data);
        List<String> bodies = new ArrayList<>();
        for (CpAck ack : acks) bodies.add(ack.toJson());
        store.savePendingAcks(bodies);
        for (CpAck ack : acks) postAck(ack);
        commit(new AckIdentity(acks.get(0).epoch.toString(), acks.get(0).sequence));
        store.clearPendingAcks();
        return true;
    }

    void postAck(CpAck ack) throws IOException, InterruptedException {
        postAckJson(ack.toJson());
    }

    void postAckJson(String body) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl + "/v2/agents/" + agentId + "/acks"))
                        .timeout(READ_TIMEOUT)
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
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
