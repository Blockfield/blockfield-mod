package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationSchema.Nullable;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.List;
import java.util.Objects;

public record AnimationGraphState(List<Root> roots, List<Node> nodes, List<Alias> aliases) {
    public AnimationGraphState(List<Root> roots, List<Node> nodes) {
        this(roots, nodes, List.of());
    }

    public AnimationGraphState {
        aliases = List.copyOf(aliases);
        AnimationSchema.count(aliases.size(), AnimationSchema.MAX_NODES, "aliases");
        roots = List.copyOf(roots);
        AnimationSchema.count(roots.size(), AnimationSchema.MAX_NODES, "roots");
        nodes = List.copyOf(nodes);
        AnimationSchema.count(nodes.size(), AnimationSchema.MAX_NODES, "nodes");
    }

    public static AnimationGraphState neutral() {
        return new AnimationGraphState(
                java.util.Arrays.stream(LayerTag.values())
                        .map(layer -> new Root(priority(layer), layer, null))
                        .toList(),
                List.of());
    }

    public static int priority(LayerTag layer) {
        return switch (layer) {
            case PA_40 -> 40;
            case PA_41 -> 41;
            case PA_42 -> 42;
            case PA_93 -> 93;
            case PA_94 -> 94;
            case PA_95 -> 95;
            case PA_96 -> 96;
            default -> 0;
        };
    }

    public record NodeRef(int id) {}

    public record Alias(String associatedKey, NodeRef winner) {
        public Alias {
            Objects.requireNonNull(associatedKey);
            Objects.requireNonNull(winner);
        }
    }

    public record MetadataOverlay(String name, boolean lrAttackAction) {
        public MetadataOverlay {
            Objects.requireNonNull(name);
        }
    }

    public record Root(int priority, LayerTag layer, @Nullable NodeRef node) {
        public Root {
            Objects.requireNonNull(layer, "layer");
        }
    }

    public record Node(
            int id,
            PhaseDomain domain,
            @Nullable AnimationStartReport.StartKey start,
            NodeData data) {
        public Node {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(data, "data");
        }
    }

    public sealed interface NodeData
            permits Stack,
                    Container,
                    ModifierLayer,
                    Modifier,
                    KeyframePlayer,
                    Fade,
                    Adjustment,
                    Speed,
                    LrFrozen,
                    LrForcedFade,
                    Animator {}

    public record Stack(List<Root> layers) implements NodeData {
        public Stack {
            layers = List.copyOf(layers);
            AnimationSchema.count(layers.size(), AnimationSchema.MAX_NODES, "layers");
        }
    }

    public record Container(@Nullable NodeRef child) implements NodeData {}

    public record ModifierLayer(@Nullable NodeRef animation, List<NodeRef> modifiers)
            implements NodeData {
        public ModifierLayer {
            modifiers = List.copyOf(modifiers);
            AnimationSchema.count(modifiers.size(), AnimationSchema.MAX_NODES, "modifiers");
        }
    }

    public record Modifier(NodeRef host, @Nullable NodeRef anim) implements NodeData {
        public Modifier {
            Objects.requireNonNull(host, "host");
        }
    }

    public record KeyframePlayer(
            ClipRef data,
            boolean isRunning,
            int currentTick,
            boolean isLoopStarted,
            float tickDelta,
            int perspective,
            FirstPersonRef firstPersonConfiguration,
            FirstPersonMode firstPersonMode,
            MetadataRef actorMetadata,
            MetadataOverlay metadata,
            List<PartState> bodyParts)
            implements NodeData {
        public KeyframePlayer {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(firstPersonConfiguration, "firstPersonConfiguration");
            Objects.requireNonNull(firstPersonMode, "firstPersonMode");
            Objects.requireNonNull(actorMetadata, "actorMetadata");
            Objects.requireNonNull(metadata, "metadata");
            bodyParts = List.copyOf(bodyParts);
            AnimationSchema.count(bodyParts.size(), AnimationSchema.MAX_NODES, "bodyParts");
        }
    }

    public record Fade(
            NodeRef host,
            @Nullable NodeRef anim,
            int time,
            int length,
            @Nullable NodeRef beginAnimation,
            Ease ease,
            FadeType subtype,
            @Nullable FunctionRef function)
            implements NodeData {
        public Fade {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(ease, "ease");
            Objects.requireNonNull(subtype, "subtype");
        }
    }

    public record Adjustment(
            NodeRef host,
            @Nullable NodeRef anim,
            boolean enabled,
            int instructedFadeout,
            int remainingFadeout,
            AdjustmentSource source,
            List<PartModifier> parts)
            implements NodeData {
        public Adjustment {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(source, "source");
            parts = List.copyOf(parts);
            AnimationSchema.count(parts.size(), AnimationSchema.MAX_NODES, "parts");
        }
    }

