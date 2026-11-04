package com.lunares.blockfield.client.tacz;

import static org.junit.jupiter.api.Assertions.*;

import com.tacz.guns.api.client.animation.AnimationController;
import com.tacz.guns.api.client.animation.ObjectAnimation;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalPlayerAimInspectTest {
    @Test
    void cancelsOnlyInspectWithDefaultOrCustomTrackOrder() throws Exception {
        var stop =
                LocalPlayerAimInspectMixin.class.getDeclaredMethod(
                        "blockfield$stopInspect", AnimationController.class);
        assertTrue(
                java.lang.reflect.Modifier.isPrivate(stop.getModifiers()),
                "Mixin requires private static helpers");
        stop.setAccessible(true);
        for (boolean customOrder : new boolean[] {false, true}) {
            var controller = new Controller();
            assertNull(controller.getUpdatingTrackArray());
            stop.invoke(null, controller);
            for (String name : List.of("reload", "inspect", "draw", "inspect_empty")) {
                controller
                        .blockfield$getCurrentRunners()
                        .add(new ObjectAnimationRunner(new ObjectAnimation(name) {}));
            }
            controller.blockfield$getCurrentRunners().add(null);
            if (customOrder) controller.setUpdatingTrackArray(List.of(4, 3, 2, 1, 0));

            stop.invoke(null, controller);

            assertEquals("reload", controller.getAnimation(0).getAnimation().name);
            assertNull(controller.getAnimation(1));
            assertEquals("draw", controller.getAnimation(2).getAnimation().name);
            assertNull(controller.getAnimation(3));
            assertNull(controller.getAnimation(4));
        }
    }

    private static final class Controller extends AnimationController
            implements AnimationControllerAccessor {
        Controller() {
            super(List.of(), null);
        }

        @Override
        public ArrayList<ObjectAnimationRunner> blockfield$getCurrentRunners() {
            return currentRunners;
        }
    }
}
