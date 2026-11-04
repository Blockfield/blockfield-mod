package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

/**
 * Proves a Blockfield production mixin observably changes its target versus the untransformed
 * expectation (ADR4 §9 proof fixture (a)).
 *
 * <p>{@code CombatLivingInterpolationMixin} merges {@code blockfield$clearInterpolation} into every
 * {@code LivingEntity}: it zeroes the client interpolation counters so a driven remote player only
 * moves at the main-frame write. Untransformed, that method does not exist and the counters keep
 * whatever the last native tick left. This fixture sets the counters, invokes the merged method and
 * asserts the reset; the companion negative fixture fails if the merge silently stops applying.
 */
class BlockfieldMixinOracleFixture {
    @Test
    void drivenInterpolationResetClearsCounters() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "driven-interpolation-reset",
                () -> {
                    var entity = mock(LivingEntity.class, org.mockito.Mockito.CALLS_REAL_METHODS);
                    setCounter(entity, "lerpSteps", 3);
                    setCounter(entity, "lerpHeadSteps", 3);
                    entity.getClass().getMethod("blockfield$clearInterpolation").invoke(entity);
                    var result = new LinkedHashMap<String, Object>();
                    result.put("lerpSteps", getCounter(entity, "lerpSteps"));
                    result.put("lerpHeadSteps", getCounter(entity, "lerpHeadSteps"));
                    assertEquals(0, getCounter(entity, "lerpSteps"));
                    assertEquals(0, getCounter(entity, "lerpHeadSteps"));
                    return result;
                });
    }

    /**
     * Negative test (ADR4 §9 proof fixture (d)): fails if the required Blockfield mixin silently
     * stops applying. The injected method exists only on transformed classes; on an untransformed
     * JVM this lookup throws and the oracle refuses to pass.
     */
    @Test
    void requiredBlockfieldMixinIsApplied() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "required-mixin-negative-guard",
                () -> {
                    var method =
                            LivingEntity.class.getDeclaredMethod("blockfield$clearInterpolation");
                    com.lunares.blockfield.oracle.OracleTransformAudit.requireConfig(
                            "blockfield-client.mixins.json");
                    org.junit.jupiter.api.Assertions.assertThrows(
                            AssertionError.class,
                            () ->
                                    com.lunares.blockfield.oracle.OracleTransformAudit
                                            .requireConfig("deliberately-missing.mixins.json"));
                    var result = new LinkedHashMap<String, Object>();
                    result.put("method", method.toString());
                    result.put("declaringClass", method.getDeclaringClass().getName());
                    return result;
                });
    }

    private static void setCounter(LivingEntity entity, String field, int value) throws Exception {
        Field counter =
                com.lunares.blockfield.oracle.OracleReflection.field(
                        LivingEntity.class, field, "I");
        counter.setAccessible(true);
        counter.setInt(entity, value);
    }

    private static int getCounter(LivingEntity entity, String field) throws Exception {
        Field counter =
                com.lunares.blockfield.oracle.OracleReflection.field(
                        LivingEntity.class, field, "I");
        counter.setAccessible(true);
        return counter.getInt(entity);
    }
}
