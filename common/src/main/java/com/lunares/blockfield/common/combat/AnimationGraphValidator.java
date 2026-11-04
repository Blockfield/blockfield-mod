package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationGraphState.*;
import static com.lunares.blockfield.common.combat.AnimationTypes.*;

import java.util.*;

/**
 * Host backlinks are validated separately from directed composition edges, which must be acyclic.
 */
public final class AnimationGraphValidator {
    private AnimationGraphValidator() {}

    /**
     * Recently validated complete graphs of this thread: {@code (graph, policy, nodes)} triples
     * matched by identity. A graph and everything it holds are immutable, so the same instance
     * always validates to the same node map; a refused graph is never cached.
     */
    private static final int RECENT = 1024;

    private record Validated(AnimationPolicy policy, Map<Integer, Node> nodes) {}

    private static final ThreadLocal<IdentityHashMap<AnimationGraphState, Validated>> VALIDATED =
            ThreadLocal.withInitial(IdentityHashMap::new);

    public static Map<Integer, Node> validateComplete(
            AnimationGraphState graph, AnimationPolicy policy) {
        var recent = VALIDATED.get();
        var known = recent.get(graph);
        if (known != null && known.policy() == policy) return known.nodes();
        var nodes = validateCompleteUncached(graph, policy);
        // ponytail: cleared when full (bounded memory); an LRU if hit rates ever matter.
        if (recent.size() >= RECENT) recent.clear();
        recent.put(graph, new Validated(policy, nodes));
        return nodes;
    }

    private static Map<Integer, Node> validateCompleteUncached(
            AnimationGraphState graph, AnimationPolicy policy) {
        Set<LayerTag> roots = java.util.EnumSet.noneOf(LayerTag.class);
        for (Root root : graph.roots()) {
            if (!roots.add(root.layer())
                    || root.priority() != AnimationGraphState.priority(root.layer()))
                throw new IllegalArgumentException("Invalid complete root inventory");
        }
        if (roots.size() != LayerTag.values().length)
            throw new IllegalArgumentException("Incomplete root inventory");
        return validate(graph, policy);
    }

    public static Map<Integer, Node> validate(AnimationGraphState graph, AnimationPolicy policy) {
        AnimationSchema.validateFinite(graph);
        AnimationSchema.count(graph.nodes().size(), AnimationSchema.MAX_NODES, "graph nodes");
        Map<Integer, Node> nodes = new LinkedHashMap<>();
        for (Node node : graph.nodes()) {
            if (node.id() <= 0 || nodes.put(node.id(), node) != null)
                throw new IllegalArgumentException("Duplicate/invalid graph node");
            if (node.start() != null) {
                AnimationSchema.nonnegative(node.start().eventSequence(), "graph start sequence");
                if (node.start().nodeId() <= 0)
                    throw new IllegalArgumentException("Invalid graph anchor reference");
            }
            if (node.data() instanceof Animator animator && !matches(animator))
                throw new IllegalArgumentException("Animator tag/state mismatch");
        }
        for (Node node : nodes.values()) {
            for (NodeRef ref : edges(node.data())) required(nodes, ref);
            if (node.data() instanceof LrFrozen frozen
                    && !(required(nodes, frozen.source()).data() instanceof KeyframePlayer))
                throw new IllegalArgumentException("Frozen source must be a keyframe actor");
            if (node.data() instanceof Animator actor
                    && actor.state() instanceof DiveHost hostState) {
                requireAnimator(nodes, hostState.diveAnimator(), AnimatorTag.DIVE_CHILD);
                if (hostState.skyDiveAnimator() != null)
                    requireAnimator(nodes, hostState.skyDiveAnimator(), AnimatorTag.SKY_DIVE_CHILD);
            }

            NodeRef host = host(node.data());
            if (host != null) {
                Node parent = required(nodes, host);
                if (!(parent.data() instanceof ModifierLayer layer)
                        || !layer.modifiers().contains(new NodeRef(node.id())))
                    throw new IllegalArgumentException("Modifier host mismatch");
                if (parent.domain() != node.domain())
                    throw new IllegalArgumentException("Cross-domain modifier host");
            }
        }
        Set<String> aliasKeys = new HashSet<>();
        for (Alias alias : graph.aliases()) {
            if (!aliasKeys.add(alias.associatedKey()))
                throw new IllegalArgumentException("Duplicate associated key alias");
            required(nodes, alias.winner());
        }
        Set<Integer> reachable = new HashSet<>();
        Map<Integer, Integer> heights = new HashMap<>();
        for (Root root : graph.roots())
            if (root.node() != null) {
                PhaseDomain domain = policy.domain(root.layer());
                visit(root.node(), nodes, domain, new HashSet<>(), reachable, heights, 0);
            }
        if (reachable.size() != nodes.size())
            throw new IllegalArgumentException("Unreachable graph node");
        return Map.copyOf(nodes);
    }

