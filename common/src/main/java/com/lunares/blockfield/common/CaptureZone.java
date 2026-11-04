package com.lunares.blockfield.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * A capture point's zone: a union of boxes in inclusive block coordinates, so it can follow a
 * street and a courtyard, a stepped square or an L-shaped building and give neither team a
 * shortcut. Map JSON of a point:
 *
 * <ul>
 *   <li>{@code "zone": [{minX, minY, minZ, maxX, maxY, maxZ}, ...]} -- any shape;
 *   <li>{@code "aabb": {...}} -- the older single box;
 *   <li>neither: {@code x ± radius} (6), {@code y - height.min .. y + height.max} (3 and 5), {@code
 *       z ± radius}.
 * </ul>
 *
 * The server tests players against it, the workshop edits and draws it, the client gets the boxes
 * in X/Z ({@code bs} in the campaign UI packet, {@code boxes} in workshop marks).
 */
public record CaptureZone(List<Box> boxes) {
    public static final int DEFAULT_RADIUS = 6, DEFAULT_BELOW = 3, DEFAULT_ABOVE = 5;

    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Box {
            if (minX > maxX) {
                int t = minX;
                minX = maxX;
                maxX = t;
            }
            if (minY > maxY) {
                int t = minY;
                minY = maxY;
                maxY = t;
            }
            if (minZ > maxZ) {
                int t = minZ;
                minZ = maxZ;
                maxZ = t;
            }
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        static Box of(JsonObject o) {
            return new Box(
                    o.get("minX").getAsInt(),
                    o.get("minY").getAsInt(),
                    o.get("minZ").getAsInt(),
                    o.get("maxX").getAsInt(),
                    o.get("maxY").getAsInt(),
                    o.get("maxZ").getAsInt());
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("minX", minX);
            o.addProperty("minY", minY);
            o.addProperty("minZ", minZ);
            o.addProperty("maxX", maxX);
            o.addProperty("maxY", maxY);
            o.addProperty("maxZ", maxZ);
            return o;
        }

        Box shifted(int dx, int dy, int dz) {
            return new Box(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
        }
    }

    public CaptureZone {
        if (boxes.isEmpty()) throw new IllegalArgumentException("capture zone without boxes");
        boxes = List.copyOf(boxes);
    }

    /**
     * Zone of a point JSON; {@code point} may be null (tests): the default box around (x, y, z).
     */
    public static CaptureZone of(JsonObject point, int x, int y, int z) {
        if (point != null
                && point.has("zone")
                && point.get("zone").isJsonArray()
                && !point.getAsJsonArray("zone").isEmpty()) {
            List<Box> boxes = new ArrayList<>();
            for (JsonElement e : point.getAsJsonArray("zone"))
                boxes.add(Box.of(e.getAsJsonObject()));
            return new CaptureZone(boxes);
        }
        if (point != null && point.has("aabb"))
            return new CaptureZone(List.of(Box.of(point.getAsJsonObject("aabb"))));
        int radius =
                point != null && point.has("radius")
                        ? point.get("radius").getAsInt()
                        : DEFAULT_RADIUS;
        int below = DEFAULT_BELOW, above = DEFAULT_ABOVE;
        if (point != null && point.has("height")) {
            JsonObject height = point.getAsJsonObject("height");
            below = height.get("min").getAsInt();
            above = height.get("max").getAsInt();
        }
        return new CaptureZone(
                List.of(
                        new Box(
                                x - radius,
                                y - below,
                                z - radius,
                                x + radius,
                                y + above,
                                z + radius)));
    }

    public static CaptureZone of(JsonObject point) {
        return of(
                point,
                point.get("x").getAsInt(),
                point.get("y").getAsInt(),
                point.get("z").getAsInt());
    }

    public boolean contains(int x, int y, int z) {
        for (Box box : boxes) if (box.contains(x, y, z)) return true;
        return false;
    }

    /**
     * Bounding rectangle {@code [minX, minZ, maxX, maxZ]}: what clients without {@code bs} draw and
     * test.
     */
    public int[] bounds2d() {
        int minX = Integer.MAX_VALUE,
                minZ = Integer.MAX_VALUE,
                maxX = Integer.MIN_VALUE,
                maxZ = Integer.MIN_VALUE;
        for (Box b : boxes) {
            minX = Math.min(minX, b.minX);
            minZ = Math.min(minZ, b.minZ);
            maxX = Math.max(maxX, b.maxX);
            maxZ = Math.max(maxZ, b.maxZ);
        }
        return new int[] {minX, minZ, maxX, maxZ};
    }

    /** {@code [[minX, minZ, maxX, maxZ], ...]} for the client. */
    public JsonArray boxes2d() {
        JsonArray out = new JsonArray();
        for (Box b : boxes) {
            JsonArray a = new JsonArray();
            a.add(b.minX);
            a.add(b.minZ);
            a.add(b.maxX);
            a.add(b.maxZ);
            out.add(a);
        }
        return out;
    }

    // ------------------------------------------------------------ editing a point JSON (workshop,
    // map tools)

    /** Writes the zone as {@code zone} and drops the older keys it replaces. */
    public static void write(JsonObject point, List<Box> boxes) {
        JsonArray zone = new JsonArray();
        for (Box b : boxes) zone.add(b.toJson());
        point.add("zone", zone);
        point.remove("aabb");
        point.remove("radius");
        point.remove("height");
    }

    /** Adds a box to the point's current zone (the default box too, if the point had none). */
    public static void add(JsonObject point, Box box) {
        List<Box> boxes = new ArrayList<>(of(point).boxes());
        boxes.add(box);
        write(point, boxes);
    }

    /** Back to the default box around the point. */
    public static void reset(JsonObject point) {
        point.remove("zone");
        point.remove("aabb");
        point.remove("radius");
        point.remove("height");
    }

    /** Moves an explicit zone with its point; a default zone follows the point by itself. */
    public static void shift(JsonObject point, int dx, int dy, int dz) {
        if (point.has("zone") && point.get("zone").isJsonArray()) {
            JsonArray zone = point.getAsJsonArray("zone");
            for (int i = 0; i < zone.size(); i++)
                zone.set(i, Box.of(zone.get(i).getAsJsonObject()).shifted(dx, dy, dz).toJson());
        } else if (point.has("aabb")) {
            point.add("aabb", Box.of(point.getAsJsonObject("aabb")).shifted(dx, dy, dz).toJson());
        }
    }
}
