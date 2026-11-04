package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.combat.VanillaDrivenScope;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * U2 vanilla/Blockfield native pins (ADR4 §§4.2/5.3): the exact native getter formulas the driven
 * path replaces run here as production-transformed bytecode. Every expectation is a hand-computed
 * literal; the point is proving the pinned formulas (attack wrap, swim endpoints, velocity order,
 * walk triple, driven override) rather than comparing two ports.
 */
class VanillaAnimSyncOracleFixture {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nativeAttackAnimWrapsNegativeDeltas() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "attack-anim-wrap",
                () -> {
                    var entity = mock(LivingEntity.class, CALLS_REAL_METHODS);
                    setFloat(entity, LivingEntity.class, "oAttackAnim", "F", 0.9f);
                    setFloat(entity, LivingEntity.class, "attackAnim", "F", 0.1f);
                    float wrapped = entity.getAttackAnim(0.5f);
                    // Wrapped (0.9 + 0.2 * 0.5 = 1.0), never the plain lerp 0.5.
                    assertEquals(1.0f, wrapped, 1e-6);
                    assertTrue(Math.abs(wrapped - 0.5f) > 0.4f, "wrap branch must run");
                    assertEquals(0.9f, entity.getAttackAnim(0), 0);
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    var result = new LinkedHashMap<String, Object>();
                    result.put("attack05", wrapped);
                    result.put("attackBits", Float.floatToRawIntBits(wrapped));
                    return result;
                });
    }

    @Test
    void nativeSwimAmountSamplesOldCurrentEndpoints() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "swim-amount-endpoints",
                () -> {
                    var entity = mock(LivingEntity.class, CALLS_REAL_METHODS);
                    setFloat(entity, LivingEntity.class, "swimAmountO", "F", 0.25f);
                    setFloat(entity, LivingEntity.class, "swimAmount", "F", 0.75f);
                    assertEquals(0.25f, entity.getSwimAmount(0), 0);
                    assertEquals(0.75f, entity.getSwimAmount(1), 0);
                    assertEquals(0.5f, entity.getSwimAmount(0.5f), 1e-7);
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    var result = new LinkedHashMap<String, Object>();
                    result.put("swim0", entity.getSwimAmount(0));
                    result.put("swim1", entity.getSwimAmount(1));
                    return result;
                });
    }

    @Test
    void nativeVelocityLerpKeepsPrevCurOrder() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "velocity-lerp-order",
                () -> {
                    var entity = mock(AbstractClientPlayer.class, CALLS_REAL_METHODS);
                    var prev = new Vec3(1, 2, 3);
                    var current = new Vec3(4, 6, 8);
                    setVec(entity, "deltaMovementOnPreviousTick", prev);
                    when(entity.getDeltaMovement()).thenReturn(current);
                    // Endpoints prove the roles: p = 0 is previous, p = 1 is current.
                    assertEquals(prev, entity.getDeltaMovementLerped(0));
                    assertEquals(current, entity.getDeltaMovementLerped(1));
                    assertEquals(new Vec3(2.5, 4, 5.5), entity.getDeltaMovementLerped(0.5f));
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    var result = new LinkedHashMap<String, Object>();
                    result.put("mid", entity.getDeltaMovementLerped(0.5f).toString());
                    return result;
                });
    }

    @Test
    void drivenVelocityOverrideIgnoresAmbientPartial() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "driven-velocity-override-ignores-ambient",
                () -> {
                    var entity = mock(AbstractClientPlayer.class, CALLS_REAL_METHODS);
                    var prev = new Vec3(0, -1, 0);
                    var current = new Vec3(0, -3, 4);
                    setVec(entity, "deltaMovementOnPreviousTick", new Vec3(99, 99, 99));
                    when(entity.getDeltaMovement()).thenReturn(new Vec3(-99, -99, -99));
                    int id = entity.getId();
                    // The mock subclass extends the transformed player class, so this call runs
                    // the real production override, not a stub.
                    VanillaDrivenScope.install(
                            id, new VanillaDrivenScope.DrivenFrame(prev, current, 0.25f));
                    try {
                        // p_R = 0.25: (0, -1.5, 1), whatever ambient p the renderer passes.
                        var expected = new Vec3(0, -1.5, 1);
                        assertEquals(expected, entity.getDeltaMovementLerped(0));
                        assertEquals(expected, entity.getDeltaMovementLerped(0.37f));
                        assertEquals(expected, entity.getDeltaMovementLerped(1));
                    } finally {
                        VanillaDrivenScope.clear(id);
                    }
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    var result = new LinkedHashMap<String, Object>();
                    result.put("entityId", id);
                    result.put("driven", new Vec3(0, -1.5, 1).toString());
                    return result;
                });
    }

    @Test
    void nativeWalkTripleMatchesAuthoritativeFormulas() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "walk-triple-native-formulas",
                () -> {
                    var walk = new WalkAnimationState();
                    walk.update(2.0f, 1.0f);
                    // update: speedOld = 0, speed = 0 + (2 - 0) * 1 = 2, position = 0 + 2 = 2.
                    assertEquals(2.0f, walk.position(), 0);
                    assertEquals(2.0f, walk.speed(), 0);
                    assertEquals(1.0f, walk.speed(0.5f), 1e-7);
                    // position(p) = position - speed * (1 - p): 2 - 2 * 0.5 = 1.
                    assertEquals(1.0f, walk.position(0.5f), 1e-7);
                    assertEquals(2.0f, walk.position(1.0f), 0);
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    var result = new LinkedHashMap<String, Object>();
                    result.put("position05", walk.position(0.5f));
                    result.put("speed05", walk.speed(0.5f));
                    return result;
                });
    }

    private static void setFloat(
            Object entity, Class<?> owner, String name, String descriptor, float value)
            throws Exception {
        Field field = OracleReflection.field(owner, name, descriptor);
        field.setAccessible(true);
        field.setFloat(entity, value);
    }

    private static void setVec(Object entity, String name, Vec3 value) throws Exception {
        Field field =
                OracleReflection.field(
                        AbstractClientPlayer.class, name, "Lnet/minecraft/world/phys/Vec3;");
        field.setAccessible(true);
        field.set(entity, value);
    }
}
