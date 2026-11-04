package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alrex.parcool.client.animation.Animator;
import com.alrex.parcool.common.attachment.Attachments;
import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationGraphState.NodeRef;
import com.lunares.blockfield.oracle.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ParCoolZiplineOracleFixture {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nativeRootModelAndIntegerContinuationProduceDifferentialRows() {
        AnimSyncOracleRunner.run(
                "parcool",
                "zipline-root-model-native",
                () -> {
                    var adapter =
                            new ParCoolGraph(
                                    getClass().getClassLoader(),
                                    "com.alrex.parcool.client.animation.impl.");
                    var player = ParCoolCheckpointOracleFixture.player();
                    var rows = new ArrayList<Map<String, Object>>();
                    try (var singleton = mockStatic(Minecraft.class)) {
                        singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
                        Animator continued = null;
                        for (var checkpoint : ParCoolZiplineCases.checkpoints()) {
                            var graph = checkpoint.graph();
                            var frame = new ParCoolFrame(graph, checkpoint.state());
                            var attachment = frame.scratchAttachment();
                            when(player.getAttachedOrCreate(Attachments.PARKOURABILITY))
                                    .thenReturn(attachment);
                            var input = ParCoolZiplineCases.input(rows.size());
                            when(player.getAttributeValue(Attributes.GRAVITY))
                                    .thenReturn(input.gravity());
                            if (continued != null) {
                                continued.tick(player);
                                assertEquals(
                                        graph,
                                        adapter.freeze(
                                                AnimationGraphState.neutral(),
                                                continued,
                                                new AtomicInteger()::incrementAndGet,
                                                ignored -> null));
                            }
                            var scratch = (Animator) adapter.materialize(graph, new NodeRef(1));
                            var samples =
                                    ParCoolZiplineCases.samples(
                                            (fraction, modelFirst) ->
                                                    ParCoolCheckpointOracleFixture.sample(
                                                            scratch,
                                                            player,
                                                            attachment,
                                                            fraction,
                                                            modelFirst,
                                                            12));
                            assertEquals(
                                    graph,
                                    adapter.freeze(
                                            AnimationGraphState.neutral(),
                                            scratch,
                                            new AtomicInteger()::incrementAndGet,
                                            ignored -> null));
                            rows.add(Map.of("tick", rows.size(), "samples", samples));
                            // Reseeding after every native integer tick must retain the next 12
                            // steps.
                            continued = (Animator) adapter.materialize(graph, new NodeRef(1));
                        }
                    }
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    return Map.of(
                            "checkpoints",
                            rows,
                            "scalarOrder",
                            "root16,head/body/leftArm/rightArm/leftLeg/rightLeg9",
                            "fps",
                            List.of(30, 60, 144, 240),
                            "retainedStateUnchanged",
                            true);
                });
    }
}
