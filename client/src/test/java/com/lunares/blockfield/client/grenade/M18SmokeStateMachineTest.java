package com.lunares.blockfield.client.grenade;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

class M18SmokeStateMachineTest {
    @Test
    void nativeFourTickReleaseThrowsWithoutAFrameAtThePrepareThreshold() throws IOException {
        Harness h = new Harness();
        h.startUsing(3);
        assertEquals("unlock_safe", h.lastAnimation());
        assertEquals(0, h.context.progressWrites);
        h.context.using = false;
        h.context.usingTick = 0;
        h.input("m18_throw");
        h.update();
        assertSame(h.main.get("throwing"), h.state);
        assertEquals("throw", h.lastAnimation());
    }

    @Test
    void nativeCancellationBeforeFourTicksReturnsToIdleWithoutThrowing() throws IOException {
        Harness h = new Harness();
        h.startUsing(3);
        h.context.using = false;
        h.update();
        assertSame(h.main.get("idle"), h.state);
        assertFalse(h.context.animations.contains("throw"));
        assertEquals("static_idle", h.lastAnimation());
        assertEquals(0.12f, h.context.blendDuration);
        assertEquals(0, h.context.stops);
    }

    @Test
    void holdingAndCookingCompletionUseTheSameNativeThrowSignal() throws IOException {
        Harness h = new Harness();
        h.startUsing(4);
        assertSame(h.main.get("using"), h.state);
        h.context.animationHolding = true;
        h.update();
        assertSame(h.main.get("holding"), h.state);
        assertEquals("unlock_safe_loop", h.lastAnimation());
        h.context.usingTick = 80;
        h.input("m18_throw");
        assertSame(h.main.get("throwing"), h.state);
        assertEquals("throw", h.lastAnimation());
    }

    @Test
    void renderFramesDoNotScrubThePinPullIntoFourTicks() throws IOException {
        Harness h = new Harness();
        h.startUsing(0);
        for (int tick = 0; tick <= 22; tick++) {
            h.context.usingTick = tick;
            for (float partial : new float[] {0, 0.25f, 0.75f}) {
                h.context.partialTick = partial;
                h.update();
                assertSame(h.main.get("using"), h.state);
            }
        }
        assertEquals("unlock_safe", h.lastAnimation());
        assertEquals(0, h.context.progressWrites);
        h.context.animationHolding = true;
        h.update();
        assertSame(h.main.get("holding"), h.state);
        assertEquals("unlock_safe_loop", h.lastAnimation());
    }

    @Test
    void inspectCanBeInterruptedByUseAndPutAway() throws IOException {
        Harness h = new Harness();
        h.input("inspect");
        assertEquals("inspect", h.lastAnimation());
        h.startUsing(1);
        assertEquals("unlock_safe", h.lastAnimation());
        h.input("put_away");
        assertSame(h.main.get("final"), h.state);
        assertEquals("put_away", h.lastAnimation());
        assertEquals(1f, h.context.progress);
        assertEquals(-0.38f, h.context.adjustment);
    }

    @Test
    void exhaustedStackDoesNotRedrawAndRemainingStackDoes() throws IOException {
        Harness h = new Harness();
        h.input("m18_throw");
        h.context.animationHolding = true;
        h.context.stackCount = 0;
        h.update();
        assertSame(h.main.get("throwing"), h.state);
        assertEquals("throw", h.lastAnimation());
        h.context.stackCount = 1;
        h.update();
        assertSame(h.main.get("idle"), h.state);
        assertEquals("re_draw", h.lastAnimation());
    }

    private static final class Harness {
        final TestContext context = new TestContext();
        final LuaTable module;
        final LuaValue main;
        final LuaValue luaContext = CoerceJavaToLua.coerce(context);
        LuaValue state;

        Harness() throws IOException {
            var globals = JsePlatform.standardGlobals();
            globals.set("INPUT_DRAW", "draw");
            globals.set("INPUT_PUT_AWAY", "put_away");
            globals.set("INPUT_INSPECT", "inspect");
            globals.set("LOOP", 0);
            globals.set("PLAY_ONCE_STOP", 1);
            globals.set("PLAY_ONCE_HOLD", 2);
            try (var source =
                    getClass().getResourceAsStream("/assets/blockfield/scripts/m18_smoke.lua")) {
                assertNotNull(source);
                module = globals.load(source, "m18_smoke", "t", globals).call().checktable();
            }
            main = module.get("main");
            state = main.get("start");
            input("draw");
        }

        void input(String input) {
            LuaValue next =
                    state.get("transition").call(module, luaContext, LuaValue.valueOf(input));
            if (!next.isnil()) state = next;
        }

        void update() {
            state.get("update").call(module, luaContext);
            if (context.input != null) {
                String input = context.input;
                context.input = null;
                input(input);
            }
        }

        void startUsing(int ticks) {
            context.using = true;
            context.usingTick = ticks;
            update();
            update();
        }

        String lastAnimation() {
            return context.animations.getLast();
        }
    }

    public static final class TestContext {
        final List<String> animations = new ArrayList<>();
        boolean using;
        boolean animationHolding;
        int usingTick;
        int stackCount = 2;
        int stops;
        float progress;
        float adjustment;
        float partialTick;
        float blendDuration;
        int progressWrites;
        String input;

        public int getTrack(int line, int track) {
            return track;
        }

        public void runAnimation(String name, int track, boolean blend, int mode, float duration) {
            animations.add(name);
            blendDuration = duration;
        }

        public boolean isUsing() {
            return using;
        }

        public int getUsingTick() {
            return usingTick;
        }

        public float getPartialTicks() {
            return partialTick;
        }

        public int getPrepareTime() {
            return 4;
        }

        public int getStackCount() {
            return stackCount;
        }

        public boolean isHolding(int track) {
            return animationHolding;
        }

        public float getPutAwayTime() {
            return 0.38f;
        }

        public void trigger(String next) {
            input = next;
        }

        public void setAnimationProgress(int track, float value, boolean normalized) {
            progress = value;
            progressWrites++;
        }

        public void adjustAnimationProgress(int track, float value, boolean normalized) {
            adjustment = value;
        }

        public void stopAnimation(int track) {
            stops++;
        }
    }
}
