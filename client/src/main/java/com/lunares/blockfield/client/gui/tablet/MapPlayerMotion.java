package com.lunares.blockfield.client.gui.tablet;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authorized markers; smoothing never discovers extra players. Times are monotonic
 * milliseconds.
 */
public final class MapPlayerMotion {
    public record Position(UUID id, double x, double z, boolean enemy) {}

    private record Motion(Position from, Position to) {}

    /** A delivery is one roster: bound the per-packet work a malformed packet can cause. */
    static final int MAX_ENTRIES = 256;

    private final Map<UUID, Motion> players = new LinkedHashMap<>();
    private String dimension = "", mapId = "";
    private long receivedAt;

    public void clear() {
        players.clear();
        dimension = "";
        mapId = "";
    }

    public void update(JsonObject map, long now) {
        if (!matches(map) || now - receivedAt > 1500) clear();
        var next = new LinkedHashMap<UUID, Motion>();
        for (Position to : read(map)) {
            Motion old = players.get(to.id());
            Position from = old == null ? to : interpolate(old, now);
            // Teleports, respawns and visibility changes must not sweep markers across the
            // battlefield.
            if (from.enemy() != to.enemy() || Math.hypot(to.x() - from.x(), to.z() - from.z()) > 64)
                from = to;
            next.put(to.id(), new Motion(from, to));
        }
        players.clear();
        players.putAll(next);
        dimension = text(map, "dimension");
        mapId = text(map, "id");
        receivedAt = now;
    }

    public List<Position> positions(JsonObject map, long now) {
        if (!matches(map) || now - receivedAt > 1500) return read(map);
        return players.values().stream().map(motion -> interpolate(motion, now)).toList();
    }

    private boolean matches(JsonObject map) {
        return map != null
                && text(map, "dimension").equals(dimension)
                && text(map, "id").equals(mapId);
    }

    /** Missing or non-string server fields read as empty, never throw. */
    private static String text(JsonObject map, String key) {
        return map.has(key) && map.get(key).isJsonPrimitive() ? map.get(key).getAsString() : "";
    }

    private Position interpolate(Motion motion, long now) {
        double fraction = Math.clamp((now - receivedAt) / 200d, 0d, 1d);
        Position from = motion.from(), to = motion.to();
        return new Position(
                to.id(),
                from.x() + (to.x() - from.x()) * fraction,
                from.z() + (to.z() - from.z()) * fraction,
                to.enemy());
    }

    static List<Position> read(JsonObject map) {
        List<Position> result = new ArrayList<>();
        for (String key : List.of("teammates", "revealedEnemies")) {
            if (!map.has(key) || !map.get(key).isJsonArray()) continue;
            for (var entry : map.getAsJsonArray(key)) {
                if (result.size() >= MAX_ENTRIES) return result;
                if (!entry.isJsonObject()) continue;
                JsonObject other = entry.getAsJsonObject();
                if (!other.has("x") || !other.has("z")) continue;
                UUID id = null;
                if (other.has("uuid")) {
                    try {
                        id = UUID.fromString(other.get("uuid").getAsString());
                    } catch (IllegalArgumentException | UnsupportedOperationException bad) {
                        continue;
                    }
                }
                double x, z;
                try {
                    x = other.get("x").getAsDouble();
                    z = other.get("z").getAsDouble();
                } catch (NumberFormatException | UnsupportedOperationException bad) {
                    continue;
                }
                if (Double.isFinite(x) && Double.isFinite(z))
                    result.add(new Position(id, x, z, key.equals("revealedEnemies")));
            }
        }
        return result;
    }
}
