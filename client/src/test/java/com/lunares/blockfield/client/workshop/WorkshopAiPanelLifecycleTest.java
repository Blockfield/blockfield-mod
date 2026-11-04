package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.network.packet.WorkshopAiRequestC2SPacket;
import com.lunares.blockfield.network.packet.WorkshopAiStateS2CPacket;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class WorkshopAiPanelLifecycleTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void closingAndRemovingPanelNeverCancelWorkOrDiscardTranscriptionDraft() throws Exception {
        var mc = mock(Minecraft.class);
        try (var networking = mockStatic(ClientPlayNetworking.class)) {
            for (String stage :
                    new String[] {
                        "planning",
                        "awaiting",
                        "applying",
                        "undoing",
                        "transcribing",
                        "preview",
                        "idle"
                    }) {
                UUID owner = UUID.randomUUID(), job = UUID.randomUUID();
                WorkshopAiClient.accept(
                        new WorkshopAiStateS2CPacket(
                                owner,
                                job,
                                stage,
                                "",
                                "",
                                BlockPos.ZERO,
                                BlockPos.ZERO,
                                0,
                                0,
                                false,
                                false),
                        owner,
                        ignored -> {});
                var panel = new WorkshopAiScreen();
                var field =
                        net.minecraft.client.gui.screens.Screen.class.getDeclaredField("minecraft");
                field.setAccessible(true);
                field.set(panel, mc);
                var trans = WorkshopAiClient.class.getDeclaredField("transcriptionId");
                trans.setAccessible(true);
                trans.set(null, job);
                var upload = WorkshopAiClient.class.getDeclaredField("upload");
                upload.setAccessible(true);
                byte[] bytes = {1, 2, 3};
                upload.set(null, bytes);
                WorkshopAiClient.draft("unfinished draft");
                panel.onClose();
                panel.removed();
                assertSame(bytes, upload.get(null));
                assertEquals(job, trans.get(null));
                assertEquals("unfinished draft", WorkshopAiClient.draft());
                WorkshopAiClient.accept(
                        new WorkshopAiStateS2CPacket(
                                owner,
                                job,
                                "idle",
                                "",
                                "voice text",
                                BlockPos.ZERO,
                                BlockPos.ZERO,
                                0,
                                0,
                                false,
                                false),
                        owner,
                        ignored -> {});
                assertEquals("voice text", WorkshopAiClient.draft());
                assertNull(trans.get(null));
                assertNotNull(new WorkshopAiScreen());
            }
            networking.verify(
                    () -> ClientPlayNetworking.send(any(WorkshopAiRequestC2SPacket.class)),
                    never());
        } finally {
            WorkshopAiClient.availability(false);
        }
    }
}
