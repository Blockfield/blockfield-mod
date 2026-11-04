package com.lunares.blockfield.common.balance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;
import com.lunares.blockfield.network.packet.UiJson;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Strict override types follow the pinned TaCZ POJOs, including absent optional fields. */
public final class GunBalanceSchema {
    public static final String BODY_PART_MULTIPLIERS = "body_part_multipliers";
    public static final Set<String> BODY_PARTS =
            Set.of("head", "torso", "left_arm", "right_arm", "left_leg", "right_leg");
    private static final Set<String> NULLABLE =
            Set.of(
                    "extended_mag_ammo_amount",
                    "bullet.extra_damage",
                    "bullet.explosion",
                    "heat",
                    "inaccuracy",
                    "recoil.pitch",
                    "recoil.yaw",
                    "melee.default",
                    "script",
                    "script_param",
                    "charging",
                    "charging.*",
                    "fire_mode_adjust.*",
                    "exclusive_attachments.*.melee");

    private GunBalanceSchema() {}

    /** Codec validation for kit/balance overrides; errors carry the field path, never throw. */
    public static final Codec<JsonObject> OVERRIDES_CODEC =
            UiJson.validatingCodec(GunBalanceSchema::problems);

    /**
     * Non-throwing validation for kit/balance JSON: success, or the first violation with its field
     * path (same checks as {@link #merge}, without applying anything).
     */
    public static DataResult<JsonObject> validate(JsonObject overrides) {
        Optional<String> problem = problems(overrides);
        if (problem.isPresent()) return DataResult.error(problem.get()::toString);
        return DataResult.success(overrides);
    }

    private static Optional<String> problems(JsonObject overrides) {
        try {
            validate(nativeOverrides(overrides), GunData.class, "");
            return Optional.empty();
        } catch (IllegalArgumentException bad) {
            return Optional.of(bad.getMessage());
        }
    }

    public static JsonObject merge(JsonObject baseline, JsonObject overrides) {
        overrides = nativeOverrides(overrides);
        validate(overrides, GunData.class, "");
        JsonObject result = baseline.deepCopy();
        mergeInto(result, overrides);
        return result;
    }

    public static JsonObject complete(GunData data, JsonObject raw) {
        JsonObject result =
                canonicalMapKeys(CommonAssetsManager.GSON.toJsonTree(data), GunData.class)
                        .getAsJsonObject();
        // Keep legal wire spellings such as distance="infinite" while filling POJO defaults.
        mergeInto(result, canonicalMapKeys(raw, GunData.class).getAsJsonObject());
        return result;
    }

    private static JsonElement canonicalMapKeys(JsonElement value, Type type) {
        if (value.isJsonArray()) {
            Type item;
            if (type instanceof ParameterizedType generic)
                item = generic.getActualTypeArguments()[0];
            else if (type instanceof Class<?> clazz && clazz.isArray())
                item = clazz.componentType();
            else return value.deepCopy();
            JsonArray result = new JsonArray();
            for (JsonElement element : value.getAsJsonArray())
                result.add(canonicalMapKeys(element, item));
            return result;
        }
        if (!value.isJsonObject() || type == Object.class) return value.deepCopy();
        JsonObject result = new JsonObject();
        if (type instanceof ParameterizedType generic
                && Map.class.isAssignableFrom((Class<?>) generic.getRawType())) {
            Type[] args = generic.getActualTypeArguments();
            value.getAsJsonObject()
                    .entrySet()
                    .forEach(
                            entry -> {
                                String key = entry.getKey();
                                if (args[0] instanceof Class<?> clazz && clazz.isEnum()) {
                                    // Gson writes enum map keys with toString(), ignoring
                                    // SerializedName; the catalog must expose one wire spelling.
                                    Object parsed =
                                            CommonAssetsManager.GSON.fromJson(
                                                    new JsonPrimitive(key), clazz);
                                    if (parsed != null)
                                        key =
                                                CommonAssetsManager.GSON
                                                        .toJsonTree(parsed)
                                                        .getAsString();
                                }
                                result.add(key, canonicalMapKeys(entry.getValue(), args[1]));
                            });
        } else {
            Map<String, Field> specs = type instanceof Class<?> clazz ? fields(clazz) : Map.of();
            value.getAsJsonObject()
                    .entrySet()
                    .forEach(
                            entry -> {
                                Field spec = specs.get(entry.getKey());
                                result.add(
                                        entry.getKey(),
                                        canonicalMapKeys(
                                                entry.getValue(),
                                                spec == null
                                                        ? Object.class
                                                        : spec.getGenericType()));
                            });
        }
        return result;
    }

