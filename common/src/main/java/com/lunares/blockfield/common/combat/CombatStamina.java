package com.lunares.blockfield.common.combat;

import com.alrex.parcool.common.attachment.common.ReadonlyStamina;

public final class CombatStamina {
    private CombatStamina() {}

    public static ReadonlyStamina newLife(ReadonlyStamina current) {
        return new ReadonlyStamina(false, current.max(), current.max());
    }
}
