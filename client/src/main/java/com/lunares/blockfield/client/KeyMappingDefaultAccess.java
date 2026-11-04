package com.lunares.blockfield.client;

import com.mojang.blaze3d.platform.InputConstants;

/** Позволяет мод-раскладке стать настоящим дефолтом: поле defaultKey у KeyMapping финальное. */
public interface KeyMappingDefaultAccess {
    void blockfield$setDefaultKey(InputConstants.Key key);
}