    private static void mergeInto(JsonObject target, JsonObject overrides) {
        overrides
                .entrySet()
                .forEach(
                        entry -> {
                            JsonElement value = entry.getValue();
                            if (value.isJsonObject()
                                    && target.get(entry.getKey()) instanceof JsonObject object)
                                mergeInto(object, value.getAsJsonObject());
                            else target.add(entry.getKey(), value.deepCopy());
                        });
    }

    public static JsonObject metadata() {
        JsonObject result = describe(GunData.class, "");
        JsonObject body = new JsonObject();
        body.addProperty("type", "object");
        body.addProperty("nullable", false);
        body.addProperty("serverOnly", true);
        JsonObject parts = new JsonObject();
        for (String part : BODY_PARTS.stream().sorted().toList()) {
            JsonObject number = new JsonObject();
            number.addProperty("type", "number");
            number.addProperty("nullable", false);
            number.addProperty("min", 0);
            number.addProperty("max", 10);
            parts.add(part, number);
        }
        body.add("fields", parts);
        result.getAsJsonObject("fields").add(BODY_PART_MULTIPLIERS, body);
        return result;
    }

    /** Server metadata never enters GunData, shared-data alias comparisons or the client packet. */
    public static JsonObject nativeOverrides(JsonObject overrides) {
        bodyMultipliers(overrides);
        JsonObject result = overrides.deepCopy();
        result.remove(BODY_PART_MULTIPLIERS);
        return result;
    }

    public static JsonObject bodyMultipliers(JsonObject overrides) {
        JsonObject result = new JsonObject();
        BODY_PARTS.stream().sorted().forEach(part -> result.addProperty(part, 1f));
        if (!overrides.has(BODY_PART_MULTIPLIERS)) return result;
        JsonElement body = overrides.get(BODY_PART_MULTIPLIERS);
        require(body.isJsonObject(), BODY_PART_MULTIPLIERS, "expected object");
        for (var entry : body.getAsJsonObject().entrySet()) {
            String path = BODY_PART_MULTIPLIERS + "." + entry.getKey();
            require(BODY_PARTS.contains(entry.getKey()), path, "unknown body part");
            JsonElement value = entry.getValue();
            require(
                    value instanceof JsonPrimitive primitive && primitive.isNumber(),
                    path,
                    "expected number");
            double number = value.getAsDouble();
            require(
                    Double.isFinite(number) && number >= 0 && number <= 10,
                    path,
                    "expected finite number 0..10");
            result.addProperty(entry.getKey(), (float) number);
        }
        return result;
    }

    public static void validateBodyOnlyOverrides(JsonObject overrides) {
        bodyMultipliers(overrides);
        require(
                overrides.keySet().stream().allMatch(BODY_PART_MULTIPLIERS::equals),
                "",
                "item/throwable/melee/mounted weapons only accept body_part_multipliers");
    }

    private static Map<String, Field> fields(Class<?> type) {
        Map<String, Field> result = new LinkedHashMap<>();
        for (Field field : type.getDeclaredFields()) {
            SerializedName annotation = field.getAnnotation(SerializedName.class);
            if (annotation != null) result.put(annotation.value(), field);
        }
        return result;
    }

