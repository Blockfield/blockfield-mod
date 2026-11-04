package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.client.animation.RemoteLrCompat;
import com.lunares.blockfield.client.animation.RemotePaPresentation;
import com.lunares.blockfield.client.animation.RemoteParCoolPresentation;
import com.lunares.blockfield.common.StanceTransition;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTypes;
import com.lunares.blockfield.common.combat.ObserverReducer;
import com.lunares.blockfield.common.combat.PaGraph;
import com.lunares.blockfield.common.combat.SnapshotWireMath;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The driven-remote render scope of one main frame (ADR4 §§2.4/4.2/5.3, U8). Every render pass of a
 * driven remote samples the native renderer at the frame's A endpoint partial: the A-domain native
 * old/current pairs (attack, swim, age, death, pose, arm, stance) come from S[ceil(A)] and the
 * native getters interpolate them themselves. R-domain inputs are collapsed to their R sample
 * (position and facing on the entity, walk through {@link #walkPosition}/{@link #walkSpeed},
 * velocity through {@link VanillaDrivenScope}). The PA graph is the observer reduction installed
 * through the LR-aware adapter, the ParCool family value (animator, passive, option and action
 * scratch) through {@link RemoteParCoolPresentation}, installed for every driven remote so its
 * native mirror never selects geometry. The local player is never driven.
 *
 * <p>Inside every render pass ({@link #scope}) the remaining A-domain inputs the native renderer
 * and model read through entity getters come from the same checkpoint (U8b): item use (state, hand,
 * ticks, arm poses, crossbow charge duration), riding, climbing, elytra and riptide flags,
 * freezing, bed orientation with the standing eye height, the upside-down name branch, and the
 * Create hanging membership with its phase since presentation enter, and the retained SBW input of
 * bf24's model tail and seat root (parachute bit, seat indices, passenger scale, the prone tail's
 * main hand; {@link DrivenSbw}). Outside the scope the entity keeps its live values (client
 * gameplay, the local-native view).
 */
public final class DrivenPresentation {
    /**
     * The Create hanging adapter input of one revision: the whole age the native {@code
     * getTicks(true)} slot receives and the {@code getPartialTicks()} fraction (frac(A)).
     */
    public record Hanging(int ticks, float fraction) {}

    private record Frame(
            float partialA,
            boolean medkit,
            AnimationState.Vanilla vanilla,
            Hanging hanging,
            AnimationState.Sbw sbw,
            ItemStack sbwMainHand) {}

    private record Walk(float position, float speed) {}

    private static final Map<Entity, Frame> FRAMES = new IdentityHashMap<>();
    private static final Map<WalkAnimationState, Walk> WALKS = new IdentityHashMap<>();
    // Remotes whose LR compatibility history was lost: native until the entity object is replaced
    // (U4: an empty view cannot be re-seeded from driven scratch).
    private static final java.util.Set<Entity> LOST =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private DrivenPresentation() {}

    /** The render partial of a driven remote (its A endpoint partial), or null for native. */
    public static Float partial(Entity entity) {
        var frame = FRAMES.get(entity);
        return frame == null ? null : frame.partialA();
    }

    public static Float walkPosition(WalkAnimationState walk) {
        var scoped = WALKS.get(walk);
        return scoped == null ? null : scoped.position();
    }

    public static Float walkSpeed(WalkAnimationState walk) {
        var scoped = WALKS.get(walk);
        return scoped == null ? null : scoped.speed();
    }

    /**
     * The R-domain locomotion of a remote driven by an exact record: the walk triple and the
     * velocity pair at p_R. Placement data, so it holds whether or not the animation is driven.
     */
    public static void placement(AbstractClientPlayer player, SnapshotTransform record, float p) {
        if (player instanceof LocalPlayer || record == null || !record.exact()) return;
        var placement = record.placement();
        WALKS.put(
                player.walkAnimation,
                new Walk(
                        SnapshotWireMath.walkPosition(placement.walk(), p),
                        SnapshotWireMath.walkSpeed(placement.walk(), p)));
        VanillaDrivenScope.install(
                player.getId(),
                new VanillaDrivenScope.DrivenFrame(
                        vec(placement.deltaMovementOnPreviousTick()),
                        vec(placement.deltaMovement()),
                        p));
    }

    /** The authoritative medkit pose of a driven remote, or null for native. */
    public static Boolean medkit(Entity entity) {
        var frame = FRAMES.get(entity);
        return frame == null ? null : frame.medkit();
    }

    public static boolean driven(Entity entity) {
        return FRAMES.containsKey(entity);
    }

    // The entity whose render pass is open and the checkpoint inputs it reads (render thread).
    private static Entity scoped;
    private static Frame scopedFrame;

    /**
     * Opens the render scope of {@code entity} for one pass: its getters read the installed
     * checkpoint until the scope closes (nested passes restore the outer one, also on exceptions).
     * A no-op scope for an undriven entity.
     */
    public static Scope scope(Entity entity) {
        var scope = new Scope(scoped, scopedFrame);
        var frame = FRAMES.get(entity);
        scoped = frame == null ? null : entity;
        scopedFrame = frame;
        return scope;
    }

    /** Restores the previous render scope. */
    public static final class Scope implements AutoCloseable {
        private final Entity entity;
        private final Frame frame;

        private Scope(Entity entity, Frame frame) {
            this.entity = entity;
            this.frame = frame;
        }

        @Override
        public void close() {
            scoped = entity;
            scopedFrame = frame;
        }
    }

    /** The checkpoint inputs while {@code entity} renders inside its scope, else null (native). */
    public static AnimationState.Vanilla inputs(Object entity) {
        return entity == scoped ? scopedFrame.vanilla() : null;
    }

    /**
     * The Create hanging input while {@code entity} renders inside its scope: null when native,
     * {@link #NOT_HANGING} for a driven remote outside membership.
     */
    public static Hanging hanging(Object entity) {
        if (entity != scoped) return null;
        var hanging = scopedFrame.hanging();
        return hanging == null ? NOT_HANGING : hanging;
    }

    public static final Hanging NOT_HANGING = new Hanging(-1, 0);

    /** The retained SBW input while {@code entity} renders inside its scope, else null (native). */
    public static AnimationState.Sbw sbw(Object entity) {
        return entity == scoped ? scopedFrame.sbw() : null;
    }

    /** The retained SBW input of the open render pass, for reads on its vehicles. */
    public static AnimationState.Sbw scopedSbw() {
        return scoped == null ? null : scopedFrame.sbw();
    }

    /** bf24's merged {@code HumanoidModelMixin.setupAnim} handler (Fabric mixin handler name). */
    public static final String SBW_MODEL_TAIL = "$superbwarfare$setupAnim";

    /**
     * The retained main hand SBW's model handlers read inside the driven render scope. Every other
     * main-hand reader of the pass (held item, TaCZ) keeps the live stack.
     */
    public static ItemStack sbwTailMainHand(Object entity) {
        if (entity != scoped || scopedFrame.sbwMainHand() == null) return null;
        // A stack walk confines the item-class redirect to SBW's merged handlers; Mixin cannot
        // target another mod's handler. Other held-item readers keep their native stacks.
        boolean tail =
                StackWalker.getInstance()
                        .walk(
                                frames ->
                                        frames.limit(64)
                                                .anyMatch(
                                                        f ->
                                                                f.getMethodName()
                                                                                .endsWith(
                                                                                        SBW_MODEL_TAIL)
                                                                        || f.getMethodName()
                                                                                .endsWith(
                                                                                        "$superbwarfare$poseRightArm")
                                                                        || f.getMethodName()
                                                                                .endsWith(
                                                                                        "$superbwarfare$poseLeftArm")));
        return tail ? scopedFrame.sbwMainHand().copy() : null;
    }

    /** SBW's prone test: pose SWIMMING out of the water-swimming state. */
    static boolean prone(AnimationState.Vanilla vanilla) {
        return vanilla.pose() == AnimationTypes.Pose.SWIMMING && !vanilla.flags().swimming();
    }

    /**
     * Installs one revision for a driven remote. Returns null, or the label of why its animation
     * stays native this frame (the presentation is then cleared, never half-installed).
     */
    public static String install(
            AbstractClientPlayer player,
            SnapshotTransform placementRecord,
            float partialR,
            SnapshotTransform animationRecord,
            float partialA,
            ObserverReducer.Result reduced,
            ClientAnimationAssets.Generation assets,
            AnimationTypes.Time a,
            float fractionR) {
        float fractionA = a.fraction();
        if (player instanceof LocalPlayer) return "local_player";
        var checkpoint = animationRecord == null ? null : animationRecord.checkpoint();
        if (checkpoint == null || placementRecord == null || !placementRecord.exact()) {
            clear(player);
            return "state_unavailable";
        }
        if (LOST.contains(player)) return "lr_view_lost";
        try {
            AnimationSchema.validateFinite(checkpoint);
            AnimationSchema.validateFinite(placementRecord.placement());
            var vanilla = checkpoint.vanilla();
            ItemStack mainHand = null;
            if (Sbw.LOADED) {
                mainHand = DrivenSbw.mainHand(player, checkpoint, ref -> definition(ref, assets));
                String sbw =
                        DrivenSbw.unavailable(
                                player, checkpoint, ref -> definition(ref, assets), mainHand);
                if (sbw != null) {
                    clear(player);
                    return sbw;
                }
            }
            var frame =
                    new RemotePaPresentation.Frame(
                            reduced.pa().graph(),
                            assets.lr().catalog(),
                            assets.policy(),
                            fractionA,
                            fractionR,
                            vanilla,
                            checkpoint.tacz(),
                            assets.tacz());
            if (!RemotePaPresentation.driven(player)) RemoteLrCompat.attach(player);
            else if (RemoteLrCompat.unseeded(player)) {
                LOST.add(player);
                throw new PaGraph.Unsupported("LR native compatibility view lost");
            }
            RemoteLrCompat.install(player, frame);
            // ADR4 §5.2: the observer-reduced ParCool value, sampled at frac(A); its FastRun and
            // wall-run limbs read the R walk at p_R that the walk scope below presents.
            RemoteParCoolPresentation.install(
                    player,
                    new RemoteParCoolPresentation.Frame(
                            reduced.parcoolFrame(), assets.policy(), fractionA, fractionR));
            writeAnimation(player, vanilla, checkpoint.stance());
            placement(player, placementRecord, partialR);
            // ADR4 §4.5: the reduced membership start drives the native hanging pose at the
            // whole age u0 + (floor(A) - sA) plus frac(A), exactly as the server port samples it.
            var create = reduced.create();
            var hanging =
                    create == null
                            ? null
                            : new Hanging(
                                    com.lunares.blockfield.common.combat.CreateHanging.ticks(
                                            create.u0(), create.phaseAnchor(), a.tick()),
                                    fractionA);
            FRAMES.put(
                    player,
                    new Frame(
                            partialA,
                            vanilla.medkitActive(),
                            vanilla,
                            hanging,
                            checkpoint.sbw(),
                            mainHand));
            return null;
        } catch (RuntimeException e) {
            // Fail closed: any refusal or missing native piece leaves the remote native.
            clear(player);
            return "presentation:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    /** SBW is optional on the client: {@link DrivenSbw} loads only when it is present. */
    private static final class Sbw {
        static final boolean LOADED =
                net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("superbwarfare");
    }

    /**
     * A retained definition: life-scoped refs arrive with the exact stream, generation-scoped ones
     * resolve in their generation's catalog. Null when neither retains it.
     */
    private static AnimationResources.Definition definition(
            AnimationResources.ResourceRef ref, ClientAnimationAssets.Generation assets) {
        var retained = ClientCombatScene.retainedDefinition(ref);
        if (retained != null) return retained;
        for (var definition : assets.lr().catalog().definitions())
            if (definition.ref().equals(ref)) return definition;
        return null;
    }

    /** A-domain native pairs from S[ceil(A)]; the native getters sample them at the A partial. */
    private static void writeAnimation(
            AbstractClientPlayer player,
            AnimationState.Vanilla vanilla,
            AnimationState.Stance stance) {
        var attack = vanilla.attack();
        player.oAttackAnim = attack.oAttackAnim();
        player.attackAnim = attack.attackAnim();
        player.swinging = attack.swinging();
        player.swingTime = attack.swingTime();
        player.swingingArm =
                attack.swingingArm() == null
                        ? null
                        : attack.swingingArm() == AnimationTypes.Hand.MAIN_HAND
                                ? InteractionHand.MAIN_HAND
                                : InteractionHand.OFF_HAND;
        var living = (com.lunares.blockfield.client.mixin.CombatDrivenLivingAccessor) player;
        living.blockfield$setSwimAmountO(vanilla.swimAmountO());
        living.blockfield$setSwimAmount(vanilla.swimAmount());
        living.blockfield$setFallFlyTicks(vanilla.vital().fallFlyTicks());
        player.tickCount = vanilla.vital().tickCount();
        player.deathTime = vanilla.vital().deathTime();
        player.setMainArm(
                vanilla.mainArm() == AnimationTypes.Arm.LEFT
                        ? HumanoidArm.LEFT
                        : HumanoidArm.RIGHT);
        if (player instanceof StanceTransition.Access access
                && access.blockfield$stance() != null) {
            var transition = access.blockfield$stance();
            transition.setDriven(true);
            // Driven stance holds the authoritative box: the client-observed pose must not
            // retarget it, but the box/eye still ease from the replicated snapshot — including
            // the crouch triple, so the remote body blends through the shared Crouch.at path.
            var pose = Pose.valueOf(vanilla.pose().name());
            if (player.getPose() != pose) player.setPose(pose);
            var like = player.getDimensions(pose);
            transition.restore(
                    StanceTransition.snapshotOf(
                            stance, dims(stance.from(), like), dims(stance.to(), like)));
            // The pose is constant through the transition, so no vanilla path refreshes: push
            // the replicated eased box now.
            player.refreshDimensions();
        } else {
            var pose = Pose.valueOf(vanilla.pose().name());
            if (player.getPose() != pose) player.setPose(pose);
        }
        if (player.isSwimming() != vanilla.flags().swimming())
            player.setSwimming(vanilla.flags().swimming());
    }

    private static net.minecraft.world.entity.EntityDimensions dims(
            AnimationState.DimensionsValue v, net.minecraft.world.entity.EntityDimensions like) {
        return new net.minecraft.world.entity.EntityDimensions(
                v.width(), v.height(), v.eyeHeight(), like.attachments(), v.fixed());
    }

    private static Vec3 vec(AnimationState.Vec3 value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    /**
     * Returns a remote to native animation: native layers, LR view state and stance. Its exact
     * locomotion scope stays until {@link #release}.
     */
    public static void clear(Entity entity) {
        var frame = FRAMES.remove(entity);
        if (entity instanceof AbstractClientPlayer player) {
            if (RemotePaPresentation.driven(player)) RemotePaPresentation.clear(player);
            if (RemoteParCoolPresentation.driven(player)) RemoteParCoolPresentation.clear(player);
            RemoteLrCompat.detach(player);
            if (frame != null
                    && player instanceof StanceTransition.Access access
                    && access.blockfield$stance() != null)
                access.blockfield$stance().setDriven(false);
        }
    }

    /** No exact record drives the remote any more: animation and locomotion scopes go. */
    public static void release(Entity entity) {
        clear(entity);
        if (entity instanceof AbstractClientPlayer player) WALKS.remove(player.walkAnimation);
        VanillaDrivenScope.clear(entity.getId());
    }

    /** The entity object left the level: a replacement object may be driven again. */
    public static void forget(Entity entity) {
        release(entity);
        LOST.remove(entity);
    }

    /** Every driven animation goes native; exact locomotion scopes stay. */
    public static void clearAnimations() {
        for (var entity : List.copyOf(FRAMES.keySet())) clear(entity);
    }

    public static void clearAll() {
        for (var entity : List.copyOf(FRAMES.keySet())) release(entity);
        LOST.clear();
        WALKS.clear();
        RemotePaPresentation.clearAll();
        RemoteParCoolPresentation.clearAll();
        RemoteLrCompat.clearAll();
        VanillaDrivenScope.clearAll();
    }
}
