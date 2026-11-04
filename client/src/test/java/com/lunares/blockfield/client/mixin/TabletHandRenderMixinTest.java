package com.lunares.blockfield.client.mixin;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.item.ItemDisplayContext;
import org.junit.jupiter.api.Test;

class TabletHandRenderMixinTest {
    @Test
    void cachedAnimatedTabletCannotFallBackAfterHolsterButOtherItemsAndContextsStillRender()
            throws Exception {
        var guard =
                TabletHandRenderMixin.class.getDeclaredMethod(
                        "blockfield$suppressTabletFallback",
                        boolean.class,
                        ItemDisplayContext.class);
        guard.setAccessible(true);
        for (boolean animated : new boolean[] {true, false}) {
            for (var context : ItemDisplayContext.values()) {
                boolean firstPerson =
                        context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                                || context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
                assertEquals(animated && firstPerson, guard.invoke(null, animated, context));
            }
        }
    }
}