    private static void validate(JsonElement value, Type type, String path) {
        if (type == Object.class) {
            validateParameter(value, path, 0);
            return;
        }
        if (value.isJsonNull()) {
            require(NULLABLE.contains(path), path, "null is not allowed");
            return;
        }
        if (type instanceof ParameterizedType generic) {
            Type[] args = generic.getActualTypeArguments();
            if (Map.class.isAssignableFrom((Class<?>) generic.getRawType())) {
                require(value.isJsonObject(), path, "expected object");
                require(value.getAsJsonObject().size() <= 256, path, "too many entries");
                value.getAsJsonObject()
                        .entrySet()
                        .forEach(
                                entry -> {
                                    validateKey(entry.getKey(), args[0], path);
                                    validate(entry.getValue(), args[1], path + ".*");
                                });
            } else {
                require(value.isJsonArray(), path, "expected array");
                require(value.getAsJsonArray().size() <= 256, path, "too many entries");
                for (JsonElement item : value.getAsJsonArray())
                    validate(item, args[0], path + "[]");
            }
            return;
        }
        Class<?> clazz = (Class<?>) type;
        if (clazz.isArray()) {
            require(value.isJsonArray(), path, "expected array");
            require(value.getAsJsonArray().size() <= 256, path, "too many entries");
            for (JsonElement item : value.getAsJsonArray())
                validate(item, clazz.componentType(), path + "[]");
        } else if (clazz == boolean.class) {
            require(
                    value instanceof JsonPrimitive primitive && primitive.isBoolean(),
                    path,
                    "expected boolean");
        } else if (clazz.isPrimitive() || Number.class.isAssignableFrom(clazz)) {
            require(
                    value instanceof JsonPrimitive primitive && primitive.isNumber(),
                    path,
                    "expected number");
            double number = value.getAsDouble();
            double[] range = bounds(path);
            require(
                    Double.isFinite(number) && number >= range[0] && number <= range[1],
                    path,
                    "expected finite number " + range[0] + ".." + range[1]);
            if (clazz == int.class || clazz == long.class)
                require(number == Math.rint(number), path, "expected integer");
        } else if (clazz == String.class || clazz == ResourceLocation.class || clazz.isEnum()) {
            validateKey(string(value, path), clazz, path);
        } else if (clazz.getSimpleName().equals("Ignite")
                && value instanceof JsonPrimitive primitive
                && primitive.isBoolean()) {
            // TaCZ accepts both the legacy boolean and the entity/block object.
        } else {
            require(value.isJsonObject(), path, "expected object");
            Map<String, Field> specs = fields(clazz);
            value.getAsJsonObject()
                    .entrySet()
                    .forEach(
                            entry -> {
                                Field spec = specs.get(entry.getKey());
                                String child =
                                        path.isEmpty()
                                                ? entry.getKey()
                                                : path + "." + entry.getKey();
                                require(spec != null, child, "unknown TaCZ field");
                                if (child.endsWith("damage_adjust[].distance")
                                        && entry.getValue() instanceof JsonPrimitive primitive
                                        && primitive.isString())
                                    require(
                                            primitive.getAsString().equals("infinite"),
                                            child,
                                            "expected distance or infinite");
                                else validate(entry.getValue(), spec.getGenericType(), child);
                            });
        }
    }

    private static void validateKey(String value, Type type, String path) {
        require(value.length() <= 256 && !value.isBlank(), path, "invalid string");
        if (type == ResourceLocation.class)
            require(ResourceLocation.tryParse(value) != null, path, "invalid resource ID");
        else if (type instanceof Class<?> clazz && clazz.isEnum()) {
            Object parsed = CommonAssetsManager.GSON.fromJson(new JsonPrimitive(value), clazz);
            require(
                    parsed != null
                            && !Set.of("UNKNOWN", "NONE").contains(((Enum<?>) parsed).name()),
                    path,
                    "unknown enum value " + value);
        }
    }

    private static String string(JsonElement value, String path) {
        require(
                value instanceof JsonPrimitive primitive && primitive.isString(),
                path,
                "expected string");
        return value.getAsString();
    }

    private static void validateParameter(JsonElement value, String path, int depth) {
        require(depth <= 8, path, "script_param nesting exceeds 8");
        if (value.isJsonNull()) return;
        if (value instanceof JsonPrimitive primitive) {
            if (primitive.isNumber())
                require(
                        Double.isFinite(primitive.getAsDouble())
                                && Math.abs(primitive.getAsDouble()) <= 1_000_000,
                        path,
                        "invalid script number");
            if (primitive.isString())
                require(primitive.getAsString().length() <= 4096, path, "script string too long");
        } else if (value.isJsonObject()) {
            require(value.getAsJsonObject().size() <= 256, path, "too many script parameters");
            value.getAsJsonObject()
                    .entrySet()
                    .forEach(
                            entry -> {
                                validateKey(entry.getKey(), String.class, path);
                                validateParameter(
                                        entry.getValue(), path + "." + entry.getKey(), depth + 1);
                            });
        } else {
            require(value.getAsJsonArray().size() <= 256, path, "script array too long");
            value.getAsJsonArray().forEach(item -> validateParameter(item, path + "[]", depth + 1));
        }
    }

