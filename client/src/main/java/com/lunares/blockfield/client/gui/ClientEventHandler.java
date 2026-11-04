package com.lunares.blockfield.client.gui;

import com.lunares.blockfield.client.combat.FirstShotDrawGuard;
import com.lunares.blockfield.client.combat.PreMatchCombat;
import com.lunares.blockfield.client.gui.screen.ClassSelectionScreen;
import com.lunares.blockfield.client.gui.screen.SpawnSelectionScreen;
import com.lunares.blockfield.client.gui.tablet.Tablet;
import com.lunares.blockfield.network.packet.RadioStateC2SPacket;
import com.lunares.blockfield.network.packet.RigOpenC2SPacket;
import com.mojang.blaze3d.platform.InputConstants;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.client.input.ShootKey;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public class ClientEventHandler {
    /** Читается из потока голосового чата через PTTKeyHandlerMixin. */
    private static volatile boolean wasRadioHeld = false;

    /**
     * ponytail: у Fabric нет KeyConflictContext, клавиша конфликтует глобально, а не только
     * IN_GAME.
     */
    public static final KeyMapping RADIO_KEY =
            new KeyMapping(
                    "key.blockfield.radio",
                    InputConstants.Type.KEYSYM,
                    InputConstants.KEY_R,
                    "key.categories.blockfield");

    /** Клетки разгрузочного жилета: жилет надет всегда, предметом в руке его не открыть. */
    public static final KeyMapping RIG_KEY =
            new KeyMapping(
                    "key.blockfield.rig",
                    InputConstants.Type.KEYSYM,
                    InputConstants.KEY_J,
                    "key.categories.blockfield");

    /** Карта и меню в одном: клавиша открывает полноэкранный планшет. */
    public static final KeyMapping TABLET_KEY =
            new KeyMapping(
                    "key.blockfield.tablet",
                    InputConstants.Type.KEYSYM,
                    InputConstants.KEY_M,
                    "key.categories.blockfield");

    public static void init() {
        KeyBindingHelper.registerKeyBinding(RADIO_KEY);
        KeyBindingHelper.registerKeyBinding(RIG_KEY);
        KeyBindingHelper.registerKeyBinding(TABLET_KEY);
        ClientTickEvents.END_CLIENT_TICK.register(ClientEventHandler::onClientTick);
    }

    /**
     * Экран, пришедший с сервера во время «Загрузки территории» после респавна: открыть его сейчас
     * нельзя — ReceivingLevelScreen закроется и утянет его за собой. Держим до следующего тика без
     * загрузки.
     */
    private static Runnable pendingScreen;

    private static String latestUiJson;

    private static void openOrDefer(Runnable open) {
        Screen current = Minecraft.getInstance().screen;
        if (current instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
                || current instanceof net.minecraft.client.gui.screens.DeathScreen) {
            pendingScreen = open;
        } else {
            pendingScreen = null;
            open.run();
            if (latestUiJson != null) handleUpdateUI(latestUiJson);
        }
    }

    private static void onClientTick(Minecraft mc) {
        if (pendingScreen != null
                && mc.player != null
                && !(mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen)
                && !(mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen)) {
            Runnable open = pendingScreen;
            pendingScreen = null;
            open.run();
            if (latestUiJson != null) handleUpdateUI(latestUiJson);
        }
        if (mc.player == null) {
            pendingScreen = null;
            latestUiJson = null;
        }
        while (RIG_KEY.consumeClick()) {
            if (mc.player == null || mc.screen != null) break;
            if (ClientPlayNetworking.canSend(RigOpenC2SPacket.TYPE)) {
                ClientPlayNetworking.send(RigOpenC2SPacket.INSTANCE);
            }
        }
        // Опрос GLFW, а не KeyMapping.isDown(): с открытым экраном (меню класса, высадка) ванильный
        // ввод в клавишу не попадает, а рация обязана работать и там. Состояние PTT самого SVC
        // подменяет PTTKeyHandlerMixin.
        boolean isHeld = mc.player != null && isKeyPhysicallyPressed(mc, RADIO_KEY);
        if (isHeld != wasRadioHeld) {
            wasRadioHeld = isHeld;
            if (ClientPlayNetworking.canSend(RadioStateC2SPacket.TYPE)) {
                ClientPlayNetworking.send(new RadioStateC2SPacket(isHeld));
            }
        }
        // First-shot draw guard: sample the server draw every tick, so a click between the 250 ms
        // TaCZ lock timeout and the draw sync is held instead of refused. A tap queued during the
        // hold fires once, when plain TaCZ gating allows it again.
        FirstShotDrawGuard.samplePlayerTick();
        replayQueuedTap(mc);
    }

    /** Fires one tap queued during the draw hold, exactly once (see FirstShotDrawGuard). */
    private static void replayQueuedTap(Minecraft mc) {
        if (mc.player == null || Tablet.isOpen() || PreMatchCombat.noAttacks()) return;
        // Same trigger TaCZ reads; a held trigger needs no replay (TaCZ's own tick fires).
        boolean triggerDown = ShootKey.SHOOT_KEY.isDown();
        long synDrawCoolDown = IGunOperator.fromLivingEntity(mc.player).getSynDrawCoolDown();
        if (!FirstShotDrawGuard.pollPendingShot(System.nanoTime(), synDrawCoolDown, triggerDown))
            return;
        // One synthetic tick with the trigger down, exactly like TaCZ ShootKey.autoShoot.
        var operator = IClientPlayerGunOperator.fromLocalPlayer(mc.player);
        if (operator.chargeShoot(true)) operator.shoot();
    }

    /** Клавиша рации зажата прямо сейчас — читает PTTKeyHandlerMixin из потока голосового чата. */
    public static boolean isRadioHeld() {
        return wasRadioHeld;
    }

    private static boolean isKeyPhysicallyPressed(Minecraft mc, KeyMapping mapping) {
        if (mapping.isUnbound()) return false;
        long window = mc.getWindow().getWindow();
        if (window == 0L) return false;
        InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(mapping);
        if (key.getType() == InputConstants.Type.MOUSE) {
            return org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, key.getValue())
                    == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(window, key.getValue());
    }

    // Выбор класса и точки высадки живут на вкладке «Высадка» планшета (Tablet.openDeployment).
    public static void handleOpenClassMenu(String json, boolean isUpdate) {
        if (Tablet.deployment() instanceof ClassSelectionScreen screen) {
            screen.updateClassesData(json);
            // Свежее (не update) меню обязано стать видимым: иначе карточка «Выбрать класс»
            // молча обновляет скрытую высадку, и кажется, что кнопка мертва.
            if (!isUpdate) openOrDefer(() -> Tablet.openDeployment(screen));
        } else if (!isUpdate) {
            openOrDefer(() -> Tablet.openDeployment(new ClassSelectionScreen(json)));
        }
    }

    public static void handleOpenSpawnMenu(String json, String mapId) {
        if (Tablet.deployment() instanceof SpawnSelectionScreen screen) {
            // Повторная выдача (кнопка в комнате возрождения, /menu spawn): обновляем данные на
            // месте, чтобы не сбрасывать выбор точки; закрытый Esc планшет поднимается снова.
            screen.updateSpawnsData(json, mapId);
            if (!Tablet.isOpen()) openOrDefer(() -> Tablet.openDeployment(screen));
            return;
        }
        openOrDefer(() -> Tablet.openDeployment(new SpawnSelectionScreen(json, mapId)));
    }

    public static void handleOpenMenu(String json) {
        String id;
        boolean close;
        boolean update;
        try {
            com.google.gson.JsonObject obj =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            id = obj.has("id") ? obj.get("id").getAsString() : "";
            close = obj.has("close") && obj.get("close").getAsBoolean();
            update = obj.has("update") && obj.get("update").getAsBoolean();
        } catch (Exception e) {
            return;
        }

        if (id.startsWith("workshop")
                || close && com.lunares.blockfield.client.render.WeaponHud.inWorkshop()) {
            com.lunares.blockfield.client.workshop.WorkshopRadialScreen.accept(
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject());
            return;
        }

        // Меню с сервера теперь живёт на полноэкранном планшете: те же данные, тот же MenuScreen
        // внутри него. Протухший live-ответ для закрытого планшета или фоновой вкладки роняем:
        // возврат на вкладку меню запросит свежее.
        if (close) {
            Tablet.closeMenu();
        } else if (Tablet.hasMenu(id)) {
            Tablet.updateMenu(json);
        } else if (!update || Tablet.adoptMenuUpdate(id)) {
            Tablet.openMenu(json, id);
        }
    }

    public static void handleUpdateUI(String json) {
        try {
            var data = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (data.has("closeDeploymentMenu") && data.get("closeDeploymentMenu").getAsBoolean()) {
                pendingScreen = null;
                latestUiJson = null;
                Tablet.closeDeployment();
                return;
            }
        } catch (RuntimeException e) {
            return;
        }
        latestUiJson = json;
        if (Tablet.deployment() instanceof ClassSelectionScreen screen) {
            screen.updateModel(json);
        } else if (Tablet.deployment() instanceof SpawnSelectionScreen screen) {
            screen.updateModel(json);
        }
    }
}
