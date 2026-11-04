package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.LrAssets;
import com.lunares.blockfield.common.combat.PaGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClientRetireCadenceTest {
    @AfterEach
    void reset() {
        ClientCombatScene.disconnect();
        ClientAnimationAssets.clear();
    }

    static ClientAnimationAssets.Generation generation(long gen) {
        return new ClientAnimationAssets.Generation(
                gen, ExactFixtures.policy(), null, new LrAssets(new PaGraph.Catalog(gen)));
    }

    @Test
    void retireRunsRarelyInsteadOfEveryTick() {
        ClientCombatScene.disconnect();
        // Unique generations: sibling test classes share this JVM and pin generation 0.
        double now = ClientCombatScene.nowMs();
        ClientAnimationAssets.install(generation(9001));
        ClientAnimationAssets.install(generation(9002));
        ClientCombatScene.retireResources(now);
        assertFalse(
                ClientAnimationAssets.generations().contains(9001L),
                "first retire drops the unreferenced generation");
        ClientAnimationAssets.install(generation(9001));
        // Reinstalling 9001 made it latest (always retained): point latest away again.
        ClientAnimationAssets.install(generation(9002));
        ClientCombatScene.retireResources(now + 100);
        assertTrue(
                ClientAnimationAssets.generations().contains(9001L),
                "a tick 100 ms later must not re-walk/retire");
        ClientCombatScene.retireResources(now + ClientCombatScene.RETIRE_INTERVAL_MS + 1);
        assertFalse(
                ClientAnimationAssets.generations().contains(9001L),
                "retire runs again after the interval");
        assertTrue(
                ClientAnimationAssets.generations().contains(9002L), "latest is always retained");
    }

    @Test
    void clientTickPathIsThrottled() {
        ClientCombatScene.disconnect();
        double now = ClientCombatScene.nowMs();
        ClientAnimationAssets.install(generation(9001));
        ClientAnimationAssets.install(generation(9002));
        ClientCombatScene.clientTick(now);
        assertFalse(ClientAnimationAssets.generations().contains(9001L));
        ClientAnimationAssets.install(generation(9001));
        ClientAnimationAssets.install(generation(9002));
        for (int n = 1; n < 20; n++) ClientCombatScene.clientTick(now + n * 50);
        assertTrue(
                ClientAnimationAssets.generations().contains(9001L),
                "19 ticks within the interval must not retire");
        ClientCombatScene.clientTick(now + ClientCombatScene.RETIRE_INTERVAL_MS + 20 * 50);
        assertFalse(ClientAnimationAssets.generations().contains(9001L));
    }
}
