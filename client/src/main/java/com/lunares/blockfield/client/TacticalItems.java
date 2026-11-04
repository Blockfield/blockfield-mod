package com.lunares.blockfield.client;

import com.lunares.blockfield.client.grenade.GrenadeThrowInput;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Class items that work like a gun: the right button always uses the item, whatever block is under
 * the crosshair (ThrowableUseMixin, GrenadeInputMixin), and doors, buttons and levers are used
 * through the TACZ interact key (InteractKeyTacticalMixin). Drones stay out: they launch by
 * clicking a block (DroneDeploy).
 */
public final class TacticalItems {
    /**
     * Use goes straight to the item, past useItemOn. Hand grenades (GrenadeThrowSupport) have their
     * own throw path.
     */
    private static final Set<ResourceLocation> DIRECT_USE =
            Set.of(
                    ResourceLocation.parse("superbwarfare:medical_kit"),
                    ResourceLocation.parse("superbwarfare:armor_plate"));

    private TacticalItems() {}

    public static boolean directUse(ItemStack stack) {
        return DIRECT_USE.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    public static boolean holding(Minecraft mc) {
        return mc.player != null
                && (directUse(mc.player.getMainHandItem())
                        || GrenadeThrowInput.holdingSupported(mc));
    }
}
