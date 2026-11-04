package com.lunares.blockfield.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lunares.blockfield.client.gui.ClientEventHandler;
import com.lunares.blockfield.network.packet.*;
import java.util.function.Consumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BlockFieldClient implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("BlockField-Client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Blockfield Client Setup...");

        if (!ResourceManagerHelper.registerBuiltinResourcePack(
                ResourceLocation.fromNamespaceAndPath("blockfield", "gun_overrides"),
                FabricLoader.getInstance().getModContainer("blockfield").orElseThrow(),
                Component.literal("Blockfield — анимации и звуки оружия"),
                ResourcePackActivationType.ALWAYS_ENABLED)) {
            throw new IllegalStateException("Missing Blockfield gun resource overrides");
        }
        if (!ResourceManagerHelper.registerBuiltinResourcePack(
                ResourceLocation.fromNamespaceAndPath("blockfield", "equipment"),
                FabricLoader.getInstance().getModContainer("blockfield").orElseThrow(),
                Component.literal("Blockfield — модели снаряжения"),
                ResourcePackActivationType.ALWAYS_ENABLED)) {
            throw new IllegalStateException("Missing Blockfield equipment models");
        }

        com.lunares.blockfield.client.gui.tablet.MapSnapshots.register();
        com.lunares.blockfield.client.grenade.GrenadeThrowInput.register();
        com.lunares.blockfield.client.grenade.M18SmokeAnimations.register();
        com.lunares.blockfield.client.render.EquipmentAnimations.register();
        com.lunares.blockfield.client.render.TabletAnimations.register();
        com.lunares.blockfield.client.combat.MedkitPickup.register();
        com.lunares.blockfield.client.combat.ClientCombatScene.register();
        com.lunares.blockfield.client.combat.AnimationPolicy.register();
        com.lunares.blockfield.client.combat.ClientAnimationResources.register();
        com.lunares.blockfield.client.animation.RemotePaPresentation.register();
        com.lunares.blockfield.client.render.RangeHuskRenderer.register();
        com.lunares.blockfield.client.render.ItemSilhouette.register();
        com.lunares.blockfield.client.render.WeaponHud.register();
        com.lunares.blockfield.client.render.DisplayBatcher.register();
        com.lunares.blockfield.client.workshop.WorkshopAiClient.register();
        com.lunares.blockfield.client.workshop.WorkshopFeedback.register();
        com.lunares.blockfield.client.workshop.WorkshopToolsClient.register();
        com.lunares.blockfield.client.workshop.WorkshopKeyHelp.register();
        com.lunares.blockfield.client.workshop.WorkshopWandSelection.register();
        WorkshopKeyBindings.register();
        // Workshop builders are creative without op, so vanilla hides the operator tab and its
        // barrier/light; the server lets non-ops take them from the creative menu.
        net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(
                        net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS)
                .register(
                        entries -> {
                            entries.accept(net.minecraft.world.item.Items.BARRIER);
                            entries.accept(net.minecraft.world.item.Items.LIGHT);
                            entries.accept(net.minecraft.world.item.Items.STRUCTURE_VOID);
                        });

        // Fabric доставляет пакеты уже в главном потоке клиента, отдельный enqueueWork не нужен.
        ClientPlayNetworking.registerGlobalReceiver(
                OpenClassMenuS2CPacket.TYPE,
                (p, c) -> {
                    if (!p.valid()) return;
                    ClientEventHandler.handleOpenClassMenu(p.classesJson(), p.isUpdate());
                });
        ClientPlayNetworking.registerGlobalReceiver(
                OpenSpawnMenuS2CPacket.TYPE,
                (p, c) -> {
                    if (!p.valid()) return;
                    ClientEventHandler.handleOpenSpawnMenu(p.spawnsJson(), p.mapId());
                });
        ClientPlayNetworking.registerGlobalReceiver(
                OpenMenuS2CPacket.TYPE,
                (p, c) -> {
                    if (!p.valid()) return;
                    ClientEventHandler.handleOpenMenu(p.json());
                });
        ClientPlayNetworking.registerGlobalReceiver(
                MapPlayersS2CPacket.TYPE,
                (p, c) -> {
                    try {
                        com.lunares.blockfield.client.gui.tablet.TabletMapTab.updatePlayers(
                                UiJson.parse(p.json()));
                    } catch (Exception e) {
                        // Malformed server positions: keep the last good ones on the map.
                    }
                });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) ->
                        com.lunares.blockfield.client.gui.tablet.TabletMapTab.clearPlayers());
        // F3+B captures the posed native model locally; no server bone stream exists.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
                client ->
                        com.lunares.blockfield.client.render.BodyPartHitboxRenderer
                                .pollSubscription(client));
        com.lunares.blockfield.client.combat.PoseDebugOverlay.register();
        // The server's per-tick head anchor drives the first-person camera (eye-sync).
        ClientPlayNetworking.registerGlobalReceiver(
                SelfEyeS2CPacket.TYPE,
                (p, c) -> com.lunares.blockfield.client.combat.EffectiveEyeView.onPacket(p));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) ->
                        com.lunares.blockfield.client.combat.EffectiveEyeView.onDisconnect());
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) ->
                        com.lunares.blockfield.client.render.BodyPartHitboxRenderer.onDisconnect());
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) ->
                        com.lunares.blockfield.client.gui.tablet.TabletMapTab.clearPlayers());
        ClientPlayNetworking.registerGlobalReceiver(
                UpdateUIS2CPacket.TYPE,
                (p, c) -> {
                    if (!p.valid()) return;
                    com.google.gson.JsonObject obj = p.ui();
                    String json = p.uiDataJson();
                    try {
                        if (UiJson.bool(obj, "resetStamina", false)) {
                            var local = c.player();
                            var stamina =
                                    local.getAttachedOrCreate(
                                                    com.alrex.parcool.common.attachment.Attachments
                                                            .STAMINA)
                                            .updateMax(local);
                            var fresh =
                                    com.lunares.blockfield.common.combat.CombatStamina.newLife(
                                            stamina);
                            local.setAttached(
                                    com.alrex.parcool.common.attachment.Attachments.STAMINA, fresh);
                            fresh.sync(local);
                            if (obj.size() == 1) return;
                        }
                        if (obj.has("workshopMap")) {
                            com.lunares.blockfield.client.workshop.WorkshopAiClient.availability(
                                    UiJson.bool(obj, "workshopAi", false));
                            com.lunares.blockfield.client.workshop.WorkshopGhostClient.update(
                                    UiJson.bool(obj, "workshopBuild", false),
                                    UiJson.bool(obj, "workshopGhost", false));
                            com.lunares.blockfield.client.render.HudRenderer.updateHUDData(json);
                            return;
                        }
                        if (obj.has("resetScores")) {
                            com.lunares.blockfield.client.render.HudRenderer.clearKillFeed();
                            com.lunares.blockfield.client.render.TabMenuRenderer.clearCache();
                            // The server sends it when the player enters a room: the previous
                            // battle's result must not stay on the tablet of the new one.
                            com.lunares.blockfield.client.gui.tablet.TabletResultTab.clear();
                            return;
                        }
                        if (obj.has("removeScore")) {
                            com.lunares.blockfield.client.render.TabMenuRenderer.removePlayer(
                                    java.util.UUID.fromString(UiJson.str(obj, "removeScore", "")));
                            return;
                        }
                        if (obj.has("creditGain")) {
                            com.google.gson.JsonObject gain = UiJson.obj(obj, "creditGain");
                            if (gain == null) throw new IllegalArgumentException("creditGain");
                            com.lunares.blockfield.client.render.HudRenderer.onCreditGain(gain);
                            return;
                        }
                        if (obj.has("matchResult")) {
                            dispatchMatchResult(
                                    obj.get("matchResult"),
                                    com.lunares.blockfield.client.gui.tablet.TabletResultTab
                                            ::accept,
                                    com.lunares.blockfield.client.music.MusicDirector
                                            ::onMatchResult);
                            return;
                        }
                        if (obj.has("presence")) {
                            com.google.gson.JsonObject presence = UiJson.obj(obj, "presence");
                            if (presence == null) throw new IllegalArgumentException("presence");
                            com.lunares.blockfield.client.music.MusicDirector.onPresence(presence);
                            com.lunares.blockfield.client.gui.KillFeedReset.onPresence(presence);
                            LauncherBridge.update(presence);
                            return;
                        }
                        ClientEventHandler.handleUpdateUI(json);
                        if (UiJson.bool(obj, "closeDeploymentMenu", false)) return;
                        com.lunares.blockfield.client.render.HudRenderer.updateHUDData(json);
                        if (obj.has("tabActive")) {
                            boolean active = UiJson.bool(obj, "tabActive", false);
                            if (active && UiJson.bool(obj, "showHud", true)) {
                                com.lunares.blockfield.client.gui.tablet.TabletResultTab.onBattle();
                            }
                            boolean wasActive =
                                    com.lunares.blockfield.client.render.TabMenuRenderer
                                            .isTabActive();
                            com.lunares.blockfield.client.render.TabMenuRenderer.setTabActive(
                                    active);
                            if (wasActive && !active) {
                                com.lunares.blockfield.client.render.TabMenuRenderer.clearCache();
                            }
                            // CampaignMode never sends "ffa" (only Deathmatch does), so the flag
                            // would otherwise
                            // stay stuck true after DM. Not under wasActive: leaving the DM waiting
                            // room, where
                            // the table never turned on, left the next team match with the FFA
                            // columns.
                            if (!active) {
                                com.lunares.blockfield.client.render.TabMenuRenderer.setFfaMode(
                                        false);
                            }
                        }
                        if (obj.has("ffa")) {
                            com.lunares.blockfield.client.render.TabMenuRenderer.setFfaMode(
                                    UiJson.bool(obj, "ffa", false));
                        }
                    } catch (Exception e) {
                        // Ignore parsing errors
                    }
                });
        ClientPlayNetworking.registerGlobalReceiver(
                TicketUpdatePacket.TYPE,
                (p, c) ->
                        com.lunares.blockfield.client.render.HudRenderer.updateTickets(
                                p.redTickets, p.blueTickets));
        ClientPlayNetworking.registerGlobalReceiver(
                MapPingS2CPacket.TYPE,
                (p, c) -> com.lunares.blockfield.client.gui.tablet.MapPings.add(p));
        ClientPlayNetworking.registerGlobalReceiver(
                RadioStateS2CPacket.TYPE,
                (p, c) ->
                        com.lunares.blockfield.client.voice.VoiceHudState.setRadio(
                                p.player(), p.held()));
        ClientPlayNetworking.registerGlobalReceiver(
                PlaySound2DS2CPacket.TYPE,
                (p, c) -> {
                    var instance = soundInstance(p.soundId, p.volume, p.pitch);
                    if (instance != null) {
                        net.minecraft.client.Minecraft.getInstance()
                                .getSoundManager()
                                .play(instance);
                    }
                });
        ClientPlayNetworking.registerGlobalReceiver(
                ConsumeStaminaS2CPacket.TYPE,
                (p, c) -> {
                    var local = net.minecraft.client.Minecraft.getInstance().player;
                    if (local != null) com.alrex.parcool.api.Stamina.get(local).consume(p.amount);
                });
        ClientPlayNetworking.registerGlobalReceiver(
                ScoreDeltaPacket.TYPE,
                (p, c) -> {
                    com.lunares.blockfield.client.render.TabMenuRenderer.updatePlayerStatsCache(p);
                    var local = net.minecraft.client.Minecraft.getInstance().player;
                    if (local != null && local.getUUID().equals(p.playerUUID))
                        com.lunares.blockfield.client.music.MusicDirector.onKills(p.kills);
                });
        // Регистрация рендера аксессуаров обязана лечь до первой перезагрузки ресурсов: Accessories
        // кэширует рендереры в onReload, поэтому позже CLIENT_STARTED уже поздно.
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("accessories")) {
            com.lunares.blockfield.client.render.UniformRenderers.register();
            com.lunares.blockfield.client.accessories.AccessoriesSyncDefer.init();
        }

        // Экран клеток жилета для MenuType blockfield:chest_rig (сам тип регистрируется в common).
        net.minecraft.client.gui.screens.MenuScreens.register(
                com.lunares.blockfield.common.item.ChestRigMenu.TYPE,
                com.lunares.blockfield.client.gui.screen.ChestRigScreen::new);

        // Подсказка на предмете жилета с упоминанием клавиши
        net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register(
                (stack, tooltipContext, tooltipFlag, lines) -> {
                    if (stack.is(com.lunares.blockfield.common.ModItems.CHEST_RIG)) {
                        lines.add(
                                net.minecraft.network.chat.Component.translatable(
                                                "blockfield.gui.rig_tab.open",
                                                ClientEventHandler.RIG_KEY
                                                        .getTranslatedKeyMessage())
                                        .withStyle(net.minecraft.ChatFormatting.GRAY));
                    } else if (com.lunares.blockfield.common.WorkshopToolItems.isAi(stack)) {
                        lines.add(
                                net.minecraft.network.chat.Component.translatable(
                                                "item.blockfield.workshop_ai_tool.hint",
                                                com.lunares.blockfield.client.workshop
                                                        .WorkshopControls.key("ai")
                                                        .getTranslatedKeyMessage())
                                        .withStyle(net.minecraft.ChatFormatting.GRAY));
                    } else if (com.lunares.blockfield.common.WorkshopToolItems.isWand(stack)) {
                        lines.add(
                                net.minecraft.network.chat.Component.translatable(
                                                "item.blockfield.workshop_wand.hint")
                                        .withStyle(net.minecraft.ChatFormatting.GRAY));
                    }
                });

        // Мягкая зависимость: класс AimCamera тянет за собой API TaCZ, грузим только с модом.
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("tacz")) {
            AimCamera.init();
            com.lunares.blockfield.client.balance.GunBalanceClient.init();
        }

        // Перекраска Sodium: пресеты тем в палитру Blockfield (явная тема страницы — миксином).
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")) {
            com.lunares.blockfield.client.sodium.SodiumTheme.apply();
        }

        // Иконка окна/панели задач после создания окна.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED
                .register(mc -> WindowIcon.apply());

        com.lunares.blockfield.client.music.MusicDirector.init();
        PackUpdates.init();
        DraslAuth.init();
        DraslSession.init();
        LauncherBridge.init();
        KeyDefaults.init();
        SprintControl.init();
        com.lunares.blockfield.client.animation.ParCoolIntentSender.init();
        OptionsDefaults.init();
        com.lunares.blockfield.client.gui.screen.ModSettingsScreen.initButton();
        ClientEventHandler.init();
        com.lunares.blockfield.client.gui.KillFeedReset.init();
        com.lunares.blockfield.client.render.HudRenderer.init();
        com.lunares.blockfield.client.render.PointMarkerRenderer.init();
        // Раньше голосового HUD: он встаёт под прямоугольником миникарты этого же кадра.
        com.lunares.blockfield.client.render.MinimapHud.init();
        com.lunares.blockfield.client.render.VoiceHudOverlay.init();
        com.lunares.blockfield.client.gui.tablet.Tablet.init(ClientEventHandler.TABLET_KEY);
    }

    /**
     * Server 2D sound sanitized for the sound engine, or null when the id is unknown: malformed ids
     * never throw, non-finite values fall back, and volume/pitch stay in UI bounds so a malicious
     * packet cannot earrape through the speakers.
     */
    static net.minecraft.client.resources.sounds.SimpleSoundInstance soundInstance(
            String soundId, float volume, float pitch) {
        var id = net.minecraft.resources.ResourceLocation.tryParse(soundId);
        if (id == null) return null;
        var sound = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(id);
        if (sound == null) return null;
        float gain = Float.isFinite(volume) ? net.minecraft.util.Mth.clamp(volume, 0f, 1f) : 1f;
        float tone = Float.isFinite(pitch) ? net.minecraft.util.Mth.clamp(pitch, 0.5f, 2f) : 1f;
        return net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, tone, gain);
    }

    static void dispatchMatchResult(
            JsonElement result, Consumer<JsonObject> tablet, Consumer<String> music) {
        if (result == null || result.isJsonNull()) return;
        JsonObject object = UiJson.asObject(result);
        if (object != null) {
            tablet.accept(object);
        } else if (result instanceof com.google.gson.JsonPrimitive primitive
                && primitive.isString()) {
            String outcome = result.getAsString();
            if (outcome.equals("win") || outcome.equals("loss") || outcome.equals("draw")) {
                music.accept(outcome);
            }
        }
    }
}
