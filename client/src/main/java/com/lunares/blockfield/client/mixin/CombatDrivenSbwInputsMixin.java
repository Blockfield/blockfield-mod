package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.lunares.blockfield.client.combat.DrivenSbw;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.SbwLayouts;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Inside a driven remote's render pass bf24's native SBW readers see S[ceil(A)]'s retained {@code
 * AnimationState.Sbw} (client review P0-3): the parachute bit of the model tail, and the seat
 * indices and passenger scale of the model tail and seat root, and the root vehicle's offset height
 * and turret/barrel/weapon-station positions behind the seat direction. The ride link and seat
 * resources are checked at install ({@code DrivenSbw}); outside the scope every read stays native.
 */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.item.curio.ParachuteItem$Companion", remap = false)
public abstract class CombatDrivenSbwInputsMixin {
    /** Accessories' current {@code Open} bit; SBW's static bridge routes here. */
    @Inject(method = "isParachuteOpen", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$parachute(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        var sbw = DrivenPresentation.sbw(entity);
        if (sbw != null) cir.setReturnValue(sbw.parachuteOpen());
    }

    @Pseudo
    @Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity", remap = false)
    public abstract static class Seat {
        /** The direct vehicle's seat bones and the root's seat transform query this index. */
        @Inject(method = "getSeatIndex", at = @At("HEAD"), cancellable = true, require = 1)
        private void blockfield$seatIndex(Entity passenger, CallbackInfoReturnable<Integer> cir) {
            var sbw = DrivenPresentation.sbw(passenger);
            if (sbw == null) return;
            int id = ((Entity) (Object) this).getId();
            if (id == sbw.directVehicleEntityId()) cir.setReturnValue(sbw.directSeatIndex());
            else if (id == sbw.rootVehicleEntityId()) cir.setReturnValue(sbw.rootSeatIndex());
        }

        /**
         * The seat direction's named matrix ({@code getVehicleYOffsetTransform}, {@code
         * getTurretTransform}) translates by the root vehicle's retained offset height and turret
         * position (FX-SEAT), the same decoded reads the server port consumes.
         */
        @Inject(method = "getRotateOffsetHeight", at = @At("HEAD"), cancellable = true, require = 1)
        private void blockfield$rotateOffsetHeight(CallbackInfoReturnable<Double> cir) {
            if (DrivenSbw.retainedRead(this, SbwLayouts.ROTATE_OFFSET_HEIGHT)
                    instanceof AnimationState.DoubleValue value) cir.setReturnValue(value.value());
        }

        @Inject(method = "getTurretPos", at = @At("HEAD"), cancellable = true, require = 1)
        private void blockfield$turretPos(CallbackInfoReturnable<Vec3> cir) {
            if (!(DrivenSbw.retainedRead(this, SbwLayouts.HAS_TURRET)
                    instanceof AnimationState.BooleanValue present)) return;
            var position = DrivenSbw.retainedRead(this, SbwLayouts.TURRET_POS);
            cir.setReturnValue(
                    present.value() && position instanceof AnimationState.VectorValue value
                            ? new Vec3(value.value().x(), value.value().y(), value.value().z())
                            : null);
        }

        @Inject(method = "getBarrelPosition", at = @At("HEAD"), cancellable = true, require = 1)
        private void blockfield$barrelPosition(CallbackInfoReturnable<Vec3> cir) {
            blockfield$position(SbwLayouts.BARREL_POSITION, cir);
        }

        @Inject(
                method = "getPassengerWeaponStationPosition",
                at = @At("HEAD"),
                cancellable = true,
                require = 1)
        private void blockfield$weaponStationPosition(CallbackInfoReturnable<Vec3> cir) {
            blockfield$position(SbwLayouts.WEAPON_STATION_POSITION, cir);
        }

        @Inject(
                method = "getPassengerWeaponStationBarrelPosition",
                at = @At("HEAD"),
                cancellable = true,
                require = 1)
        private void blockfield$weaponStationBarrelPosition(CallbackInfoReturnable<Vec3> cir) {
            blockfield$position(SbwLayouts.WEAPON_STATION_BARREL_POSITION, cir);
        }

        @org.spongepowered.asm.mixin.Unique
        private void blockfield$position(String member, CallbackInfoReturnable<Vec3> cir) {
            if (!(DrivenSbw.retainedRead(this, member + SbwLayouts.PRESENT)
                    instanceof AnimationState.BooleanValue present)) return;
            var position = DrivenSbw.retainedRead(this, member);
            cir.setReturnValue(
                    present.value() && position instanceof AnimationState.VectorValue value
                            ? new Vec3(value.value().x(), value.value().y(), value.value().z())
                            : null);
        }

        /** The seat root scales the passenger by its root vehicle's resource scale. */
        @Inject(
                method = "getPassengerRenderScale",
                at = @At("HEAD"),
                cancellable = true,
                require = 1)
        private void blockfield$passengerScale(CallbackInfoReturnable<Float> cir) {
            var sbw = DrivenPresentation.scopedSbw();
            if (sbw != null && ((Entity) (Object) this).getId() == sbw.rootVehicleEntityId())
                cir.setReturnValue(sbw.scale());
        }
    }
}
