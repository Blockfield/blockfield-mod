package com.lunares.blockfield.network;

import java.nio.file.Files;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;

public final class PackVersion {
    public static final ResourceLocation CHANNEL =
            ResourceLocation.fromNamespaceAndPath("blockfield", "pack_version");
    // Полный sha256 (64 hex) клиентского содержимого пака: build-distribution.py пишет его в маркер
    // и в build.json "client".
    public static final String FORMAT = "[0-9a-f]{64}";
    // Freeze at startup: updating files under a running game must not certify its loaded mods.
    public static final String CURRENT = read("blockfield-pack-version.txt");

    private PackVersion() {}

    static String read(String file) {
        try {
            String value =
                    Files.readString(FabricLoader.getInstance().getConfigDir().resolve(file))
                            .trim();
            return value.matches(FORMAT) ? value : "";
        } catch (java.io.IOException | RuntimeException e) {
            // No loader in unit tests (or no file in dev): not understood, callers fall back.
            return "";
        }
    }

    public static boolean accepts(String expected, String actual) {
        return !expected.isEmpty() && expected.equals(actual);
    }
}
