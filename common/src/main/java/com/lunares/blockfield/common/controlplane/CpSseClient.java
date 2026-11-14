package com.lunares.blockfield.common.controlplane;

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

        /**
         * One-line warning without a stack trace, for a stuck-but-routine state like a replaying
         * poison frame. Defaults to info so older consumers keep compiling.
         */
        default void warn(String message) {
            info(message);
        }
    }

    /**
     * Interruptible backoff sleep; tests substitute a fake to assert the schedule
     * deterministically.
     */
    interface Sleeper {
        boolean sleep(long millis, AtomicBoolean stop);
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    /** Idle watchdog: the server keep-alives well inside this, so silence means a dead stream. */
    static final Duration IDLE_TIMEOUT = Duration.ofSeconds(90);

    private static final long BACKOFF_MIN_MILLIS = 1_000;
    private static final long BACKOFF_MAX_MILLIS = 30_000;
    private static final long POLL_MILLIS = 200;

    /** One poison frame replays every backoff; repeats of its rejection stay silent this long. */
    static final long CONTRACT_WARN_SUPPRESS_MILLIS = 5 * 60 * 1_000;

    private final String baseUrl;
    private final String agentId;
    private final String bearer;
    private final CpStore store;
    private final CpConsumer consumer;
    private final Reporter reporter;
    private final Clock clock;
    private final HttpClient http;
    private final Duration idleTimeout;
    private final Sleeper sleeper;

    private String lastContractMessage;
    private long lastContractWarnMillis;

    public CpSseClient(
            String baseUrl,
            String agentId,
            String bearer,
            CpStore store,
            CpConsumer consumer,
            Reporter reporter,
            Clock clock) {
        this(baseUrl, agentId, bearer, store, consumer, reporter, clock, IDLE_TIMEOUT);
    }

    CpSseClient(
            String baseUrl,
            String agentId,
            String bearer,
            CpStore store,
            CpConsumer consumer,
            Reporter reporter,
            Clock clock,
            Duration idleTimeout) {
        this(baseUrl, agentId, bearer, store, consumer, reporter, clock, idleTimeout, null);
    }

    CpSseClient(
            String baseUrl,
            String agentId,
            String bearer,
            CpStore store,
            CpConsumer consumer,
            Reporter reporter,
            Clock clock,
            Duration idleTimeout,
            Sleeper sleeper) {
        this.baseUrl = baseUrl;
        this.agentId = agentId;
        this.bearer = bearer;
        this.store = store;
        this.consumer = consumer;
        this.reporter = reporter;
        this.clock = clock;
        this.idleTimeout = idleTimeout;
        this.sleeper = sleeper != null ? sleeper : this::sleep;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        // Pin HTTP/1.1: the default client offers an h2c upgrade on plain
                        // HTTP and the control plane logs it as an unsupported upgrade.
                        .version(HttpClient.Version.HTTP_1_1)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    /** Outcome of one {@code /events} connection: frames applied, and any line seen. */
    record StreamOutcome(int frames, boolean active) {}

    /**
     * Split-credential form: the stream spends only the command bearer; the console-read bearer
     * belongs to the server-side ConsoleLogPush and never leaves with this client.
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
     * poison frame that fails contract parsing is logged once as one line without a stack trace
     * (repeats rate-limited) and retried with backoff, never fatal: the cursor does not advance
     * past it, so it replays until an operator reconciles it. Keep-alive comments refresh liveness:
     * a connection that carried any line resumes without growing the backoff; only a totally silent
     * close backs off. Returns when stopped.
     */
    public void run(AtomicBoolean stop) {
        long backoff = BACKOFF_MIN_MILLIS;
        boolean needsSnapshot = true;
        while (!stop.get()) {
            try {
                if (needsSnapshot) {
                    resnapshot();
                    needsSnapshot = false;
                }
                StreamOutcome outcome = stream(stop);
                if (outcome.frames() > 0) {
                    backoff = BACKOFF_MIN_MILLIS;
                } else if (outcome.active()) {
                    backoff = BACKOFF_MIN_MILLIS;
                    if (!sleeper.sleep(backoff, stop)) return;
                } else {
                    if (!sleeper.sleep(backoff, stop)) return;
                    backoff = Math.min(backoff * 2, BACKOFF_MAX_MILLIS);
                }
            } catch (CpResyncException e) {
                reporter.warn("control-plane resync: " + e.getMessage(), e);
                needsSnapshot = true;
                if (!sleeper.sleep(backoff, stop)) return;
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MILLIS);
            } catch (CpContractException e) {
                warnContractOnce("control-plane frame rejected, retrying: " + e.getMessage());
                if (!sleeper.sleep(backoff, stop)) return;
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MILLIS);
            } catch (RuntimeException e) {
                reporter.warn("control-plane consumer failed, retrying", e);
                if (!sleeper.sleep(backoff, stop)) return;
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MILLIS);
            } catch (IOException | InterruptedException e) {
                if (stop.get() || e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                reporter.warn("control-plane stream failed, retrying", e);
                if (!sleeper.sleep(backoff, stop)) return;
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MILLIS);
            }
        }
    }

    /**
     * One line per rejected frame, without a stack trace: repeats of the same rejection stay silent
     * for {@link #CONTRACT_WARN_SUPPRESS_MILLIS}. A poison frame replays every backoff, so an
     * unthrottled warn would log a full stack ~120 times an hour. Stays at WARN level so a stuck
     * cursor remains visible to WARN-level operators.
     */
    void warnContractOnce(String message) {
        long now = clock.millis();
        if (message.equals(lastContractMessage)
                && now - lastContractWarnMillis < CONTRACT_WARN_SUPPRESS_MILLIS) {
            return;
        }
        lastContractMessage = message;
        lastContractWarnMillis = now;
        reporter.warn(message);
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
        // The socket read blocks, so it runs on a daemon reader while this thread watches the
        // idle deadline and stop(): silence past the watchdog closes the stream and reconnects,
        // and stop() returns promptly instead of hanging in a blocking read.
        BlockingQueue<Object> lines = new LinkedBlockingQueue<>();
        Object end = new Object();
        ExecutorService reader =
                Executors.newSingleThreadExecutor(
                        task -> {
                            Thread thread = new Thread(task, "blockfield-cp-sse");
                            thread.setDaemon(true);
                            return thread;
                        });
        InputStream in = response.body();
        reader.submit(
                () -> {
                    try (BufferedReader buffered =
                            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = buffered.readLine()) != null) lines.offer(line);
                    } catch (IOException e) {
                        // Closed below or dropped by the network; the watchdog decides.
                    } finally {
                        lines.offer(end);
                    }
                });
        try {
            return pump(stop, lines, end);
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                // Already closing; the reader notices and exits.
            }
            reader.shutdownNow();
        }
    }

    private StreamOutcome pump(AtomicBoolean stop, BlockingQueue<Object> lines, Object end)
            throws IOException, InterruptedException {
        int frames = 0;
        boolean active = false;
        long lastActivity = clock.millis();
        String event = "message";
        List<String> data = new ArrayList<>();
        while (!stop.get()) {
            Object item = lines.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            if (item == null) {
                if (clock.millis() - lastActivity >= idleTimeout.toMillis()) {
                    throw new IOException("control-plane SSE idle timeout, reconnecting");
                }
                continue;
            }
            lastActivity = clock.millis();
            if (item == end) break;
            String line = (String) item;
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
        return new StreamOutcome(frames, active);
    }

    /**
     * Post the current ack chain for one frame, then advance the cursor. A 409/stale answer drops
     * this chain and re-syncs from the snapshot on the next pass: the terminal decision stays
     * durable, so the replay re-posts it instead of rebuilding chained retries.
     */
    private boolean dispatch(String event, String data) throws IOException, InterruptedException {
        if (data.isEmpty()) return false;
        if ("resync".equals(event)) throw new CpResyncException("server asked to resync");
        if (!"control".equals(event)) return false;
        List<CpAck> acks = consumer.onFrame(data);
        for (CpAck ack : acks) postAck(ack);
        CpAck first = acks.get(0);
        store.saveCursorIfForward(first.epoch.toString(), first.sequence);
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
