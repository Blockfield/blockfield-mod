package com.lunares.blockfield.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DecorInteractionsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void leversAndTrapdoorsAreLockedDoorsButtonsAndGatesAreNot() {
        assertTrue(DecorInteractions.isLocked(Blocks.LEVER.defaultBlockState()));
        assertTrue(DecorInteractions.isLocked(Blocks.OAK_TRAPDOOR.defaultBlockState()));
        assertTrue(DecorInteractions.isLocked(Blocks.IRON_TRAPDOOR.defaultBlockState()));
        assertFalse(DecorInteractions.isLocked(Blocks.OAK_DOOR.defaultBlockState()));
        assertFalse(DecorInteractions.isLocked(Blocks.STONE_BUTTON.defaultBlockState()));
        assertFalse(DecorInteractions.isLocked(Blocks.OAK_FENCE_GATE.defaultBlockState()));
    }
}
