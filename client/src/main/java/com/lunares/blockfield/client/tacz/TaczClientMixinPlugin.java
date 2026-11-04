package com.lunares.blockfield.client.tacz;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Клиентские миксины TACZ применяются только при наличии tacz. */
public class TaczClientMixinPlugin implements IMixinConfigPlugin {

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return FabricLoader.getInstance().isModLoaded("tacz")
                && (!(mixinClassName.endsWith(".ParCoolFastRunWeightMixin")
                                || mixinClassName.contains(".Exhausted"))
                        || FabricLoader.getInstance().isModLoaded("parcool"))
                && (!(mixinClassName.endsWith(".ExhaustedPlayerPoseMixin")
                                || mixinClassName.endsWith(".ExhaustedModifierLayerMixin")
                                || mixinClassName.contains(".Remote"))
                        || FabricLoader.getInstance().isModLoaded("playeranimator"))
                && (!mixinClassName.endsWith(".CameraOverhaulAimMixin")
                        || FabricLoader.getInstance().isModLoaded("cameraoverhaul"));
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return List.of();
    }

    @Override
    public void preApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {}

    @Override
    public void postApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {}
}
