package com.lunares.blockfield.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Inject;

class MatchViewClientTest {
    @Test
    void raiseToAnnouncedAndRestoreOnLeave() {
        assertEquals(22, MatchViewClient.onRadius(22, 12));
        assertEquals(12, MatchViewClient.onRadius(8, 22));
    }

    @Test
    void highPlayerSettingIsUntouched() {
        assertEquals(32, MatchViewClient.onRadius(22, 32));
    }

    @Test
    void clampsToTheClientCap() {
        assertEquals(32, MatchViewClient.onRadius(40, 12));
        assertEquals(12, MatchViewClient.onRadius(8, 32));
    }

    @Test
    void manualSliderMoveWinsOverTheRememberedValue() {
        assertEquals(22, MatchViewClient.onRadius(22, 12));
        assertEquals(30, MatchViewClient.onRadius(22, 30));
        assertEquals(30, MatchViewClient.onRadius(8, 30));
    }

    @Test
    void quittingMidMatchRestores() {
        assertEquals(22, MatchViewClient.onRadius(22, 12));
        assertEquals(12, MatchViewClient.onLeave(22));
    }

    @Test
    void mixinTargetsAreDeclaredOnClientPacketListener() throws Exception {
        // Mixin only finds methods the target class declares itself, not inherited ones
        // (`onDisconnect` lives on the superclass): a miss crashes the client at boot
        // (`required: true`).
        var declared = new HashSet<String>();
        for (var m : ClientPacketListener.class.getDeclaredMethods())
            declared.add(m.getName() + Arrays.toString(m.getParameterTypes()));
        var mixin = Class.forName("com.lunares.blockfield.client.mixin.MatchViewClientMixin");
        int injects = 0;
        for (var handler : mixin.getDeclaredMethods()) {
            var inject = handler.getAnnotation(Inject.class);
            if (inject == null) continue;
            var params = handler.getParameterTypes();
            var target = Arrays.toString(Arrays.copyOf(params, params.length - 1));
            for (String name : inject.method()) {
                assertTrue(declared.contains(name + target), name + target);
                injects++;
            }
        }
        assertEquals(3, injects);
    }
}
