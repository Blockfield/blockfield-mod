package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.WeaponHud;
import com.lunares.blockfield.client.workshop.WorkshopChatArea;
import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {
    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
    @Shadow private int chatScrollbarPos;

    @Shadow
    public abstract int getWidth();

    @Shadow
    public abstract int getLinesPerPage();

    @Shadow
    public abstract double getScale();

    @Shadow
    protected abstract int getLineHeight();

    @Shadow
    protected abstract boolean isChatHidden();

    @Inject(method = "render", at = @At("HEAD"))
    private void blockfield$reserveChat(
            GuiGraphics graphics,
            int ticks,
            int mouseX,
            int mouseY,
            boolean open,
            CallbackInfo ci) {
        int start = Math.min(chatScrollbarPos, trimmedMessages.size());
        int end = Math.min(trimmedMessages.size(), start + getLinesPerPage());
        int[] ages = new int[end - start];
        for (int i = start; i < end; i++)
            ages[i - start] = ticks - trimmedMessages.get(i).addedTime();
        WorkshopChatArea.capture(
                getWidth(),
                graphics.guiHeight(),
                getScale(),
                getLineHeight(),
                blockfield$chatInset(),
                getLinesPerPage(),
                ages,
                open,
                isChatHidden());
    }

    @Unique
    private int blockfield$chatInset() {
        return WeaponHud.visible()
                ? WeaponHud.chatBottomInset(Minecraft.getInstance().getWindow().getGuiScaledWidth())
                : 40;
    }

    @ModifyConstant(method = "render", constant = @Constant(intValue = 40))
    private int blockfield$raiseMessages(int original) {
        return blockfield$chatInset();
    }

    @ModifyConstant(
            method = {"screenToChatY", "handleChatQueueClicked"},
            constant = @Constant(doubleValue = 40))
    private double blockfield$raiseHitTargets(double original) {
        return blockfield$chatInset();
    }
}
