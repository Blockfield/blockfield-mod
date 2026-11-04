package com.lunares.blockfield.client.gui.screen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ClassSelectionCreditsTest {
    @Test
    void balanceIsRelevantOnlyWhenTheMenuOffersAPaidClass() {
        assertFalse(ClassSelectionScreen.hasPaidClasses(null));
        assertFalse(
                ClassSelectionScreen.hasPaidClasses(
                        JsonParser.parseString(
                                        "{\"assault\":{\"deploymentCost\":0},\"mark\":{\"deploymentCost\":0}}")
                                .getAsJsonObject()));
        assertTrue(
                ClassSelectionScreen.hasPaidClasses(
                        JsonParser.parseString(
                                        "{\"assault\":{\"deploymentCost\":0},\"tech\":{\"deploymentCost\":300}}")
                                .getAsJsonObject()));
    }
}
