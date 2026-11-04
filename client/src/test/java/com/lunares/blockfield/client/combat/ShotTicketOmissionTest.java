package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.common.combat.AnimationStartReport;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShotTicketOmissionTest {
    @Test
    void exactSendOmissionDistinguishesAbsentExpiredAndIncompleteReceipts() {
        assertEquals("no_receipt", ClientCombatScene.ticketOmission(null, 10, 10));
        var receipt = mock(CombatRenderReceipt.class);
        var exact = mock(CombatRenderReceipt.Exact.class);
        when(receipt.exact()).thenReturn(exact);
        when(exact.unavailable()).thenReturn("12:seed_window");
        assertEquals("deadline", ClientCombatScene.ticketOmission(receipt, 10, 10.001));
        assertEquals("report:12:seed_window", ClientCombatScene.ticketOmission(receipt, 10, 10));
        when(exact.report()).thenReturn(new AnimationStartReport(List.of()));
        assertEquals("", ClientCombatScene.ticketOmission(receipt, 10, 10));
    }
}
