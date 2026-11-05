package com.lunares.blockfield.common.combat;

import java.util.List;

/**
 * Typed SBW vehicle dependency layout (ADR4 §4.5, fidelity P2-3; U6, moved to common by U8b so both
 * peers hash it into {@code compat.family.sbw}). One slot is a {@code (declaringClass, memberId,
 * descriptor, role)} read; {@link #baseSlots} is the complete pose-driving read set of every
 * production query U6 enables, pinned against bf24 bytecode. A transformed read-coverage test
 * rejects a supported query reading outside it; a new shipped class that is neither covered nor
 * explicitly listed fails that test instead of silently joining the exact cohort.
 */
public final class SbwLayouts {
    /** Pinned bf24 base vehicle class every production vehicle extends. */
    public static final String BASE_CLASS =
            "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity";

    /**
     * Seat transform names occurring in the pinned bf24 vehicle data (44 vehicle JSONs). {@code
     * Vehicle} and any unknown name resolve through the native {@code Default} entry ({@code
     * getOrDefault}); only the five registered families below have their own functions.
     */
    public static final List<String> SEAT_TRANSFORMS =
            List.of("Vehicle", "Barrel", "Turret", "WeaponStationBarrel", "WeaponStation");

    /** One manifest slot: where the value is read, its role, and why it is in/out. */
    public record Slot(
            String declaringClass, String member, String descriptor, AnimationTypes.Role role) {}

    private SbwLayouts() {}

    /**
     * Complete pose-driving read set for the enabled queries (direct seat pose, root seat direction
     * yaw, root orientation quaternion, passenger scale). Base yaw/pitch live in the placement
     * angle slots ({@code yRotO/yRot} = yaw, {@code xRotO/xRot} = pitch); origin in the placement
     * origins; roll, turret and gun pairs in {@link AnimationState.VehicleAngles}; seat identity
     * (entity ids, seat indices, generations) in {@link AnimationState.Sbw}; seat/vehicle
     * definitions in resource refs. Only the resource-data values below ride the generic reads:
     * everything else the projection consumes is already a first-class checkpoint field. The seat
     * direction query's named Matrix4d ({@code getTurretTransform} -> {@code
     * getVehicleTransformWithCustomPitch} -> {@code getVehicleYOffsetTransform}) also translates by
     * {@code getRotateOffsetHeight()} and {@code getTurretPos()}; a null turret position (the
     * native {@code hasTurret()} test) skips the turret translation and yaw, so its presence is a
     * slot of its own and {@code getTurretPos} is present exactly when it is true. Barrel and
     * weapon-station matrices additionally read their three nullable position getters; each has an
     * explicit derived {@code $present} null-test slot and an optional vector value.
     *
     * <p>Pinned against bf26 {@code 2a5e4371…} by the transitive read-closure test: the enabled
     * queries ({@code getTransformDirectionNoOrientation}, {@code
     * getRotationFromString(String,float)}, seat/pose/scale getters and the {@code
     * VectorTool.combineRotations*}/{@code VehicleVecUtils.getVehicle*Transform} bodies behind
     * them) read no other live entity field. Exclude rules with reason:
     *
     * <ul>
     *   <li>{@code weaponFiringState} map and the three {@code *Transform} lambda maps: mutable
     *       {@code Function} objects are never shipped, only resource refs (ADR4 §4.5).
     *   <li>{@code turretYRotLock} single float with no old/current pair: no driven query reads it
     *       (absent from the closure); a future reader needs a reviewed slot.
     *   <li>Rudder/wheel/track/flap/gear/propeller visual pairs, {@code pitchAngle}, {@code
     *       rollAngle}, {@code fakePitch/fakeRoll}, {@code recoilShake}, {@code absoluteSpeed},
     *       {@code deltaMovementO}/{@code positionO}/{@code prevMotion}: read by render-only
     *       animation paths outside the enabled seat queries, absent from the closure; a newly
     *       enabled query reading them needs a reviewed slot.
     *   <li>Interpolation bookkeeping ({@code interpolationSteps}, {@code xO/yO/zO} protected
     *       doubles): superseded by the authoritative R endpoint pairs.
     *   <li>Everything else on the class (health/entity-data, inventory, cooldowns, OBB caches,
     *       sounds, gun data, engine state): no read path from the enabled queries.
     * </ul>
     */
    public static List<Slot> baseSlots() {
        String base = BASE_CLASS;
        String scalar = "()F";
        return List.of(
                // Resource-data constants behind the selected queries (computed() vehicle data,
                // immutable within a resource generation).
                new Slot(base, "getTurretCustomPitch", scalar, AnimationTypes.Role.CONSTANT),
                new Slot(base, "getPassengerWeaponMinPitch", scalar, AnimationTypes.Role.CONSTANT),
                new Slot(base, "getPassengerWeaponMaxPitch", scalar, AnimationTypes.Role.CONSTANT),
                new Slot(base, "getPassengerRenderScale", scalar, AnimationTypes.Role.CONSTANT),
                new Slot(base, ROTATE_OFFSET_HEIGHT, "()D", AnimationTypes.Role.CONSTANT),
                new Slot(base, HAS_TURRET, "()Z", AnimationTypes.Role.CONSTANT),
                new Slot(base, TURRET_POS, VEC3_GETTER, AnimationTypes.Role.CONSTANT),
                new Slot(base, BARREL_POSITION + PRESENT, "()Z", AnimationTypes.Role.CONSTANT),
                new Slot(base, BARREL_POSITION, VEC3_GETTER, AnimationTypes.Role.CONSTANT),
                new Slot(
                        base,
                        WEAPON_STATION_POSITION + PRESENT,
                        "()Z",
                        AnimationTypes.Role.CONSTANT),
                new Slot(base, WEAPON_STATION_POSITION, VEC3_GETTER, AnimationTypes.Role.CONSTANT),
                new Slot(
                        base,
                        WEAPON_STATION_BARREL_POSITION + PRESENT,
                        "()Z",
                        AnimationTypes.Role.CONSTANT),
                new Slot(
                        base,
                        WEAPON_STATION_BARREL_POSITION,
                        VEC3_GETTER,
                        AnimationTypes.Role.CONSTANT));
    }

