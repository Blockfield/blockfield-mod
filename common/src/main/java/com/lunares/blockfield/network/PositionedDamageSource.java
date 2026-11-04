package com.lunares.blockfield.network;

import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class PositionedDamageSource extends DamageSource {
    private final Vec3 position;

    public PositionedDamageSource(
            Holder<DamageType> type, Entity direct, Entity cause, Vec3 position) {
        super(type, direct, cause);
        this.position = position;
    }

    @Override
    public Vec3 getSourcePosition() {
        return position;
    }

    @Override
    public Vec3 sourcePositionRaw() {
        return position;
    }
}
