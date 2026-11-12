package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationFixtures.*;
import static com.lunares.blockfield.common.combat.AnimationGraphState.*;
import static com.lunares.blockfield.common.combat.AnimationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AnimationGraphTest {
    @Test
    void nestedFadeFrozenSourceModifierHostAndAliasRetainIdentity() {
        var nodes =
                List.of(
                        node(1, new ModifierLayer(new NodeRef(2), List.of(new NodeRef(3)))),
                        node(2, new LrFrozen(new NodeRef(4))),
                        node(
                                3,
                                new Fade(
                                        new NodeRef(1),
                                        new NodeRef(2),
                                        2,
                                        5,
                                        new NodeRef(5),
                                        Ease.INOUTSINE,
                                        FadeType.STANDARD,
                                        null)),
                        node(4, sample(KeyframePlayer.class)),
                        node(5, new Container(new NodeRef(4))));
        var graph =
                new AnimationGraphState(
                        List.of(
                                new Root(42, LayerTag.PA_42, new NodeRef(1)),
                                new Root(96, LayerTag.PA_96, new NodeRef(5))),
                        nodes,
                        List.of(new Alias("tacz:rotation", new NodeRef(5))));
        assertEquals(5, AnimationGraphValidator.validate(graph, policy()).size());
        var restored = roundTrip(graph);
        assertEquals(graph, restored);
        assertEquals(new NodeRef(5), restored.aliases().getFirst().winner());
        byte[] before = AnimationSchema.encode(graph);
        for (int i = 0; i < 8; i++)
            assertArrayEquals(before, AnimationSchema.encode(roundTrip(graph)));
    }

    @Test
    void cyclesDuplicateIdsDanglingRefsUnreachableNodesAndBadHostsFail() {
        reject(List.of(node(1, new Container(new NodeRef(1)))));
        reject(
                List.of(
                        node(1, new Container(new NodeRef(2))),
                        node(2, new Container(new NodeRef(1)))));
        reject(List.of(node(1, new Container(new NodeRef(9)))));
        reject(List.of(node(1, new Container(null)), node(1, new Container(null))));
        reject(List.of(node(1, new Container(null)), node(2, new Container(null))));
        reject(
                List.of(
                        node(1, new Speed(new NodeRef(2), null, 2f / 3, 0, 0)),
                        node(2, new Container(null))));
        reject(List.of(node(1, new Animator(AnimatorTag.ROLL, 2, new Dodge(DodgeDirection.LEFT)))));
    }

    @Test
    void frozenCopiesRetainAncestorAnchorWithoutReusingPhysicalNodeIdentity() {
        var actor =
                new Node(
                        2,
                        PhaseDomain.A,
                        new AnimationStartReport.StartKey(5, 99),
                        sample(KeyframePlayer.class));
        var frozen = graph(List.of(node(1, new LrFrozen(new NodeRef(2))), actor));
        AnimationGraphValidator.validate(frozen, policy());
        assertEquals(frozen, roundTrip(frozen));
        reject(List.of(node(1, new LrFrozen(new NodeRef(2))), node(2, new Container(null))));
        reject(
                List.of(
                        node(
                                1,
                                new Animator(
                                        AnimatorTag.DIVE_HOST,
                                        0,
                                        new DiveHost(
                                                new NodeRef(2), null, false, false, false, 0))),
                        node(2, new Animator(AnimatorTag.ROLL, 0, new Roll(RollDirection.FRONT)))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AnimationGraphValidator.validateComplete(
                                new AnimationGraphState(List.of(), List.of()), policy()));
        AnimationGraphValidator.validateComplete(AnimationGraphState.neutral(), policy());
    }

    private static void reject(List<Node> nodes) {
        assertThrows(
                IllegalArgumentException.class,
                () -> AnimationGraphValidator.validate(graph(nodes), policy()));
    }

    @Test
    void graphDepthAndCollectionLimitsAreFailureBounds() {
        var nodes = new ArrayList<Node>();
        for (int i = 1; i <= 64; i++)
            nodes.add(node(i, new Container(i == 64 ? null : new NodeRef(i + 1))));
        AnimationGraphValidator.validate(graph(nodes), policy());
        nodes.set(63, node(64, new Container(new NodeRef(65))));
        nodes.add(node(65, new Container(null)));
        reject(nodes);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AnimationGraphState(
                                List.of(),
                                Collections.nCopies(4097, node(1, new Container(null)))));
        assertEquals(
                com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket.MAX_RECORDS,
                AnimationSchema.MAX_VICTIMS);
    }

    @Test
    void onlyCompleteLowerSubtreesMayUseR() {
        var rPolicy =
                new AnimationPolicy(
                        0, PhaseDomain.R, true, true, true, true, AnimationPolicy.registered());
        var rNode = new Node(1, PhaseDomain.R, null, new Container(null));
        var root = new Root(41, LayerTag.PA_41, new NodeRef(1));
        AnimationGraphValidator.validate(
                new AnimationGraphState(List.of(root), List.of(rNode)), rPolicy);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AnimationGraphValidator.validate(
                                new AnimationGraphState(
                                        List.of(root, new Root(94, LayerTag.PA_94, new NodeRef(1))),
                                        List.of(rNode)),
                                rPolicy));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AnimationPolicy(
                                0,
                                PhaseDomain.A,
                                false,
                                true,
                                true,
                                true,
                                AnimationPolicy.registered()));
        assertEquals(26, AnimationPolicy.registered().size());
        assertEquals(28, AnimatorTag.values().length);
        assertEquals(17, LayerTag.values().length);
        assertThrows(
                IllegalArgumentException.class,
                () -> Enum.valueOf(AnimatorTag.class, "EXAGGERATED_SIDE_DODGE"));
    }
}
