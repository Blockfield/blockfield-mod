package com.lunares.blockfield.client.combat.oracle;

import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.lunares.blockfield.oracle.PaObserverNativeCases;
import org.junit.jupiter.api.Test;

class PaObserverOracleFixture {
    @Test
    void nativeObserverStartsSeedsAndArrivalPrefixes() {
        AnimSyncOracleRunner.run(
                "playerAnimator",
                "observer-seeds-arrival-prefixes",
                () -> {
                    var result = PaObserverNativeCases.run();
                    OracleTransformAudit.requireConfig("playerAnimator-common.mixins.json");
                    return result;
                });
    }
}
