package com.lunares.blockfield.client.combat;

import com.atsuishio.superbwarfare.data.vehicle.VehicleData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.SbwLayouts;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * The SBW half of a driven remote's install (client review P0-3): whether the native bf24 readers
 * can present S[ceil(A)]'s retained {@link AnimationState.Sbw}. Loaded only with SBW present.
 *
 * <p>The render scope redirects the seat index, the passenger scale, the parachute bit, the prone
 * tail's main-hand read and the root vehicle's offset height and turret/barrel/weapon-station
 * positions (its retained reads, {@link CombatVehicleScope#reads}); the ride link and the seat
 * resources must agree with the checkpoint, because the native code reads them off the live vehicle
 * (a mismatch is a seat boundary, labelled unavailable as on the server, never presented across).
 */
public final class DrivenSbw {
    private DrivenSbw() {}

    /**
     * Inside a driven passenger's render scope, the retained layout read of its root vehicle (the
     * decoded record the vehicle was written from), else null (native).
     */
    public static AnimationState.ReadValue retainedRead(Object vehicle, String member) {
        var sbw = DrivenPresentation.scopedSbw();
        int id = ((Entity) vehicle).getId();
        if (sbw == null || id != sbw.rootVehicleEntityId()) return null;
        var reads = CombatVehicleScope.reads(id);
        return reads == null ? null : SbwLayouts.read(reads, member);
    }

    /** Why the remote stays native this frame, or null when the native readers can present it. */
    static String unavailable(
            AbstractClientPlayer player,
            AnimationState state,
            Function<AnimationResources.ResourceRef, AnimationResources.Definition> definitions,
            ItemStack mainHand) {
        var sbw = state.sbw();
        Entity live = player.getVehicle();
        int direct = live instanceof VehicleEntity ? live.getId() : -1;
        Entity top = player.getRootVehicle();
        int root = top != player && top instanceof VehicleEntity ? top.getId() : -1;
        if (direct != sbw.directVehicleEntityId() || root != sbw.rootVehicleEntityId())
            return "sbw_vehicle_link";
        // bf24 HumanoidModelMixin.setupAnim 120-174: computed().seats() at getSeatIndex, an
        // out-of-range index returns before the gun tail (the server port has no such return).
        if (direct >= 0) {
            var seats = ((VehicleEntity) live).computed().seats();
            int index = sbw.directSeatIndex();
            if (index < 0 || index >= seats.size()) return "sbw_direct_seat";
            if (!Objects.equals(seats.get(index).pose, sbw.seatPose())) return "sbw_seat_pose";
        }
        // LivingEntityRendererMixin.setupRotations 29-120: VehicleData.compute(root).seats() at
        // getSeatIndex; its transform must be the retained transform-name definition.
        if (root >= 0) {
            var seats = VehicleData.compute((VehicleEntity) top).seats();
            int index = sbw.rootSeatIndex();
            if (index < 0
                    || index >= seats.size()
                    || direct == root && sbw.directSeatIndex() != index) return "sbw_root_seat";
            String transform = null;
            if (sbw.transformName() != null) {
                var definition = definitions.apply(sbw.transformName());
                if (definition == null) return "sbw_transform_unavailable";
                String text = new String(definition.value().value(), StandardCharsets.UTF_8);
                if (!text.startsWith("transform:")) return "sbw_transform";
                transform = text.substring("transform:".length());
            }
            if (!Objects.equals(seats.get(index).transform, transform)) return "sbw_seat_transform";
            // The seat direction reads the root's offset height and named positions: present the
            // retained values or stay native, never the live resource data.
            var reads = CombatVehicleScope.reads(root);
            if (reads == null
                    || !(SbwLayouts.read(reads, SbwLayouts.ROTATE_OFFSET_HEIGHT)
                            instanceof AnimationState.DoubleValue)
                    || !SbwLayouts.coherentPosition(
                            reads, SbwLayouts.TURRET_POS, SbwLayouts.HAS_TURRET))
                return "sbw_vehicle_reads";
            for (String member :
                    java.util.List.of(
                            SbwLayouts.BARREL_POSITION,
                            SbwLayouts.WEAPON_STATION_POSITION,
                            SbwLayouts.WEAPON_STATION_BARREL_POSITION))
                if (!SbwLayouts.coherentPosition(reads, member, member + SbwLayouts.PRESENT))
                    return "sbw_vehicle_reads";
            // These native matrix paths dereference their position (unlike nullable WeaponStation).
            String requiredPosition =
                    switch (transform == null ? "Default" : transform) {
                        case "Barrel" -> SbwLayouts.BARREL_POSITION;
                        case "WeaponStationBarrel" -> SbwLayouts.WEAPON_STATION_BARREL_POSITION;
                        default -> null;
                    };
            if (requiredPosition != null && SbwLayouts.read(reads, requiredPosition) == null)
                return "sbw_vehicle_position";
        }
        return sbw.gun() != (mainHand.getItem() instanceof GunItem) ? "sbw_main_hand" : null;
    }

    /** Only the class is read by bf24's model handlers, as by the server's retained-item port. */
    static ItemStack mainHand(
            AbstractClientPlayer player,
            AnimationState state,
            Function<AnimationResources.ResourceRef, AnimationResources.Definition> definitions) {
        var definition = definitions.apply(state.equipment().mainHandRef());
        if (definition == null) throw new IllegalArgumentException("sbw_main_hand_unavailable");
        String text = new String(definition.value().value(), StandardCharsets.UTF_8);
        if (text.equals("empty")) return ItemStack.EMPTY;
        // VanillaAnimSync's canonical item NBT sorts the top-level id last. Components are not
        // decoded: these SBW readers inspect only the registered native item's class.
        int at = text.lastIndexOf("id:\"");
        if (!text.endsWith("\"}") || at < 0)
            throw new IllegalArgumentException("sbw_main_hand_form");
        var id = ResourceLocation.tryParse(text.substring(at + 4, text.length() - 2));
        if (id == null) throw new IllegalArgumentException("sbw_main_hand_id");
        var items = player.registryAccess().registryOrThrow(Registries.ITEM);
        if (!items.containsKey(id)) throw new IllegalArgumentException("sbw_main_hand_id");
        return new ItemStack(items.get(id));
    }
}
