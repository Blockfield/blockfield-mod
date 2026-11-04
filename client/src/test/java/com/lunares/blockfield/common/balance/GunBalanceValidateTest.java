package com.lunares.blockfield.common.balance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

class GunBalanceValidateTest {
    @Test
    void emptyOverridesValid() {
        assertTrue(GunBalanceSchema.validate(new JsonObject()).result().isPresent());
    }

    @Test
    void unknownFieldFailsWithPath() {
        JsonObject bad = new JsonObject();
        bad.addProperty("unknown_field_xyz", 1);
        var result = GunBalanceSchema.validate(bad);
        assertTrue(result.error().isPresent());
        assertTrue(result.error().get().message().contains("gunBalance.unknown_field_xyz"));
    }

    @Test
    void wrongTypeFailsWithPath() {
        JsonObject bad = JsonParser.parseString("{\"rpm\": \"fast\"}").getAsJsonObject();
        var result = GunBalanceSchema.validate(bad);
        assertTrue(result.error().isPresent());
        assertTrue(result.error().get().message().contains("gunBalance.rpm"));
    }

    @Test
    void bodyPartOutOfRangeFailsWithPath() {
        JsonObject bad =
                JsonParser.parseString("{\"body_part_multipliers\": {\"head\": 99}}")
                        .getAsJsonObject();
        var result = GunBalanceSchema.validate(bad);
        assertTrue(result.error().isPresent());
        assertTrue(
                result.error().get().message().contains("gunBalance.body_part_multipliers.head"));
    }

    @Test
    void codecRejectsBadOverrides() {
        JsonObject bad = new JsonObject();
        bad.addProperty("unknown_field_xyz", 1);
        var result = GunBalanceSchema.OVERRIDES_CODEC.parse(JsonOps.INSTANCE, bad);
        assertTrue(result.error().isPresent());
        assertTrue(result.error().get().message().contains("gunBalance.unknown_field_xyz"));
    }
}
