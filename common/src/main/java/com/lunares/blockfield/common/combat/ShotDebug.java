package com.lunares.blockfield.common.combat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Opt-in evidence only: native shot timestamps join the two processes without a new protocol. */
public final class ShotDebug {
    private static final Gson JSON =
            new com.google.gson.GsonBuilder()
                    .registerTypeAdapter(
                            net.minecraft.world.phys.Vec3.class,
                            (com.google.gson.JsonSerializer<net.minecraft.world.phys.Vec3>)
                                    (value, type, context) -> {
                                        var out = new com.google.gson.JsonArray();
                                        out.add(value.x);
                                        out.add(value.y);
                                        out.add(value.z);
                                        return out;
                                    })
                    .create();
    private static final int SHOTS = 128, EVENTS = 128, BINDINGS = 1024;
    private static volatile boolean enabled = Boolean.getBoolean("blockfield.shotDebug");
    private static final LinkedHashMap<String, List<JsonObject>> records = new LinkedHashMap<>();
    private static final IdentityHashMap<Object, String> bindings = new IdentityHashMap<>();
    private static final ArrayDeque<Object> order = new ArrayDeque<>();
    private static long dropped;

    private ShotDebug() {}

    public static boolean enabled() {
        return enabled;
    }

    public static synchronized void arm(boolean on) {
        enabled = on;
        clear();
    }

    public static synchronized void clear() {
        records.clear();
        bindings.clear();
        order.clear();
        dropped = 0;
    }

    /** TaCZ's timestamp is copied unchanged by the scheduled send and server handler. */
    public static String id(UUID shooter, long timestamp) {
        return shooter + "/" + timestamp;
    }

    public static synchronized void bind(Object carrier, String id) {
        if (!enabled || carrier == null || id == null || bindings.containsKey(carrier)) return;
        bindings.put(carrier, id);
        order.addLast(carrier);
        while (order.size() > BINDINGS) bindings.remove(order.removeFirst());
    }

    public static synchronized String bound(Object carrier) {
        return enabled ? bindings.get(carrier) : null;
    }

    public static synchronized void record(String id, String stage, Object... fields) {
        if (!enabled || id == null) return;
        if (fields.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs");
        var row = new JsonObject();
        row.addProperty("shotId", id);
        row.addProperty("stage", stage);
        row.addProperty("wallMs", System.currentTimeMillis());
        row.addProperty("monotonicNs", System.nanoTime());
        for (int i = 0; i < fields.length; i += 2)
            row.add((String) fields[i], JSON.toJsonTree(fields[i + 1]));
        var shot = records.computeIfAbsent(id, ignored -> new ArrayList<>());
        if (shot.size() < EVENTS) shot.add(row);
        else dropped++;
        while (records.size() > SHOTS) {
            records.remove(records.keySet().iterator().next());
            dropped++;
        }
    }

    public static synchronized java.util.Map<String, Object> snapshot() {
        var rows = new ArrayList<JsonObject>();
        records.values().forEach(shot -> shot.forEach(row -> rows.add(row.deepCopy())));
        return java.util.Map.of("enabled", enabled, "dropped", dropped, "records", rows);
    }
}
