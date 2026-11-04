package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.lib.jse.JsePlatform;

class MedkitAnimationsTest {
    @Test
    void consecutiveHeldUsesRestartTheClipAndBothClipsFitOneSecond() throws Exception {
        var globals = JsePlatform.standardGlobals();
        globals.set("PLAY_ONCE_HOLD", 2);
        try (var source =
                getClass().getResourceAsStream("/assets/blockfield/scripts/equipment.lua")) {
            assertNotNull(source);
            globals.set("equipment", globals.load(source, "equipment", "t", globals).call());
        }
        globals.load(
                        """
                        local calls = 0
                        local ctx = {count = 2, using = true}
                        function ctx:getStackCount() return self.count end
                        function ctx:isUsing() return self.using end
                        function ctx:getTrack() return 1 end
                        function ctx:runAnimation(name)
                            assert(name == "use")
                            calls = calls + 1
                        end
                        function ctx:isHolding() return true end
                        function ctx:trigger(input) self.input = input end
                        local state = equipment.main.using
                        state.entry(equipment, ctx)
                        state.update(equipment, ctx)
                        assert(calls == 0)
                        ctx.count = 1
                        state.update(equipment, ctx)
                        assert(calls == 1)
                        state.update(equipment, ctx)
                        assert(calls == 1)
                        ctx.using = false
                        state.update(equipment, ctx)
                        assert(ctx.input == "finish_use")
                        """)
                .call();
        try (var source =
                getClass()
                        .getResourceAsStream(
                                "/assets/blockfield/animations/equipment/medical_kit.animation.json")) {
            assertNotNull(source);
            var clips =
                    JsonParser.parseReader(new InputStreamReader(source, StandardCharsets.UTF_8))
                            .getAsJsonObject()
                            .getAsJsonObject("animations");
            for (String name : new String[] {"use", "ground_use"}) {
                assertEquals(
                        0.95, clips.getAsJsonObject(name).get("animation_length").getAsDouble());
            }
        }
    }
}
