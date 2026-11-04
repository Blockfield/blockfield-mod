package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.ClientConfig;
import java.io.IOException;
import net.minecraft.network.chat.Component;

/**
 * The player's speed/quality choice for AI generation: persisted in the user config and sent with
 * every generate request. Quality (the server's configured behaviour) is the default.
 */
public final class WorkshopAiSpeedChoice {
    static final String KEY = "workshop.aiFast";

    private WorkshopAiSpeedChoice() {}

    public static boolean fast() {
        return ClientConfig.USER.getBoolean(KEY, false);
    }

    public static void toggle() throws IOException {
        ClientConfig.USER.set(KEY, Boolean.toString(!fast()));
    }

    public static Component label() {
        return Component.translatable(
                "blockfield.workshop.ai." + (fast() ? "speed_fast" : "speed_quality"));
    }
}