    private static double[] bounds(String path) {
        String leaf = path.substring(path.lastIndexOf('.') + 1).replace("[]", "");
        if (path.startsWith("fire_mode_adjust.")) return new double[] {-10000, 10000};
        if (path.endsWith(".*.weight")) return new double[] {-100, 100};
        if (path.contains(".effects[].time")) return new double[] {0, 12000};
        if (path.startsWith("movement_speed.")) return new double[] {-1, 10};
        if (leaf.equals("value")) return new double[] {-180, 180};
        return switch (leaf) {
            case "ammo_amount", "extended_mag_ammo_amount" -> new double[] {1, 1000};
            case "bullet_amount", "count", "pierce" -> new double[] {1, 128};
            case "rpm" -> new double[] {1, 1200};
            case "bpm" -> new double[] {1, 12000};
            case "bolt_feed_time" -> new double[] {-1, 60};
            case "tracer_count_interval" -> new double[] {-1, 1000};
            case "gravity", "knockback" -> new double[] {-100, 100};
            case "armor_ignore", "friction" -> new double[] {0, 1};
            case "max", "max_charge" -> new double[] {0.001, 100000};
            case "min_rpm_mod", "max_rpm_mod" -> new double[] {0.01, 10};
            case "extended_mag_level" -> new double[] {0, 3};
            case "amplifier" -> new double[] {0, 255};
            case "cooling_delay", "over_heat_time" -> new double[] {0, 300000};
            case "distance", "speed", "damage" -> new double[] {0, 10000};
            case "radius" -> new double[] {0, 64};
            case "fire_multiplier", "silence_multiplier" -> new double[] {0, 10};
            case "range_angle" -> new double[] {0, 360};
            case "draw_time",
                    "put_away_time",
                    "sprint_time",
                    "aim_time",
                    "bolt_action_time",
                    "life",
                    "cooldown",
                    "prep",
                    "empty",
                    "tactical",
                    "delay",
                    "time",
                    "min_interval" ->
                    new double[] {0, 60};
            default -> new double[] {0, 10000};
        };
    }

    private static JsonObject describe(Type type, String path) {
        JsonObject result = new JsonObject();
        result.addProperty("nullable", type == Object.class || NULLABLE.contains(path));
        if (path.endsWith("damage_adjust[].distance")) result.addProperty("acceptsInfinite", true);
        if (type instanceof ParameterizedType generic) {
            Type[] args = generic.getActualTypeArguments();
            boolean map = Map.class.isAssignableFrom((Class<?>) generic.getRawType());
            result.addProperty("type", map ? "map" : "array");
            if (map) result.add("keys", describe(args[0], path));
            result.add("items", describe(args[map ? 1 : 0], path + (map ? ".*" : "[]")));
        } else {
            Class<?> clazz = (Class<?>) type;
            if (clazz.isArray()) {
                result.addProperty("type", "array");
                result.add("items", describe(clazz.componentType(), path + "[]"));
            } else if (clazz == boolean.class) result.addProperty("type", "boolean");
            else if (clazz.isPrimitive() || Number.class.isAssignableFrom(clazz)) {
                result.addProperty(
                        "type", clazz == int.class || clazz == long.class ? "integer" : "number");
                double[] range = bounds(path);
                result.addProperty("min", range[0]);
                result.addProperty("max", range[1]);
            } else if (clazz == String.class || clazz == ResourceLocation.class || clazz.isEnum()) {
                result.addProperty("type", clazz == ResourceLocation.class ? "resource" : "string");
                if (clazz.isEnum()) {
                    JsonArray values = new JsonArray();
                    Arrays.stream(clazz.getEnumConstants())
                            .filter(
                                    value ->
                                            !Set.of("UNKNOWN", "NONE")
                                                    .contains(((Enum<?>) value).name()))
                            .forEach(
                                    value ->
                                            values.add(CommonAssetsManager.GSON.toJsonTree(value)));
                    result.add("values", values);
                }
            } else if (clazz == Object.class) result.addProperty("type", "json");
            else {
                result.addProperty("type", "object");
                JsonObject children = new JsonObject();
                fields(clazz)
                        .forEach(
                                (name, field) ->
                                        children.add(
                                                name,
                                                describe(
                                                        field.getGenericType(),
                                                        path.isEmpty()
                                                                ? name
                                                                : path + "." + name)));
                result.add("fields", children);
                if (clazz.getSimpleName().equals("Ignite"))
                    result.addProperty("acceptsBoolean", true);
            }
        }
        return result;
    }

    public static void require(boolean condition, String path, String message) {
        if (!condition)
            throw new IllegalArgumentException(
                    (path.isEmpty() ? "gunBalance" : "gunBalance." + path) + ": " + message);
    }
}
