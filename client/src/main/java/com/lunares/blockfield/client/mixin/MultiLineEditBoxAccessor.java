package com.lunares.blockfield.client.mixin;

import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The AI panel selects the previous prompt so typing replaces it; vanilla has no select-all API.
 */
@Mixin(MultiLineEditBox.class)
public interface MultiLineEditBoxAccessor {
    @Accessor("textField")
    MultilineTextField blockfield$textField();
}
