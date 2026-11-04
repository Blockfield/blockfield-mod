package com.lunares.blockfield.common.combat;

import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.attachment.common.Parkourability;
import com.alrex.parcool.common.info.ClientSetting;
import com.alrex.parcool.common.stamina.StaminaType;
import com.alrex.parcool.config.ParCoolConfig;
import com.lunares.blockfield.common.combat.AnimationGraphState.NodeRef;
import com.lunares.blockfield.common.combat.AnimationState.ParCool;
import com.lunares.blockfield.common.combat.AnimationTypes.ActionTag;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import java.util.*;
import java.util.function.Function;
import java.util.function.IntSupplier;

/** Family operation result; only its two U1 values are serialized. */
public record ParCoolFrame(AnimationGraphState graph, ParCool state) {
    public ParCoolFrame {
        Objects.requireNonNull(graph);
        Objects.requireNonNull(state);
        AnimationSchema.validateFinite(state);
        if (!Objects.equals(active(graph), state.animator()))
            throw new IllegalArgumentException("ParCool root/checkpoint mismatch");
        actions(state);
    }

    /**
     * Post-tick capture. Reducer inputs/grids/passive/options are authoritative immutable values;
     * do not replace them with client-only fields left at defaults on the dedicated server.
     */
    public static ParCoolFrame freeze(
            ParCoolGraph adapter,
            AnimationGraphState base,
            Object canonicalAnimator,
            ParCool canonical,
            IntSupplier ids,
            Function<Object, AnimationStartReport.StartKey> starts) {
        var graph = adapter.freeze(base, canonicalAnimator, ids, starts);
        return new ParCoolFrame(
                graph,
                new ParCool(
                        active(graph),
                        canonical.passiveStart(),
                        canonical.option(),
                        canonical.passive(),
                        canonical.actions(),
                        canonical.hangGrid(),
                        canonical.diveGrid()));
    }

    /** Fresh geometry-only attachment; no live entity attachment is modified or retained. */
    @SuppressWarnings("unchecked")
    public Parkourability scratchAttachment() {
        var scratch = new Parkourability();
        List<Action> restored = actions(state).stream().map(ParCoolActions::materialize).toList();
        var list = scratch.getList();
        var index = (Map<Class<? extends Action>, Action>) ParCoolGraph.get(scratch, "actionsMap");
        list.clear();
        index.clear();
        for (Action action : restored) {
            list.add(action);
            index.put(action.getClass(), action);
        }
        scratch.getActionInfo().setClientSetting(GEOMETRY_POLICY);
        return scratch;
    }

    /** Recently ordered family values of this thread (immutable values, by identity). */
    private static final ThreadLocal<IdentityHashMap<ParCool, List<AnimationState.ActionState>>>
            ORDERED = ThreadLocal.withInitial(IdentityHashMap::new);

    /** Complete native registration order; missing and duplicate actions cannot use defaults. */
    public static List<AnimationState.ActionState> actions(ParCool state) {
        var ordered = ORDERED.get();
        var known = ordered.get(state);
        if (known != null) return known;
        var result = order(state);
        // ponytail: cleared when full (bounded memory); an LRU if hit rates ever matter.
        if (ordered.size() >= 1024) ordered.clear();
        ordered.put(state, result);
        return result;
    }

    private static List<AnimationState.ActionState> order(ParCool state) {
        var found = new EnumMap<ActionTag, AnimationState.ActionState>(ActionTag.class);
        for (var action : state.actions()) {
            if (found.put(action.action(), action) != null)
                throw new IllegalArgumentException("Duplicate ParCool action checkpoint");
            ParCoolActions.validateInputs(action);
        }
        if (found.size() != ActionTag.values().length)
            throw new IllegalArgumentException("Incomplete ParCool action checkpoint");
        return Arrays.stream(ActionTag.values()).map(found::get).toList();
    }

    private static NodeRef active(AnimationGraphState graph) {
        var roots =
                graph.roots().stream()
                        .filter(root -> root.layer() == LayerTag.PARCOOL_ACTIVE)
                        .toList();
        if (roots.size() != 1) throw new IllegalArgumentException("ParCool root inventory");
        return roots.getFirst().node();
    }

    // Only the three forced geometry getters are available in animation scratch. A new read is a
    // manifest/layout change, not permission to consult a local or unsynchronized configuration.
    private static final ClientSetting GEOMETRY_POLICY =
            new ClientSetting() {
                @Override
                public Boolean get(ParCoolConfig.Client.Booleans item) {
                    return switch (item) {
                        case EnableAnimation,
                                EnableFallingAnimation,
                                EnableLeanAnimationOfFastRun ->
                                true;
                        default ->
                                throw new IllegalStateException(
                                        "Unclassified ParCool geometry setting: " + item);
                    };
                }

                @Override
                public Integer get(ParCoolConfig.Client.Integers item) {
                    throw unavailable();
                }

                @Override
                public Double get(ParCoolConfig.Client.Doubles item) {
                    throw unavailable();
                }

                @Override
                public boolean getPossibilityOf(Class<? extends Action> action) {
                    throw unavailable();
                }

                @Override
                public int getStaminaConsumptionOf(Class<? extends Action> action) {
                    throw unavailable();
                }

                @Override
                public StaminaType getRequestedStamina() {
                    throw unavailable();
                }

                private IllegalStateException unavailable() {
                    return new IllegalStateException(
                            "Physics/config read in ParCool animation scratch");
                }
            };
}
