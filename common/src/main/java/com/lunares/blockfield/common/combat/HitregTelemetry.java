package com.lunares.blockfield.common.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-match hit-registration telemetry: a named counter registry, fixed-bucket histograms and a
 * bounded disputed-shot sample. World-free (no Minecraft types) so plain unit tests cover it; the
 * server wires its live combat counters into one instance per match and formats a single log line.
 *
 * <p>Histograms use fixed buckets and preallocated counts: recording never allocates. Quantiles
 * report the first bucket upper bound holding the rank; when the rank lands in the overflow bucket
 * the value clamps to the last finite bound and an overflow flag says it understates.
 */
public final class HitregTelemetry {
    /** Tick-duration bucket upper bounds in milliseconds; the last count is the overflow bucket. */
    public static final double[] TICK_MS_BUCKETS = {5, 10, 15, 20, 25, 30, 40, 50, 75, 100, 250};

    /** Ticket-age and rewind-depth bucket upper bounds in whole ticks; last count overflows. */
    public static final long[] TICK_COUNT_BUCKETS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 16};

    /** Counter registry cap; beyond it only {@code telemetryCounterEvictions} grows. */
    public static final int MAX_COUNTERS = 512;

    /** Bounded disputed-shot sample per match (first N kept). */
    public static final int MAX_DISPUTES = 8;

    private final long[] tickMs = new long[TICK_MS_BUCKETS.length + 1];
    private final long[] ageTicks = new long[TICK_COUNT_BUCKETS.length + 1];
    private final long[] rewindTicks = new long[TICK_COUNT_BUCKETS.length + 1];
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final List<Map<String, Object>> disputes = new ArrayList<>();
    private long shots;
    private long fallbackShots;

    public void recordTick(long nanos) {
        double ms = nanos / 1_000_000.0;
        int bucket = tickMs.length - 1;
        for (int i = 0; i < TICK_MS_BUCKETS.length; i++) {
            if (ms <= TICK_MS_BUCKETS[i]) {
                bucket = i;
                break;
            }
        }
        tickMs[bucket]++;
    }

    public void count(String key) {
        add(key, 1);
    }

    public void add(String key, long n) {
        if (key == null || n == 0) return;
        Long current = counters.get(key);
        long next = (current == null ? 0 : current) + n;
        if (current == null && counters.size() >= MAX_COUNTERS) {
            counters.merge("telemetryCounterEvictions", n, Long::sum);
            return;
        }
        counters.put(key, next);
    }

    public void addAll(Map<String, Long> deltas) {
        if (deltas == null) return;
        for (var entry : deltas.entrySet()) add(entry.getKey(), entry.getValue());
    }

    /**
     * One resolved shot: ticket kind is {@code exact}, {@code stale}, {@code omitted} or {@code
     * fallback}; result is {@code hit} or {@code miss}. Anything but {@code exact} counts toward
     * the fallback share.
     */
    public void noteShot(String ticket, long age, long rewind, String result) {
        shots++;
        if (!"exact".equals(ticket)) fallbackShots++;
        count("shots");
        count("shotsTicket:" + ticket);
        count("shotsResult:" + result);
        ageTicks[bucket(TICK_COUNT_BUCKETS, age)]++;
        rewindTicks[bucket(TICK_COUNT_BUCKETS, rewind)]++;
    }

    private static int bucket(long[] bounds, long value) {
        for (int i = 0; i < bounds.length; i++) {
            if (value <= bounds[i]) return i;
        }
        return bounds.length;
    }

    /**
     * Keeps the first {@link #MAX_DISPUTES} samples; later ones only bump {@code disputesDropped}.
     * Null samples, null entries and non-JSON values (NaN/Infinity, enums, UUIDs, ...) are dropped,
     * never thrown: telemetry must not fail match finalization, and the payload must stay valid
     * JSON.
     */
    public boolean noteDispute(Map<String, Object> sample) {
        if (disputes.size() >= MAX_DISPUTES) {
            count("disputesDropped");
            return false;
        }
        if (sample == null) {
            count("disputesDropped");
            return false;
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        for (var entry : sample.entrySet()) {
            Object value = jsonClosed(entry.getValue());
            if (entry.getKey() != null && value != null) kept.put(entry.getKey(), value);
        }
        if (kept.isEmpty()) {
            count("disputesDropped");
            return false;
        }
        disputes.add(kept);
        count("disputes");
        return true;
    }

    /**
     * JSON-closed copy of a dispute value: finite numbers, strings, booleans and nested lists and
     * maps of those; null for anything else (NaN, Infinity, enums, UUIDs, arbitrary objects).
     */
    private static Object jsonClosed(Object value) {
        if (value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Double d) return Double.isFinite(d) ? d : null;
        if (value instanceof Float f) return Float.isFinite(f) ? f : null;
        if (value instanceof Number) return value;
        if (value instanceof List<?> list) {
            List<Object> kept = new ArrayList<>();
            for (Object item : list) {
                Object clean = jsonClosed(item);
                if (clean == null) return null;
                kept.add(clean);
            }
            return kept;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> kept = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) return null;
                Object clean = jsonClosed(entry.getValue());
                if (clean == null) return null;
                kept.put((String) entry.getKey(), clean);
            }
            return kept;
        }
        return null;
    }

    public Report finish() {
        return new Report(
                Map.copyOf(counters),
                tickMs.clone(),
                ageTicks.clone(),
                rewindTicks.clone(),
                shots,
                fallbackShots,
                disputes.stream().map(Map::copyOf).toList());
    }

    /** Immutable per-match snapshot; {@link #toMap} holds only JSON-closed value types. */
    public record Report(
            Map<String, Long> counters,
            long[] tickMs,
            long[] ageTicks,
            long[] rewindTicks,
            long shots,
            long fallbackShots,
            List<Map<String, Object>> disputes) {
        public double tickMsP50() {
            return tickMsQuantile(0.50);
        }

        public double tickMsP99() {
            return tickMsQuantile(0.99);
        }

        /** True when the rank lands past the last finite bucket: the value understates the tail. */
        public boolean tickMsP99Overflow() {
            return quantileOverflow(tickMs, 0.99);
        }

        public long ageP50() {
            return (long) tickCountQuantile(TICK_COUNT_BUCKETS, ageTicks, 0.50);
        }

        public boolean ageP50Overflow() {
            return quantileOverflow(ageTicks, 0.50);
        }

        public long rewindP50() {
            return (long) tickCountQuantile(TICK_COUNT_BUCKETS, rewindTicks, 0.50);
        }

        public boolean rewindP50Overflow() {
            return quantileOverflow(rewindTicks, 0.50);
        }

        public double fallbackShare() {
            return shots == 0 ? 0 : (double) fallbackShots / shots;
        }

        private double tickMsQuantile(double q) {
            int bucket = quantileBucket(tickMs, q);
            if (bucket < 0) return 0;
            return TICK_MS_BUCKETS[Math.min(bucket, TICK_MS_BUCKETS.length - 1)];
        }

        private static double tickCountQuantile(long[] bounds, long[] counts, double q) {
            int bucket = quantileBucket(counts, q);
            if (bucket < 0) return 0;
            return bounds[Math.min(bucket, bounds.length - 1)];
        }

        /**
         * Index of the bucket holding the rank; the last index is the overflow bucket. A quantile
         * value clamps there to stay finite JSON, and the overflow flag says it understates.
         */
        private static int quantileBucket(long[] counts, double q) {
            long total = 0;
            for (long c : counts) total += c;
            if (total == 0) return -1;
            long rank = Math.max(1, (long) Math.ceil(total * q));
            long seen = 0;
            for (int i = 0; i < counts.length; i++) {
                seen += counts[i];
                if (seen >= rank) return i;
            }
            return counts.length - 1;
        }

        private static boolean quantileOverflow(long[] counts, double q) {
            int bucket = quantileBucket(counts, q);
            return bucket >= 0 && bucket == counts.length - 1 && counts[bucket] > 0;
        }

        /** Map of String/finite-Number/Boolean/List/Map only, for the stats JSON writer. */
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("shots", shots);
            out.put("fallbackShare", fallbackShare());
            out.put("tickMsP50", tickMsP50());
            out.put("tickMsP99", tickMsP99());
            out.put("tickMsP99Overflow", tickMsP99Overflow());
            out.put("ageTicksP50", ageP50());
            out.put("ageTicksP50Overflow", ageP50Overflow());
            out.put("rewindTicksP50", rewindP50());
            out.put("rewindTicksP50Overflow", rewindP50Overflow());
            out.put("counters", new LinkedHashMap<>(counters));
            out.put("tickMsBuckets", buckets(TICK_MS_BUCKETS, tickMs));
            out.put("ageTickBuckets", buckets(TICK_COUNT_BUCKETS, ageTicks));
            out.put("rewindTickBuckets", buckets(TICK_COUNT_BUCKETS, rewindTicks));
            out.put("disputes", new ArrayList<>(disputes));
            return out;
        }

        private static List<Map<String, Object>> buckets(double[] bounds, long[] counts) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < counts.length; i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("le", i < bounds.length ? bounds[i] : "inf");
                row.put("count", counts[i]);
                rows.add(row);
            }
            return rows;
        }

        private static List<Map<String, Object>> buckets(long[] bounds, long[] counts) {
            double[] asDouble = new double[bounds.length];
            for (int i = 0; i < bounds.length; i++) asDouble[i] = bounds[i];
            return buckets(asDouble, counts);
        }

        /** The one log line per match: every counter, no per-event logging. */
        public String formatLine(String matchId) {
            StringBuilder countersText = new StringBuilder("{");
            boolean first = true;
            for (var entry : new java.util.TreeMap<>(counters).entrySet()) {
                if (!first) countersText.append(',');
                first = false;
                countersText.append(entry.getKey()).append('=').append(entry.getValue());
            }
            countersText.append('}');
            return String.format(
                    Locale.ROOT,
                    "hitreg match=%s shots=%d fallbackShare=%.3f tickMsP50=%.1f tickMsP99=%s"
                            + " ageP50=%s rewindP50=%s counters=%s disputes=%d",
                    matchId,
                    shots,
                    fallbackShare(),
                    tickMsP50(),
                    tickMsP99Overflow()
                            ? ">" + TICK_MS_BUCKETS[TICK_MS_BUCKETS.length - 1]
                            : String.format("%.1f", tickMsP99()),
                    ageP50Overflow()
                            ? ">" + TICK_COUNT_BUCKETS[TICK_COUNT_BUCKETS.length - 1]
                            : Long.toString(ageP50()),
                    rewindP50Overflow()
                            ? ">" + TICK_COUNT_BUCKETS[TICK_COUNT_BUCKETS.length - 1]
                            : Long.toString(rewindP50()),
                    countersText,
                    disputes.size());
        }
    }
}
