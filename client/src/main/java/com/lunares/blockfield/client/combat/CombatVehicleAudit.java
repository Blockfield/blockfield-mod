package com.lunares.blockfield.client.combat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Refuse a fork subclass that replaces an audited interpolation or angle writer. */
final class CombatVehicleAudit {
    private static final String BASE =
            "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity";
    private static final java.util.Set<String> FIELD_SETTER_OVERRIDES =
            java.util.Set.of(
                    "com.atsuishio.superbwarfare.entity.vehicle.Ju87Entity",
                    "com.atsuishio.superbwarfare.entity.vehicle.Ztz99aEntity");
    private static final Map<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

    private CombatVehicleAudit() {}

    static boolean supported(Class<?> type) {
        return CACHE.computeIfAbsent(
                type,
                candidate -> {
                    for (Class<?> c = candidate;
                            c != null && !c.getName().equals(BASE);
                            c = c.getSuperclass()) {
                        for (var method : c.getDeclaredMethods()) {
                            if (method.getName().equals("handleClientSync")
                                    || java.util.Arrays.equals(
                                            method.getParameterTypes(),
                                            new Class<?>[] {
                                                double.class,
                                                double.class,
                                                double.class,
                                                float.class,
                                                float.class,
                                                int.class
                                            })
                                    || (method.getName()
                                                    .matches(
                                                            "set(Roll|PrevRoll|Turret[XY]RotO?|Gun[XY]RotO?)")
                                            && !FIELD_SETTER_OVERRIDES.contains(c.getName())))
                                return false;
                        }
                    }
                    return true;
                });
    }
}
