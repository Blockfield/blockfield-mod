package com.lunares.blockfield.client.combat.oracle;

import com.lunares.blockfield.oracle.OracleTransformAudit;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** LR's local-native metadata view through the full transformed renderer. */
class LrMixedRendererOracleFixture {
    @BeforeAll
    static void bootstrap() throws Exception {
        MixedRendererOracleFixture.bootstrap();
    }

    @Test
    void lrLocalMetadataViewPreservesTheFullNativeRender() {
        AnimSyncOracleRunner.run(
                "lr",
                "mixed-local-native-metadata",
                () -> {
                    var result = LrSelectorOracleFixture.mixedLocalNative();
                    for (var config :
                            List.of(
                                    "blockfield-client-lrtactical.mixins.json",
                                    "lrtactical.mixins.json",
                                    "playerAnimator-common.mixins.json",
                                    "tacz.mixins.json",
                                    "parcool.mixins.json"))
                        OracleTransformAudit.requireConfig(config);
                    return result;
                });
    }
}
