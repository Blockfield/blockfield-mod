package com.lunares.blockfield.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.client.animation.AnimationController;
import com.tacz.guns.api.client.animation.Animations;
import com.tacz.guns.api.client.animation.ObjectAnimation;
import com.tacz.guns.api.client.animation.statemachine.LuaAnimationStateMachine;
import com.tacz.guns.api.client.animation.statemachine.LuaStateMachineFactory;
import com.tacz.guns.client.animation.statemachine.ItemAnimationStateContext;
import com.tacz.guns.client.model.BedrockAnimatedModel;
import com.tacz.guns.client.model.functional.LeftHandRender;
import com.tacz.guns.client.model.functional.RightHandRender;
import com.tacz.guns.client.renderer.item.AnimateGeoItemRenderer;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import java.util.List;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * First-person renderer for a held item drawn as a TaCZ Bedrock model with both player hands,
 * driven by Bedrock animations and a Lua state machine. Other display contexts keep the item's
 * regular model; native item use stays with the item's mod.
 */
public abstract class BedrockItemRenderer
        extends AnimateGeoItemRenderer<BedrockAnimatedModel, BedrockItemRenderer.Context> {
    private final ResourceLocation modelId;
    private final ResourceLocation animationId;
    private final ResourceLocation scriptId;
    private boolean virtual;

    protected BedrockItemRenderer(
            ResourceLocation modelId, ResourceLocation animationId, ResourceLocation scriptId) {
        this.modelId = modelId;
        this.animationId = animationId;
        this.scriptId = scriptId;
    }

    /** Drops the loaded model and state machine whenever client resources reload. */
    public static void reloadWith(String id, BedrockItemRenderer renderer) {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(
                        new SimpleSynchronousResourceReloadListener() {
                            @Override
                            public ResourceLocation getFabricId() {
                                return ResourceLocation.fromNamespaceAndPath("blockfield", id);
                            }

                            @Override
                            public void onResourceManagerReload(ResourceManager manager) {
                                renderer.clearResources();
                            }
                        });
    }

    public static class Context extends ItemAnimationStateContext {
        private boolean using;
        private boolean linked;
        private boolean remoteEnded;
        private boolean remoteStarted;
        private boolean virtual;
        private int stackCount;

        public boolean isUsing() {
            return using;
        }

        /** The item's linked state, e.g. a monitor paired with a drone. */
        public boolean isLinked() {
            return linked;
        }

        /** Drawn in first person without being held (see {@link #renderVirtual}). */
        public boolean isVirtual() {
            return virtual;
        }

        /** True once when the player starts remote control through this item. */
        public boolean takeRemoteStarted() {
            boolean started = remoteStarted;
            remoteStarted = false;
            return started;
        }

        /** True once after the player stopped remote control through this item. */
        public boolean takeRemoteEnded() {
            boolean ended = remoteEnded;
            remoteEnded = false;
            return ended;
        }

        public int getStackCount() {
            return stackCount;
        }
    }

    /** Adjusts animations before they enter the controller, e.g. mutes native sounds. */
    protected void prepare(List<ObjectAnimation> animations) {}

    protected boolean isLinked(ItemStack stack) {
        return false;
    }

    /** "Using" for a virtual item, which the player does not hold and so cannot use natively. */
    protected boolean virtualUsing() {
        return false;
    }

    /** No walking sway, e.g. while a menu sits on the item's screen. */
    protected boolean steady(ItemStack stack) {
        return false;
    }

    /** Adds functional renderers to a freshly loaded model. */
    protected void configure(BedrockAnimatedModel model) {}

    /** Consumes a pending start of remote control. */
    protected boolean consumeRemoteStart() {
        return false;
    }

    /** Consumes a pending end of remote control (hands are hidden while it lasts). */
    protected boolean consumeRemoteEnd() {
        return false;
    }

    void clearResources() {
        // A loaded but never drawn item has no running machine; exit() would fail the reload.
        if (stateMachine != null && stateMachine.isInitialized()) stateMachine.exit();
        stateMachine = null;
        model = null;
    }

    private void loadResources() {
        if (model != null) return;
        ClientAssetsManager assets = ClientAssetsManager.INSTANCE;
        var geometry = assets.getBedrockModelPOJO(modelId);
        var animations = assets.getBedrockAnimations(animationId);
        var script = assets.getScript(scriptId);
        if (geometry == null || animations == null || script == null) return;
        BedrockAnimatedModel nextModel =
                new BedrockAnimatedModel(
                        geometry,
                        BedrockVersion.isLegacyVersion(geometry)
                                ? BedrockVersion.LEGACY
                                : BedrockVersion.NEW);
        nextModel.setFunctionalRenderer("lefthand_pos", part -> new LeftHandRender(nextModel));
        nextModel.setFunctionalRenderer("righthand_pos", part -> new RightHandRender(nextModel));
        configure(nextModel);
        var prototypes = Animations.createAnimationFromBedrock(animations);
        prepare(prototypes);
        stateMachine =
                new LuaStateMachineFactory<Context>()
                        .setController(new AnimationController(prototypes, nextModel))
                        .setLuaScripts(script)
                        .build();
        model = nextModel;
    }

    @Override
    public BedrockAnimatedModel getModel(ItemStack stack) {
        loadResources();
        return model;
    }

    @Override
    public LuaAnimationStateMachine<Context> getStateMachine(ItemStack stack) {
        loadResources();
        return stateMachine;
    }

    @Override
    public boolean isSameItem(ItemStack oldStack, ItemStack newStack) {
        // Count and item data change while held (use, throws, a monitor's drone position);
        // keep the running clip and let the script react.
        return ItemStack.isSameItem(oldStack, newStack);
    }

    @Override
    public Context initContext(ItemStack stack, Player player, float partialTick) {
        Context context = new Context();
        updateContext(context, stack, player, partialTick);
        return context;
    }

    @Override
    public void updateContext(Context context, ItemStack stack, Player player, float partialTick) {
        context.using =
                virtual
                        ? virtualUsing()
                        : player.isUsingItem() && player.getUseItem().is(stack.getItem());
        context.linked = isLinked(stack);
        context.virtual = virtual;
        if (consumeRemoteStart()) context.remoteStarted = true;
        if (consumeRemoteEnd()) context.remoteEnded = true;
        context.stackCount = stack.getCount();
        context.setPartialTicks(partialTick);
    }

    /**
     * Draws `stack` in first person although the player holds something else; the first frame
     * starts the state machine with {@link Context#isVirtual()} set.
     */
    public void renderVirtual(
            LocalPlayer player,
            ItemStack stack,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            float partialTick) {
        virtual = true;
        try {
            renderFirstPerson(
                    player,
                    stack,
                    ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
                    pose,
                    buffers,
                    light,
                    partialTick);
        } finally {
            virtual = false;
        }
    }

    /** Stops a virtual item's state machine so the next draw starts fresh. */
    public void endVirtual(ItemStack stack) {
        var machine = getStateMachine(stack);
        if (machine != null && machine.isInitialized()) machine.exit();
    }

    @Override
    public void renderFirstPerson(
            LocalPlayer player,
            ItemStack stack,
            ItemDisplayContext display,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            float partialTick) {
        var machine = getStateMachine(stack);
        if (machine != null
                && !machine.isInitialized()
                && machine.getExitingTime() < System.currentTimeMillis()
                && (virtual || player.getMainHandItem().is(stack.getItem()))) {
            tryInit(stack, player, partialTick);
        }
        pose.pushPose();
        try {
            if (!player.isScoping()
                    && !player.isUsingItem()
                    && !steady(stack)
                    && HeldEquipmentMovement.isIdle(this, stack)) {
                HeldEquipmentMovement.apply(
                        pose,
                        player,
                        partialTick,
                        display == ItemDisplayContext.FIRST_PERSON_LEFT_HAND ? -1 : 1);
            }
            super.renderFirstPerson(player, stack, display, pose, buffers, light, partialTick);
        } finally {
            pose.popPose();
        }
    }
}
