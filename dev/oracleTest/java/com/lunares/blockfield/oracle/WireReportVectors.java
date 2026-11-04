package com.lunares.blockfield.oracle;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.*;

/** Large valid logical receipts; authority checks are exercised separately with issued seeds. */
public final class WireReportVectors {
    private WireReportVectors() {}

    public static CombatView view(long sequence, int count) {
        var zero = AnimationResources.Digest.hash(new byte[0]);
        var proof =
                new AnimationStartReport.ObserverApply(
                        1, 1, 1, Time.whole(12), Time.whole(10), new Time(10, 0x4000), 13, 1);
        var start =
                new AnimationStartReport.Start(
                        1,
                        1,
                        LayerTag.PA_40,
                        StartKind.NEW,
                        AnchorClass.OBSERVER_APPLY,
                        PhaseDomain.A,
                        10,
                        0,
                        0,
                        proof);
        var rows = new ArrayList<AnimationStartReport.Victim>();
        for (int i = 0; i < count; i++)
            rows.add(
                    new AnimationStartReport.Victim(
                            i + 1, 1, 0, 0, 1, 1, 1, zero, List.of(start), List.of(), zero));
        return new CombatView(
                sequence,
                2,
                13,
                11,
                0,
                new CombatView.Receipt(
                        1,
                        3,
                        1,
                        1,
                        new Time(12, 0x8000),
                        1,
                        1,
                        zero,
                        new AnimationStartReport(rows)));
    }
}
