package com.lunares.blockfield.client.gui.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.isxander.yacl3.api.Controller;
import dev.isxander.yacl3.api.controller.ControllerBuilder;
import java.lang.reflect.Method;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

// Fail-before cover for the YACL fallback: Option.Builder.controller() takes a
// ControllerBuilder and builds it. Returning a built Controller fails YACL's cast
// in Option.build() (ClassCastException), skipping every option, so the screen
// falls back to the vanilla list with no categories.
class ModSettingsScreenControllerTest {
    @Test
    void controllerFunctionReturnsABuilder() throws Exception {
        Method boolController =
                ModSettingsScreen.class.getDeclaredMethod(
                        "boolController", Object.class, Function.class);
        boolController.setAccessible(true);
        Object controller = boolController.invoke(null, new Object[] {null, null});
        assertTrue(
                controller instanceof ControllerBuilder<?>,
                "controller() must return a ControllerBuilder, got "
                        + controller.getClass().getName());
        assertFalse(
                controller instanceof Controller<?>,
                "controller() must not return a built Controller");
    }
}