    private static void visit(
            NodeRef ref,
            Map<Integer, Node> nodes,
            PhaseDomain domain,
            Set<Integer> active,
            Set<Integer> reachable,
            Map<Integer, Integer> heights,
            int depth) {
        AnimationSchema.count(depth + 1, AnimationSchema.MAX_DEPTH, "graph depth");
        Node node = required(nodes, ref);
        if (node.domain() != domain)
            throw new IllegalArgumentException("Shared graph crosses phase domains");
        if (!active.add(node.id())) throw new IllegalArgumentException("Graph cycle");
        Integer cached = heights.get(node.id());
        if (cached != null) {
            AnimationSchema.count(depth + cached, AnimationSchema.MAX_DEPTH, "graph depth");
            active.remove(node.id());
            return;
        }
        reachable.add(node.id());
        int height = 1;
        for (NodeRef child : edges(node.data())) {
            visit(child, nodes, domain, active, reachable, heights, depth + 1);
            height = Math.max(height, 1 + heights.get(child.id()));
        }
        heights.put(node.id(), height);
        active.remove(node.id());
    }

    private static void requireAnimator(Map<Integer, Node> nodes, NodeRef ref, AnimatorTag tag) {
        if (!(required(nodes, ref).data() instanceof Animator actor) || actor.tag() != tag)
            throw new IllegalArgumentException("Wrong Dive child subtype");
    }

    private static Node required(Map<Integer, Node> nodes, NodeRef ref) {
        Node node = nodes.get(ref.id());
        if (node == null) throw new IllegalArgumentException("Dangling graph ref");
        return node;
    }

    public static List<NodeRef> edges(NodeData data) {
        List<NodeRef> refs = new ArrayList<>();
        switch (data) {
            case AnimationGraphState.Stack stack -> {
                for (Root root : stack.layers()) add(refs, root.node());
            }
            case Container c -> add(refs, c.child());
            case ModifierLayer layer -> {
                add(refs, layer.animation());
                refs.addAll(layer.modifiers());
            }
            case Modifier m -> add(refs, m.anim());
            case Fade f -> {
                add(refs, f.anim());
                add(refs, f.beginAnimation());
            }
            case Adjustment m -> add(refs, m.anim());
            case Speed m -> add(refs, m.anim());
            case LrForcedFade f -> {
                add(refs, f.anim());
                add(refs, f.beginAnimation());
            }
            case LrFrozen f -> add(refs, f.source());
            case Animator a -> {
                if (a.state() instanceof DiveHost h) {
                    add(refs, h.diveAnimator());
                    add(refs, h.skyDiveAnimator());
                }
            }
            case KeyframePlayer ignored -> {}
        }
        return List.copyOf(refs);
    }

    private static void add(List<NodeRef> refs, NodeRef ref) {
        if (ref != null) refs.add(ref);
    }

    private static NodeRef host(NodeData data) {
        return switch (data) {
            case Modifier m -> m.host();
            case Fade m -> m.host();
            case Adjustment m -> m.host();
            case Speed m -> m.host();
            case LrForcedFade m -> m.host();
            default -> null;
        };
    }

    private static boolean matches(Animator a) {
        return switch (a.tag()) {
            case BACKWARD_WALL_JUMP -> a.state() instanceof BackwardWallJump;
            case CAT_LEAP -> a.state() instanceof CatLeap;
            case CHARGE_JUMP -> a.state() instanceof ChargeJump;
            case CLIMB_UP -> a.state() instanceof ClimbUp;
            case CLING_TO_CLIFF -> a.state() instanceof ClingToCliff;
            case CRAWL -> a.state() instanceof Crawl;
            case DIVE_HOST -> a.state() instanceof DiveHost;
            case DIVE_INTO_WATER -> a.state() instanceof DiveIntoWater;
            case DODGE -> a.state() instanceof Dodge;
            case FAST_RUNNING -> a.state() instanceof FastRunning;
            case FAST_SWIM -> a.state() instanceof FastSwim;
            case FLIPPING -> a.state() instanceof Flipping;
            case HIDE_IN_BLOCK -> a.state() instanceof HideInBlock;
            case HORIZONTAL_WALL_RUN -> a.state() instanceof HorizontalWallRun;
            case JUMP_CHARGING -> a.state() instanceof JumpCharging;
            case JUMP_FROM_BAR -> a.state() instanceof JumpFromBar;
            case HANG -> a.state() instanceof Hang;
            case KONG_VAULT -> a.state() instanceof KongVault;
            case RIDE_ZIPLINE -> a.state() instanceof RideZipline;
            case ROLL -> a.state() instanceof Roll;
            case SLIDING -> a.state() instanceof Sliding;
            case SPEED_VAULT -> a.state() instanceof SpeedVault;
            case TAP -> a.state() instanceof Tap;
            case VERTICAL_WALL_RUN -> a.state() instanceof VerticalWallRun;
            case WALL_JUMP -> a.state() instanceof WallJump;
            case WALL_SLIDE -> a.state() instanceof WallSlide;
            case DIVE_CHILD -> a.state() instanceof DiveChild;
            case SKY_DIVE_CHILD -> a.state() instanceof SkyDiveChild;
        };
    }
}
