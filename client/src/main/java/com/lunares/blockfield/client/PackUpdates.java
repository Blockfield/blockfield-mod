package com.lunares.blockfield.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunares.blockfield.network.PackContract;
import com.lunares.blockfield.network.PackVersion;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PackUpdates {
    private static final Logger LOGGER = LoggerFactory.getLogger(PackUpdates.class);
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final String DOWNLOAD = "https://blockfield.pro";
    private static long nextCheck;
    private static volatile String available = "";
    private static volatile String optional = "";
    private static volatile String optionalNotified = "";
    private static volatile String optionalDismissed = "";
    private static volatile String lastMandatory = "";

    /** Whether a newer published pack needs a persistent HUD banner or just a soft hint. */
    enum UpdateKind {
        NONE,
        OPTIONAL,
        MANDATORY
    }

    // Manifest parsing for tests: absent, null or non-string compatibility is an
    // unknown contract (fail-safe mandatory), never an exception that would abort
    // the mandatory notification mid-poll.
    static String remoteContract(JsonObject build) {
        if (!build.has("compatibility")) return "";
        var element = build.get("compatibility");
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return "";
        String value = element.getAsString();
        return value.matches(PackVersion.FORMAT) ? value : "";
    }

    // Null-safe manifest read: a missing or non-string key is "", never an NPE that
    // would silently cancel the notify in the exceptionally below.
    static String text(JsonObject build, String key) {
        if (!build.has(key)) return "";
        var element = build.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return "";
        return element.getAsString();
    }

    // Pure decision over a fetched manifest for unit tests: malformed manifests are
    // ignored (NONE), a well-formed newer digest resolves against the contract.
    static UpdateKind resolveBuild(JsonObject build, String localDigest, String localContract) {
        String digest = text(build, "client");
        if (!digest.matches(PackVersion.FORMAT)) return UpdateKind.NONE;
        return resolve(localDigest, localContract, digest, remoteContract(build));
    }

    // Display label for the banner: the release version when the manifest carries
    // it, otherwise the digest, so a mandatory banner is never lost to a short
    // manifest.
    static String label(JsonObject build, String digest) {
        String version = text(build, "client_version");
        if (version.isEmpty()) version = text(build, "version");
        return version.isEmpty() ? digest : version;
    }

    // Pure decision for unit tests: local digests are passed in so class init
    // (PackVersion.CURRENT needs FabricLoader) is never triggered here.
    static UpdateKind resolve(
            String localDigest, String localContract, String remoteDigest, String remoteContract) {
        if (remoteDigest == null
                || !remoteDigest.matches(PackVersion.FORMAT)
                || remoteDigest.equals(localDigest)) return UpdateKind.NONE;
        // Same compatibility contract: client-only release, fully compatible with this
        // client, so a dismissible hint instead of the persistent banner. Anything else
        // is fail-safe mandatory.
        if (!localContract.isEmpty() && localContract.equals(remoteContract))
            return UpdateKind.OPTIONAL;
        return UpdateKind.MANDATORY;
    }

    private PackUpdates() {}

    public static void init() {
        ClientLoginNetworking.registerGlobalReceiver(
                PackContract.CHANNEL,
                (client, handler, buf, callbacks) -> {
                    // No contract file (dev or unpacked build): not understood, the server falls
                    // back
                    // to the pack hash.
                    if (PackContract.CURRENT.isEmpty())
                        return CompletableFuture.completedFuture(null);
                    var response = PacketByteBufs.create();
                    response.writeUtf(PackContract.reply(PackContract.CURRENT), 80);
                    return CompletableFuture.completedFuture(response);
                });
        ClientLoginNetworking.registerGlobalReceiver(
                PackVersion.CHANNEL,
                (client, handler, buf, callbacks) -> {
                    var response = PacketByteBufs.create();
                    response.writeUtf(PackVersion.CURRENT, 64);
                    return CompletableFuture.completedFuture(response);
                });
        if (ClientConfig.USER.getBoolean("menu.vanilla", false)) {
            // Dev menu mode also silences this notifier by design; the stand has to run
            // without the forced menu.vanilla flag to test it.
            LOGGER.info("PackUpdates: menu.vanilla dev mode, update notify off");
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    String hint = optional;
                    if (!hint.isEmpty()
                            && !hint.equals(optionalNotified)
                            && client.player != null) {
                        client.player.displayClientMessage(
                                Component.translatable("blockfield.pack.update.optional", hint),
                                false);
                        optionalNotified = hint;
                        LOGGER.info("PackUpdates: optional pack update available: {}", hint);
                    }
                    // The optional banner is a soft hint, not a blocker: opening any screen
                    // dismisses it for this version, a newer version re-arms it. Title menus
                    // (no player yet) never dismiss, so the banner still greets a fresh join.
                    if (client.screen != null && client.player != null && !hint.isEmpty())
                        optionalDismissed = hint;
                    if (hint.isEmpty()) optionalDismissed = "";
                    long now = System.nanoTime();
                    if (now < nextCheck) return;
                    nextCheck = now + Duration.ofMinutes(1).toNanos();
                    HttpRequest request =
                            HttpRequest.newBuilder(URI.create(DOWNLOAD + "/build.json"))
                                    .timeout(Duration.ofSeconds(15))
                                    .header("Cache-Control", "no-cache")
                                    .GET()
                                    .build();
                    HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                            .thenAccept(
                                    response -> {
                                        if (response.statusCode() != 200) {
                                            LOGGER.warn(
                                                    "PackUpdates: poll HTTP {}",
                                                    response.statusCode());
                                            return;
                                        }
                                        JsonObject build;
                                        try {
                                            build =
                                                    JsonParser.parseString(response.body())
                                                            .getAsJsonObject();
                                        } catch (RuntimeException bad) {
                                            LOGGER.warn("PackUpdates: poll bad JSON", bad);
                                            return;
                                        }
                                        String digest = text(build, "client");
                                        if (!digest.matches(PackVersion.FORMAT)) {
                                            LOGGER.debug(
                                                    "PackUpdates: poll without a client digest");
                                            return;
                                        }
                                        String version = label(build, digest);
                                        switch (resolveBuild(
                                                build, PackVersion.CURRENT, PackContract.CURRENT)) {
                                            case MANDATORY -> {
                                                available = version;
                                                optional = "";
                                                if (!version.equals(lastMandatory)) {
                                                    lastMandatory = version;
                                                    LOGGER.info(
                                                            "PackUpdates: mandatory pack update"
                                                                    + " required: {}",
                                                            version);
                                                }
                                            }
                                            case OPTIONAL -> {
                                                available = "";
                                                optional = version;
                                            }
                                            case NONE -> {
                                                available = "";
                                                optional = "";
                                                lastMandatory = "";
                                            }
                                        }
                                    })
                            .exceptionally(
                                    error -> {
                                        LOGGER.warn("PackUpdates: poll failed", error);
                                        return null;
                                    });
                });
        HudRenderCallback.EVENT.register(
                (graphics, delta) -> {
                    Minecraft client = Minecraft.getInstance();
                    String version = available;
                    String key = "blockfield.pack.update";
                    String soft = optional;
                    if (version.isEmpty() && !soft.isEmpty() && !soft.equals(optionalDismissed)) {
                        version = soft;
                        key = "blockfield.pack.update.optional";
                    }
                    if (version.isEmpty()
                            || client.player == null
                            || client.options.hideGui
                            || client.screen != null) return;
                    Component message = Component.translatable(key, version);
                    int width = Math.max(1, graphics.guiWidth() - 16);
                    var lines = client.font.split(message, width);
                    int y = graphics.guiHeight() - 100 - lines.size() * client.font.lineHeight;
                    for (var line : lines) {
                        int x = (graphics.guiWidth() - client.font.width(line)) / 2;
                        graphics.fill(
                                x - 3,
                                y - 2,
                                x + client.font.width(line) + 3,
                                y + client.font.lineHeight,
                                0xDD141414);
                        graphics.drawString(client.font, line, x, y, 0xFFFFCC66, true);
                        y += client.font.lineHeight;
                    }
                });
    }
}
