package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.atsuishio.superbwarfare.fabric.AccessoriesKt;
import com.atsuishio.superbwarfare.item.curio.ParachuteItem;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.lunares.blockfield.client.combat.ClientCombatScene;
import com.lunares.blockfield.client.combat.ClientSnapshotBuffer;
import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.oracle.FxClientCases;
import com.lunares.blockfield.oracle.FxClientItems;
import com.lunares.blockfield.oracle.FxPoseCases;
import com.lunares.blockfield.oracle.MixedRendererCases;
import io.wispforest.accessories.api.slot.SlotEntryReference;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.mockito.MockedStatic;

/**
 * Client review P0-3: the live remote contradicts S[ceil(A)] where bf24's native SBW readers look
 * (Accessories' parachute {@code Open} bit, the main-hand gun, pose and swimming), so a native read
 * reaching the six boxes shows up as a native/server difference. Client half only.
 */
final class FxPoseLive {
    private FxPoseLive() {}

    private static MixedRendererCases.Case current;
    private static MockedStatic<AccessoriesKt> accessories;

    /** Before each install: the case's live state (once per case) and its per-frame writes. */
    static void frame(MixedRendererCases.Case value, AbstractClientPlayer player) throws Exception {
        definitions(
                List.of(
                        FxClientCases.definition(
                                value.identity(), FxClientCases.retainedItem(value))));
        if (!value.name().startsWith(FxPoseCases.SBW)
                && !value.name().startsWith(FxClientCases.ARMS)) return;
        String name =
                value.name().startsWith(FxPoseCases.SBW)
                        ? value.name().substring(FxPoseCases.SBW.length())
                        : value.name();
        if (value != current) {
            close();
            current = value;
            // The prone redirect recognises SBW's merged model tail by its handler name.
            assertEquals(
                    1,
                    Arrays.stream(HumanoidModel.class.getDeclaredMethods())
                            .filter(m -> m.getName().endsWith(DrivenPresentation.SBW_MODEL_TAIL))
                            .count(),
                    "one merged bf24 HumanoidModelMixin.setupAnim handler");
            if (name.equals("parachute-live-open")) {
                var open = new CompoundTag();
                open.putBoolean("Open", true);
                var stack = new ItemStack(Items.STICK);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(open));
                accessories = mockStatic(AccessoriesKt.class, CALLS_REAL_METHODS);
                accessories
                        .when(() -> AccessoriesKt.findFirstEquipped(eq(player), any()))
                        .thenReturn(new SlotEntryReference(null, stack));
            }
            String retained = FxClientCases.retainedItem(value);
            if (retained != null) {
                var stack = FxClientItems.stack(retained);
                @SuppressWarnings("unchecked")
                Registry<Item> items = mock(Registry.class);
                var id = ResourceLocation.fromNamespaceAndPath("superbwarfare", retained);
                doReturn(true).when(items).containsKey(id);
                doReturn(stack.getItem()).when(items).get(id);
                var access = mock(RegistryAccess.class);
                doReturn(items).when(access).registryOrThrow(Registries.ITEM);
                doReturn(access).when(player).registryAccess();
            }
            String liveItem = name.endsWith("-gun") ? "glock_17" : null;
            if (value.name().startsWith(FxClientCases.ARMS) && value.name().endsWith("-live"))
                liveItem =
                        value.name()
                                .substring(
                                        FxClientCases.ARMS.length(),
                                        value.name().length() - "-live".length());
            var live = FxClientItems.stack(liveItem);
            doCallRealMethod().when(player).getMainHandItem();
            doCallRealMethod().when(player).getItemInHand(InteractionHand.MAIN_HAND);
            doReturn(live).when(player).getItemBySlot(EquipmentSlot.MAINHAND);
        }
        // Live pose/swimming opposite to the checkpoint: the install must present S[ceil(A)].
        if (name.startsWith("prone")) {
            player.setPose(Pose.STANDING);
            player.setSwimming(true);
        } else if (name.startsWith("swim")) player.setSwimming(false);
        if (name.equals("parachute-live-open"))
            assertTrue(ParachuteItem.isParachuteOpen(player), "live parachute open");
        if (name.endsWith("-gun") && !name.endsWith("live-empty"))
            assertInstanceOf(GunItem.class, player.getMainHandItem().getItem(), "live SBW gun");
    }

    static void close() {
        current = null;
        if (accessories != null) accessories.close();
        accessories = null;
    }

    static void definitions(List<AnimationResources.Definition> definitions) throws Exception {
        var field = ClientCombatScene.class.getDeclaredField("buffer");
        field.setAccessible(true);
        ((ClientSnapshotBuffer) field.get(null)).resources().installAll(definitions);
    }
}
