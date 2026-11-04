package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationStartReport;
import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.common.combat.CombatView;
import java.util.Map;

/**
 * H is frozen with R and the driven samples, never read again at outbound-send time. Every
 * frame/revision additionally freezes A, its identities and the complete victim report (or the
 * label of why no exact report exists for it).
 */
public record CombatRenderReceipt(
        long observerStreamEpoch,
        long high,
        long tick,
        int alpha,
        Map<Integer, SnapshotResolver.Sample> entities,
        Exact exact) {
    public CombatRenderReceipt {
        entities = Map.copyOf(entities);
    }

    /** The ADR4 part of one immutable presentation revision. */
    public record Exact(
            long connectionId,
            long sceneGeneration,
            long frameId,
            long revision,
            Time a,
            long resourceGeneration,
            long policyGeneration,
            AnimationResources.Digest compatibilityDigest,
            AnimationStartReport report,
            String unavailable) {}

    public double time() {
        return tick + alpha / 65536.0;
    }

    /**
     * The ticket of one attack against this receipt: the complete ADR4 receipt, or R without any
     * victim rows when this revision has no complete report (never a partial set). Without a ticket
     * the server could only guess R; with it, it rewinds retained server poses to R.
     */
    public CombatView ticket(long sequence) {
        return new CombatView(
                sequence,
                observerStreamEpoch,
                high,
                tick,
                alpha,
                new CombatView.Receipt(
                        exact.connectionId(),
                        exact.sceneGeneration(),
                        exact.frameId(),
                        exact.revision(),
                        exact.a(),
                        exact.resourceGeneration(),
                        exact.policyGeneration(),
                        exact.compatibilityDigest(),
                        exact.report()));
    }
}