    public record Speed(
            NodeRef host, @Nullable NodeRef anim, float speed, float delta, float shiftedDelta)
            implements NodeData {
        public Speed {
            Objects.requireNonNull(host, "host");
        }
    }

    public record LrFrozen(NodeRef source) implements NodeData {
        public LrFrozen {
            Objects.requireNonNull(source, "source");
        }
    }

    public record LrForcedFade(
            NodeRef host,
            @Nullable NodeRef anim,
            int time,
            int length,
            @Nullable NodeRef beginAnimation,
            Ease ease)
            implements NodeData {
        public LrForcedFade {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(ease, "ease");
        }
    }

    public record Animator(AnimatorTag tag, int tick, AnimatorData state) implements NodeData {
        public Animator {
            Objects.requireNonNull(tag, "tag");
            Objects.requireNonNull(state, "state");
        }
    }

    public record PartState(
            String name, DefinitionRef part, DefinitionRef keyframes, DefinitionRef axes) {
        public PartState {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(part, "part");
            Objects.requireNonNull(keyframes, "keyframes");
            Objects.requireNonNull(axes, "axes");
        }
    }

    public record PartModifier(
            String part,
            AnimationState.Vec3f rotation,
            AnimationState.Vec3f scale,
            AnimationState.Vec3f offset) {
        public PartModifier {
            Objects.requireNonNull(part, "part");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(scale, "scale");
            Objects.requireNonNull(offset, "offset");
        }
    }

    public sealed interface AnimatorData
            permits BackwardWallJump,
                    CatLeap,
                    ChargeJump,
                    ClimbUp,
                    ClingToCliff,
                    Crawl,
                    DiveHost,
                    DiveIntoWater,
                    Dodge,
                    FastRunning,
                    FastSwim,
                    Flipping,
                    HideInBlock,
                    HorizontalWallRun,
                    JumpCharging,
                    JumpFromBar,
                    Hang,
                    KongVault,
                    RideZipline,
                    Roll,
                    Sliding,
                    SpeedVault,
                    Tap,
                    VerticalWallRun,
                    WallJump,
                    WallSlide,
                    DiveChild,
                    SkyDiveChild {}

    public record BackwardWallJump(int maxTick) implements AnimatorData {}

    public record CatLeap() implements AnimatorData {}

    public record ChargeJump() implements AnimatorData {}

    public record ClimbUp() implements AnimatorData {}

    public record ClingToCliff(float leanAngle) implements AnimatorData {}

    public record Crawl() implements AnimatorData {}

    public record DiveHost(
            NodeRef diveAnimator,
            @Nullable NodeRef skyDiveAnimator,
            boolean fromInAir,
            boolean oldSkyDiveDoing,
            boolean transitioning,
            int transitionTick)
            implements AnimatorData {
        public DiveHost {
            Objects.requireNonNull(diveAnimator, "diveAnimator");
        }
    }

    public record DiveIntoWater(boolean fromSkyDive) implements AnimatorData {}

    public record Dodge(DodgeDirection direction) implements AnimatorData {
        public Dodge {
            Objects.requireNonNull(direction, "direction");
        }
    }

    public record FastRunning(float limbSwing) implements AnimatorData {}

    public record FastSwim() implements AnimatorData {}

    public record Flipping(FlipDirection direction) implements AnimatorData {
        public Flipping {
            Objects.requireNonNull(direction, "direction");
        }
    }

    public record HideInBlock(boolean standing, boolean startFromDiving) implements AnimatorData {}

    public record HorizontalWallRun(boolean wallIsRightSide, float limbSwing)
            implements AnimatorData {}

    public record JumpCharging() implements AnimatorData {}

    public record JumpFromBar() implements AnimatorData {}

    public record Hang() implements AnimatorData {}

    public record KongVault(float yRotDifference, float yRotDifferenceOld)
            implements AnimatorData {}

    public record RideZipline(double oldAngleRadian, double currentAngleRadian)
            implements AnimatorData {}

    public record Roll(RollDirection direction) implements AnimatorData {
        public Roll {
            Objects.requireNonNull(direction, "direction");
        }
    }

    public record Sliding() implements AnimatorData {}

    public record SpeedVault(VaultType type) implements AnimatorData {
        public SpeedVault {
            Objects.requireNonNull(type, "type");
        }
    }

    public record Tap() implements AnimatorData {}

    public record VerticalWallRun() implements AnimatorData {}

    public record WallJump(boolean wallRightSide, int maxTick) implements AnimatorData {}

    public record WallSlide() implements AnimatorData {}

    public record DiveChild(double initialYSpeed, float pitchAngle, float oldFactor)
            implements AnimatorData {}

    public record SkyDiveChild(
            int forwardAngleCount,
            int forwardAngleCountOld,
            int rightAngleCount,
            int rightAngleCountOld,
            float startPitchAngle,
            float pitchAngle)
            implements AnimatorData {}
}
