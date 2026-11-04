package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationSchema.Nullable;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.List;
import java.util.Objects;

public record AnimationState(
        Identity identity,
        long serverTick,
        long sampleRevision,
        long eventWatermark,
        Placement placement,
        Vanilla vanilla,
        Stance stance,
        Equipment equipment,
        @Nullable Tacz tacz,
        Lr lr,
        AnimationGraphState graph,
        ParCool parcool,
        Create create,
        Sbw sbw,
        List<AnimationEvent.Decision> decisions) {
    public AnimationState {
        AnimationSchema.nonnegative(serverTick, "server tick");
        AnimationSchema.nonnegative(sampleRevision, "sample revision");
        AnimationSchema.nonnegative(eventWatermark, "event watermark");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(vanilla, "vanilla");
        Objects.requireNonNull(stance, "stance");
        Objects.requireNonNull(equipment, "equipment");
        Objects.requireNonNull(lr, "lr");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(parcool, "parcool");
        Objects.requireNonNull(create, "create");
        Objects.requireNonNull(sbw, "sbw");
        decisions = List.copyOf(decisions);
        AnimationSchema.count(decisions.size(), AnimationSchema.MAX_NODES, "decisions");
    }

    public record Identity(
            long connectionId,
            long observerStreamEpoch,
            long sceneGeneration,
            int entityId,
            int kind,
            long subscriptionGeneration,
            long targetEpoch,
            long seatGeneration,
            long teleportGeneration,
            long resourceGeneration,
            long policyGeneration,
            Digest compatibilityDigest,
            Digest manifestDigest) {
        public Identity {
            for (long value :
                    new long[] {
                        connectionId,
                        observerStreamEpoch,
                        sceneGeneration,
                        entityId,
                        subscriptionGeneration,
                        targetEpoch,
                        seatGeneration,
                        teleportGeneration,
                        resourceGeneration,
                        policyGeneration
                    }) AnimationSchema.nonnegative(value, "identity");
            if (kind < 0 || kind > 2) throw new IllegalArgumentException("Invalid entity kind");
            Objects.requireNonNull(compatibilityDigest, "compatibilityDigest");
            Objects.requireNonNull(manifestDigest, "manifestDigest");
        }

        public boolean sameGeneration(Identity b) {
            return connectionId == b.connectionId
                    && observerStreamEpoch == b.observerStreamEpoch
                    && sceneGeneration == b.sceneGeneration
                    && entityId == b.entityId
                    && kind == b.kind
                    && subscriptionGeneration == b.subscriptionGeneration
                    && targetEpoch == b.targetEpoch
                    && seatGeneration == b.seatGeneration
                    && teleportGeneration == b.teleportGeneration
                    && resourceGeneration == b.resourceGeneration
                    && policyGeneration == b.policyGeneration
                    && compatibilityDigest.equals(b.compatibilityDigest)
                    && manifestDigest.equals(b.manifestDigest);
        }
    }

    public record Vec3(double x, double y, double z) {}

    public record Vec3f(float x, float y, float z) {}

    public record Angles(
            float yRotO,
            float yRot,
            float xRotO,
            float xRot,
            float yBodyRotO,
            float yBodyRot,
            float yHeadRotO,
            float yHeadRot) {}

    public record Walk(float position, float speedOld, float speed) {}

    public record VehicleAngles(
            float prevRoll,
            float roll,
            float turretYRotO,
            float turretYRot,
            float turretXRotO,
            float turretXRot,
            float gunYRotO,
            float gunYRot,
            float gunXRotO,
            float gunXRot) {}

    /**
     * For vehicle records (identity kind 1/2) the base yaw/pitch endpoint pairs reuse the player
     * angle slots: {@code yRotO/yRot} carry yawOld/yaw and {@code xRotO/xRot} carry pitchOld/pitch.
     * Roll, turret and gun pairs stay in {@link #vehicleAngles}, every other vehicle dependency in
     * {@link #reads}.
     */
    public record Placement(
            Vec3 previousOrigin,
            Vec3 origin,
            Angles angles,
            Walk walk,
            Vec3 deltaMovementOnPreviousTick,
            Vec3 deltaMovement,
            VehicleAngles vehicleAngles,
            List<VehicleRead> reads) {
        public Placement {
            Objects.requireNonNull(previousOrigin, "previousOrigin");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(angles, "angles");
            Objects.requireNonNull(walk, "walk");
            Objects.requireNonNull(deltaMovementOnPreviousTick, "deltaMovementOnPreviousTick");
            Objects.requireNonNull(deltaMovement, "deltaMovement");
            Objects.requireNonNull(vehicleAngles, "vehicleAngles");
            reads = List.copyOf(reads);
            AnimationSchema.count(reads.size(), AnimationSchema.MAX_NODES, "reads");
        }
    }

    public record Attack(
            float oAttackAnim,
            float attackAnim,
            boolean swinging,
            int swingTime,
            @Nullable Hand swingingArm) {}

    public record Vital(
            int tickCount,
            int deathTime,
            int hurtTime,
            int hurtDuration,
            int fallFlyTicks,
            int ticksFrozen) {}

    public record Flags(
            boolean alive,
            boolean spectator,
            boolean baby,
            boolean crouching,
            boolean visuallySwimming,
            boolean swimming,
            boolean fallFlying,
            boolean autoSpinAttack,
            boolean onGround,
            boolean inWater,
            boolean inBubbleColumn,
            boolean fullyFrozen,
            boolean passenger,
            boolean sprinting,
            boolean creativeFlying,
            boolean climbing,
            boolean upsideDown) {}

    public record Size(
            float scale, float bbHeight, float bbWidth, float standingEyeHeight, float eyeHeight) {}

    public record ItemUse(
            boolean usingItem,
            Hand usedItemHand,
            int useItemRemaining,
            int ticksUsingItem,
            ArmPose mainArmPose,
            ArmPose offArmPose,
            UseAnimation useAnimation,
            int useDuration,
            int crossbowChargeDuration) {
        public ItemUse {
            Objects.requireNonNull(usedItemHand, "usedItemHand");
            Objects.requireNonNull(mainArmPose, "mainArmPose");
            Objects.requireNonNull(offArmPose, "offArmPose");
            Objects.requireNonNull(useAnimation, "useAnimation");
        }
    }

    public record Vanilla(
            Attack attack,
            float swimAmountO,
            float swimAmount,
            float oBob,
            float bob,
            Vital vital,
            Pose pose,
            Flags flags,
            float fallDistance,
            Arm mainArm,
            @Nullable Direction bedOrientation,
            Size size,
            double gravity,
            ItemUse itemUse,
            boolean medkitActive,
            @Nullable Hand medkitHand) {
        public Vanilla {
            Objects.requireNonNull(attack, "attack");
            Objects.requireNonNull(vital, "vital");
            Objects.requireNonNull(pose, "pose");
            Objects.requireNonNull(flags, "flags");
            Objects.requireNonNull(mainArm, "mainArm");
            Objects.requireNonNull(size, "size");
            Objects.requireNonNull(itemUse, "itemUse");
        }
    }

    public record DimensionsValue(
            float width, float height, float eyeHeight, boolean fixed, AttachmentRef attachments) {
        public DimensionsValue {
            Objects.requireNonNull(attachments, "attachments");
        }
    }

    public record Stance(
            int age,
            boolean eased,
            float fromFlat,
            float toFlat,
            float flat,
            float fromCrouch,
            float toCrouch,
            float crouch,
            DimensionsValue from,
            DimensionsValue to,
            DimensionsValue current) {
        public Stance {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(current, "current");
        }
    }

    public record Equipment(
            EquipmentRef equipmentRef,
            ItemRef mainHandRef,
            ItemRef offHandRef,
            ItemRef useItemRef) {
        public Equipment {
            Objects.requireNonNull(equipmentRef, "equipmentRef");
            Objects.requireNonNull(mainHandRef, "mainHandRef");
            Objects.requireNonNull(offHandRef, "offHandRef");
            Objects.requireNonNull(useItemRef, "useItemRef");
        }
    }

    /**
     * TaCZ third-person checkpoint; the enclosing state's {@code tacz} is null when no TaCZ gun is
     * held (native {@code setRotationAnglesHead} no-gun {@code stopAllAnimation}), a first-class
     * state for empty-handed, LR melee, ParCool-only, SBW-seat and holster-fade remotes.
     */
    public record Tacz(
            EvaluatorKind evaluatorKind,
            DefinitionRef gunId,
            @Nullable DefinitionRef displayOverrideId,
            DefinitionRef resolvedDisplayId,
            boolean fixedHand,
            float aimProgress,
            @Nullable ClipRef lowerClip,
            @Nullable ClipRef upperClip,
            @Nullable ClipRef actionClip,
            @Nullable AnimationStartReport.StartKey lowerStart,
            @Nullable AnimationStartReport.StartKey upperStart,
            @Nullable AnimationStartReport.StartKey actionStart,
            boolean exhausted,
            boolean proneAtEvent,
            @Nullable DefinitionRef meleeVariant,
            Outcome outcome) {
        public Tacz {
            Objects.requireNonNull(evaluatorKind, "evaluatorKind");
            Objects.requireNonNull(gunId, "gunId");
            Objects.requireNonNull(resolvedDisplayId, "resolvedDisplayId");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /**
     * LR third-person checkpoint. {@code attackAction} is null for the legal idle state (native
     * {@code Stack.attackAction = null}; {@code extraData["name"]} absent) and names the accepted
     * attack otherwise; never a {@code ""} sentinel ({@code
     * ThirdPersonAnimationConfig.getAnimation} returns null, not {@code ""}, on missing paths and
     * holds no string constants). {@code config} and {@code display} are null on a missing-config
     * tick (native {@code IdleAnimationHandler.getConfig} returns null for non-melee/undisplayed
     * items and {@code onClientTick} routes it to {@code stopAll}); that tick carries stopAll
     * topology plus a NO_CHANGE decision, never silently reused last-config bytes.
     */
    public record Lr(
            @Nullable ConfigRef config,
            @Nullable DefinitionRef display,
            @Nullable ClipRef upperSelection,
            @Nullable ClipRef lowerSelection,
            @Nullable String attackAction,
            int actionCount,
            @Nullable DefinitionRef attackDisplay,
            boolean attackActive,
            boolean rotationEnabled,
            int rotationFade,
            boolean fixedHand,
            boolean passenger,
            List<PendingOperation> pending) {
        public Lr {
            pending = List.copyOf(pending);
            AnimationSchema.count(pending.size(), AnimationSchema.MAX_NODES, "pending");
        }
    }

    public record PendingOperation(
            long serverTick, long sequence, String action, int count, DefinitionRef display) {
        public PendingOperation {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(display, "display");
        }
    }

    public record Option(boolean animationCanceled, int allowedParts) {
        public Option {
            if ((allowedParts & ~127) != 0)
                throw new IllegalArgumentException("Unknown animation option bits");
        }

        public boolean allows(AnimationPart part) {
            return (allowedParts & (1 << part.id())) != 0;
        }
    }

    public record Passive(
            int fallingAnimationTick, int flyingAnimationLevelOld, int flyingAnimationLevel) {}

    public record Grid4(
            long baseIndex, float previous, float start, float first, float second, float third) {}

    /**
     * Hang keeps the preceding interval endpoint in {@code yaw.previous} (parallel to the Dive
     * preceding factor) alongside the four interval yaws and the constructor yaw, so any fractional
     * point of the retained interval is samplable without a live render history.
     */
    public record HangGrid(Grid4 yaw, float constructorYaw) {
        public HangGrid {
            Objects.requireNonNull(yaw, "yaw");
        }
    }

    public record DiveGrid(Grid4 factor) {
        public DiveGrid {
            Objects.requireNonNull(factor, "factor");
        }
    }

    public record ParCool(
            @Nullable AnimationGraphState.NodeRef animator,
            AnimationStartReport.StartKey passiveStart,
            Option option,
            Passive passive,
            List<ActionState> actions,
            HangGrid hangGrid,
            DiveGrid diveGrid) {
        public ParCool {
            Objects.requireNonNull(passiveStart, "passiveStart");
            Objects.requireNonNull(option, "option");
            Objects.requireNonNull(passive, "passive");
            actions = List.copyOf(actions);
            AnimationSchema.count(actions.size(), AnimationSchema.MAX_NODES, "actions");
            Objects.requireNonNull(hangGrid, "hangGrid");
            Objects.requireNonNull(diveGrid, "diveGrid");
        }
    }

    public record ActionState(
            ActionTag action,
            boolean doing,
            int doingTick,
            int notDoingTick,
            int tickFromStarted,
            List<ActionInput> inputs) {
        public ActionState {
            Objects.requireNonNull(action, "action");
            inputs = List.copyOf(inputs);
            AnimationSchema.count(inputs.size(), AnimationSchema.MAX_NODES, "action inputs");
        }
    }

    public sealed interface ActionInput
            permits AcceptedStart,
                    DurationInput,
                    SlideInput,
                    HorizontalWallRunInput,
                    VerticalWallRunInput,
                    WallSlideInput,
                    HangInput,
                    ClingInput,
                    DiveInput,
                    ChargeInput,
                    ZiplineInput,
                    HideInput,
                    IntentInput,
                    PassiveInput,
                    CompatibilityInput,
                    NoInput {}

    public record AcceptedStart(
            ActionTag action,
            int variant,
            boolean flag,
            @Nullable Vec3 direction,
            @Nullable Vec3 run,
            double scalar,
            boolean valid)
            implements ActionInput {
        public AcceptedStart {
            Objects.requireNonNull(action, "action");
        }
    }

    public record DurationInput(int duration, ConfigRef configuredDuration) implements ActionInput {
        public DurationInput {
            Objects.requireNonNull(configuredDuration, "configuredDuration");
        }
    }

    public record SlideInput(@Nullable Vec3 slidingVec) implements ActionInput {}

    public record HorizontalWallRunInput(
            float bodyYaw,
            boolean wallIsRightward,
            @Nullable Vec3 runningWallDirection,
            @Nullable Vec3 runningDirection)
            implements ActionInput {}

    public record VerticalWallRunInput(double playerYSpeed, @Nullable Vec3 wallDirection)
            implements ActionInput {}

    public record WallSlideInput(@Nullable Vec3 leanedWallDirection) implements ActionInput {}

    public record HangInput(
            double bodySwingAngleFactor,
            float armSwingAmount,
            boolean orthogonalToBar,
            @Nullable BarAxis barAxis,
            float yawSeed,
            boolean valid)
            implements ActionInput {}

    public record ClingInput(
            float armSwingAmount,
            @Nullable FacingDirection facingDirection,
            @Nullable Vec3 clingWallDirection)
            implements ActionInput {}

    /**
     * Dive action inputs. The first five fields are {@code Dive} native fields verbatim; the stop
     * outcome triple is produced by {@code Dive.onStopInLocalClient/onStopInOtherClient}: {@code
     * fromSkyDive} is the {@code DiveIntoWaterAnimator(boolean)} constructor argument read from
     * {@code SkyDive.isDoing()}, {@code notLandingTick} is {@code
     * AdditionalProperties.getNotLandingTick()} (gated {@code >= 5}), and {@code waterEntry} is
     * {@code Entity.isInWaterOrBubble() && getDeltaMovement().y < 0}. There is deliberately no
     * {@code speed} field: pinned ParCool bf7 has no Dive speed producer or reader (the {@code
     * DiveAnimationHostAnimator(double, boolean)} constructor double is the synced y-speed, and the
     * child float is the transition blend factor); U5 derives Dive speed only from the y-speed
     * pair.
     */
    public record DiveInput(
            boolean justJumped,
            double initialYVelocityOfLastJump,
            double playerYSpeedOld,
            double playerYSpeed,
            int fallingTick,
            boolean fromSkyDive,
            int notLandingTick,
            boolean waterEntry)
            implements ActionInput {}

    public record ChargeInput(
            int chargeTick,
            int lastChargeTick,
            int notChargeTick,
            boolean started,
            int syncTick,
            boolean syncValid)
            implements ActionInput {}

    /**
     * RideZipline action inputs. {@code endOffsetFromStart}, {@code acceleration} (per-tick {@code
     * speed} delta from {@code onWorkingTickInLocalClient}) and {@code slope} (widened {@code
     * Zipline.getSlope(currentT)}) are {@code RideZipline} native fields read by {@code
     * RideZiplineAnimator}; {@code rope} is the {@code ridingZipline} entity. {@code gravity} is
     * not an action field: it is {@code LivingEntity.getAttributeValue(Attributes.GRAVITY)} read
     * live by both the action tick and the animator, recorded here redundantly with {@code
     * Vanilla.gravity} (same producer) because the animator reads it independently of the action.
     * {@code valid} is {@code ridingZipline != null}; native {@code speed} is internal recurrence
     * state recomputed from these inputs, not a wire field.
     */
    public record ZiplineInput(
            @Nullable Vec3 endOffsetFromStart,
            double acceleration,
            double slope,
            double gravity,
            boolean valid,
            @Nullable VehicleRef rope)
            implements ActionInput {}

    public record HideInput(@Nullable Vec3 lookDirection, boolean startedFromDiving)
            implements ActionInput {}

    public record IntentInput(boolean forward, boolean back, boolean left, boolean right)
            implements ActionInput {}

    public record PassiveInput(
            int notLandingTick,
            boolean ground,
            boolean fall,
            boolean flying,
            boolean cling,
            float fallDistance)
            implements ActionInput {}

    public record CompatibilityInput(
            EquipmentRef heldEquipment, AnimatorTag animator, long policyGeneration, Option option)
            implements ActionInput {
        public CompatibilityInput {
            Objects.requireNonNull(heldEquipment, "heldEquipment");
            Objects.requireNonNull(animator, "animator");
            Objects.requireNonNull(option, "option");
        }
    }

    public record NoInput() implements ActionInput {}

    public record Create(
            boolean hanging,
            @Nullable AnimationStartReport.StartKey membershipStart,
            long phaseAnchor,
            int u0,
            AnchorClass anchorClass) {
        public Create {
            Objects.requireNonNull(anchorClass, "anchorClass");
        }
    }

    public record Sbw(
            boolean parachuteOpen,
            boolean gun,
            @Nullable String seatPose,
            int directVehicleEntityId,
            int rootVehicleEntityId,
            int directSeatIndex,
            int rootSeatIndex,
            long directTargetEpoch,
            long directSeatGeneration,
            long directTeleportGeneration,
            long rootTargetEpoch,
            long rootSeatGeneration,
            long rootTeleportGeneration,
            @Nullable DefinitionRef directSeatDefinition,
            @Nullable DefinitionRef rootSeatDefinition,
            @Nullable DefinitionRef transformName,
            float scale,
            Visibility visibility) {
        public Sbw {
            Objects.requireNonNull(visibility, "visibility");
        }
    }

    public record ReadSlot(String declaringClass, String memberId, String descriptor, Role role) {
        public ReadSlot {
            Objects.requireNonNull(declaringClass, "declaringClass");
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(role, "role");
        }
    }

    public sealed interface ReadValue
            permits BooleanValue,
                    IntValue,
                    LongValue,
                    FloatValue,
                    DoubleValue,
                    EnumValue,
                    RefValue,
                    VectorValue,
                    QuaternionValue,
                    MatrixValue,
                    DoubleQuaternionValue,
                    DoubleMatrixValue {}

    public record BooleanValue(boolean value) implements ReadValue {}

    public record IntValue(int value) implements ReadValue {}

    public record LongValue(long value) implements ReadValue {}

    public record FloatValue(float value) implements ReadValue {}

    public record DoubleValue(double value) implements ReadValue {}

    public record EnumValue(DefinitionRef schema, int tag) implements ReadValue {
        public EnumValue {
            Objects.requireNonNull(schema, "schema");
        }
    }

    public record RefValue(ResourceRef value) implements ReadValue {
        public RefValue {
            Objects.requireNonNull(value, "value");
        }
    }

    public record VectorValue(Vec3 value) implements ReadValue {
        public VectorValue {
            Objects.requireNonNull(value, "value");
        }
    }

    public record QuaternionValue(float x, float y, float z, float w) implements ReadValue {}

    public record MatrixValue(
            float m00,
            float m01,
            float m02,
            float m03,
            float m10,
            float m11,
            float m12,
            float m13,
            float m20,
            float m21,
            float m22,
            float m23,
            float m30,
            float m31,
            float m32,
            float m33)
            implements ReadValue {}

    public record DoubleQuaternionValue(double x, double y, double z, double w)
            implements ReadValue {}

    public record DoubleMatrixValue(
            double m00,
            double m01,
            double m02,
            double m03,
            double m10,
            double m11,
            double m12,
            double m13,
            double m20,
            double m21,
            double m22,
            double m23,
            double m30,
            double m31,
            double m32,
            double m33)
            implements ReadValue {}

    public record VehicleRead(ReadSlot slot, ReadValue value) {
        public VehicleRead {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(value, "value");
        }
    }
}