    public static final String ROTATE_OFFSET_HEIGHT = "getRotateOffsetHeight";
    public static final String HAS_TURRET = "hasTurret";
    public static final String TURRET_POS = "getTurretPos";
    public static final String BARREL_POSITION = "getBarrelPosition";
    public static final String WEAPON_STATION_POSITION = "getPassengerWeaponStationPosition";
    public static final String WEAPON_STATION_BARREL_POSITION =
            "getPassengerWeaponStationBarrelPosition";

    /** Derived null-test slots; bf24 declares all three position getters nullable. */
    public static final String PRESENT = "$present";

    /** A presence slot names its native vector getter, never a synthetic native method. */
    public static String presenceGetter(String member) {
        if (member.equals(HAS_TURRET)) return TURRET_POS;
        return member.endsWith(PRESENT)
                ? member.substring(0, member.length() - PRESENT.length())
                : null;
    }

    /** Missing or inconsistent presence/value pairs are unavailable, including an extra value. */
    public static boolean coherentPosition(
            List<AnimationState.VehicleRead> reads, String member, String presence) {
        var value = read(reads, member);
        return read(reads, presence) instanceof AnimationState.BooleanValue present
                && (present.value()
                        ? value instanceof AnimationState.VectorValue vector
                                && Double.isFinite(vector.value().x())
                                && Double.isFinite(vector.value().y())
                                && Double.isFinite(vector.value().z())
                        : value == null);
    }

    /** Pinned bf24 descriptor of a {@code Vec3} getter (production intermediary namespace). */
    public static final String VEC3_GETTER = "()Lnet/minecraft/class_243;";

    /** The slot of {@code member}; the descriptor every capture of it must carry. */
    public static Slot slot(String member) {
        for (Slot slot : baseSlots()) if (slot.member().equals(member)) return slot;
        throw new IllegalArgumentException("No SBW layout slot " + member);
    }

    /** The decoded value of {@code member}, or null when the reads omit it. */
    public static AnimationState.ReadValue read(
            List<AnimationState.VehicleRead> reads, String member) {
        for (var read : reads) if (read.slot().memberId().equals(member)) return read.value();
        return null;
    }

    /**
     * Production vehicle classes with custom interpolation writers that the driven path does not
     * cover (audited reason, not evasion): they override {@code handleClientSync} or angle setters,
     * so their client fields do not follow the base R endpoints. The closure test re-verifies the
     * override each pin; covering them is future work, never silent inclusion.
     */
    public static List<String> customInterpolationClasses() {
        return List.of(
                "com.atsuishio.superbwarfare.entity.vehicle.Ju87Entity",
                "com.atsuishio.superbwarfare.entity.vehicle.Ztz99aEntity");
    }

    /**
     * The layout identity both peers hash ({@code compat.family.sbw}): the slot table, the seat
     * transform names, the excluded classes, and whether each slot resolves with its descriptor on
     * the SBW classes this runtime loaded (a renamed getter changes the value on that peer).
     */
    public static String identity() {
        var text = new StringBuilder("sbw.layouts.v1;base=").append(BASE_CLASS);
        Class<?> base = load(BASE_CLASS);
        for (Slot slot : baseSlots()) {
            text.append(";slot=")
                    .append(slot.declaringClass())
                    .append('#')
                    .append(slot.member())
                    .append(slot.descriptor())
                    .append(':')
                    .append(slot.role().name())
                    .append(':')
                    .append(resolves(base, slot) ? "ok" : "missing");
        }
        text.append(";transforms=").append(String.join(",", SEAT_TRANSFORMS));
        for (String name : customInterpolationClasses())
            text.append(";excluded=")
                    .append(name)
                    .append(':')
                    .append(load(name) == null ? "missing" : "ok");
        return text.toString();
    }

    private static boolean resolves(Class<?> base, Slot slot) {
        if (base == null || !slot.declaringClass().equals(BASE_CLASS)) return false;
        String getter = presenceGetter(slot.member());
        if (getter != null) {
            try {
                return slot.descriptor().equals("()Z")
                        && base.getMethod(getter).getReturnType()
                                == net.minecraft.world.phys.Vec3.class;
            } catch (NoSuchMethodException | LinkageError missing) {
                return false;
            }
        }
        Class<?> type =
                switch (slot.descriptor()) {
                    case "()F" -> float.class;
                    case "()D" -> double.class;
                    case "()Z" -> boolean.class;
                    case VEC3_GETTER -> net.minecraft.world.phys.Vec3.class;
                    default -> null;
                };
        try {
            return type != null && base.getMethod(slot.member()).getReturnType() == type;
        } catch (NoSuchMethodException | LinkageError missing) {
            return false;
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, SbwLayouts.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }
}
