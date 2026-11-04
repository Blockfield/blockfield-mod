package com.lunares.blockfield.network.packet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Single parsing entry for UI JSON payloads: wire strings become records here, readers use the
 * null-safe accessors instead of raw Gson (same values and defaults for valid payloads).
 */
public final class UiJson {
    private UiJson() {}

    /** Expected kinds for known envelope keys; unlisted keys pass through untouched. */
    public enum Kind {
        BOOLEAN,
        STRING,
        NUMBER,
        ARRAY,
        OBJECT,
        ANY
    }

    /** Strict parse: malformed JSON or a non-object root throws, like the old call sites. */
    public static JsonObject parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) throw new IllegalArgumentException("Expected JSON object");
        return root.getAsJsonObject();
    }

    /** Wire-decode validation: the parsed object, or null when the payload is malformed. */
    static JsonObject decodeValid(String raw, Codec<JsonObject> codec) {
        try {
            JsonObject root = parse(raw);
            return codec.parse(JsonOps.INSTANCE, root).result().isPresent() ? root : null;
        } catch (RuntimeException bad) {
            return null;
        }
    }

    public static String str(JsonObject o, String key, String def) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException bad) {
            return def;
        }
    }

    public static int integer(JsonObject o, String key, int def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsInt();
        } catch (RuntimeException bad) {
            return def;
        }
    }

    public static double decimal(JsonObject o, String key, double def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            double value = e.getAsDouble();
            return Double.isFinite(value) ? value : def;
        } catch (RuntimeException bad) {
            return def;
        }
    }

    /** Null when the element is not an array. */
    public static JsonArray asArray(JsonElement e) {
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    /** Null when absent or not an object (callers keep the old skip/guard behavior). */
    public static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** Empty array when absent or not an array (matches the old MenuScreen helper). */
    public static JsonArray arr(JsonObject o, String key) {
        JsonArray a = arrOrNull(o, key);
        return a == null ? new JsonArray() : a;
    }

    /** Null when absent or not an array (callers keep the old null-tolerance). */
    public static JsonArray arrOrNull(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    /** Null when the element is not an object (replaces isJsonObject/getAsJsonObject pairs). */
    public static JsonObject asObject(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** Object elements of an array, skipping anything else (same as the old guarded loops). */
    public static List<JsonObject> objects(JsonArray a) {
        List<JsonObject> result = new ArrayList<>();
        if (a == null) return result;
        for (JsonElement e : a) {
            JsonObject o = asObject(e);
            if (o != null) result.add(o);
        }
        return result;
    }

    static Optional<String> problems(JsonObject o, Map<String, Kind> keys) {
        for (var entry : keys.entrySet()) {
            JsonElement e = o.get(entry.getKey());
            if (e == null || e.isJsonNull()) continue;
            boolean ok =
                    switch (entry.getValue()) {
                        case BOOLEAN -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean();
                        case STRING -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
                        case NUMBER -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
                        case ARRAY -> e.isJsonArray();
                        case OBJECT -> e.isJsonObject();
                        case ANY -> true;
                    };
            if (!ok) return Optional.of(entry.getKey() + ": expected " + entry.getValue());
        }
        return Optional.empty();
    }

    /** Validation codec: any JSON object passes structurally, known keys must match kinds. */
    public static Codec<JsonObject> codec(Map<String, Kind> keys) {
        return validatingCodec(root -> problems(root, keys));
    }

    /** Validation codec around an arbitrary object check (field paths in the error message). */
    public static Codec<JsonObject> validatingCodec(Function<JsonObject, Optional<String>> check) {
        return Codec.PASSTHROUGH.flatXmap(
                dyn -> {
                    JsonElement root;
                    try {
                        root = dyn.convert(JsonOps.INSTANCE).getValue();
                    } catch (RuntimeException bad) {
                        return DataResult.error(() -> "expected JSON: " + bad.getMessage());
                    }
                    if (!root.isJsonObject()) return DataResult.error(() -> "expected JSON object");
                    JsonObject o = root.getAsJsonObject();
                    Optional<String> problem = check.apply(o);
                    if (problem.isPresent()) return DataResult.error(problem.get()::toString);
                    return DataResult.success(o);
                },
                o -> DataResult.success(new Dynamic<>(JsonOps.INSTANCE, o)));
    }
}
