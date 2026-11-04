package com.lunares.blockfield.common.mixin.tacz;

import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.GunIndexPOJO;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = CommonGunIndex.class, remap = false)
public interface GunIndexAccessor {
    @Invoker("<init>")
    static CommonGunIndex blockfield$create() {
        throw new AssertionError();
    }

    @Invoker("checkIndex")
    static void blockfield$checkIndex(GunIndexPOJO pojo, CommonGunIndex index) {
        throw new AssertionError();
    }

    @Invoker("checkInaccuracy")
    static void blockfield$checkInaccuracy(GunData data) {
        throw new AssertionError();
    }

    @Invoker("checkRecoil")
    static void blockfield$checkRecoil(GunData data) {
        throw new AssertionError();
    }

    @Invoker("checkScript")
    static void blockfield$checkScript(GunData data, CommonGunIndex index) {
        throw new AssertionError();
    }

    @Accessor("gunData")
    void blockfield$setGunData(GunData value);

    @Accessor("pojo")
    void blockfield$setPojo(GunIndexPOJO value);
}
