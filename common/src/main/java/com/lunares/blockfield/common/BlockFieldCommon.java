package com.lunares.blockfield.common;

import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BlockFieldCommon implements ModInitializer {
    public static final String MOD_ID = "blockfield";
    public static final Logger LOGGER = LogManager.getLogger("BlockField");

    @Override
    public void onInitialize() {
        ModItems.register();
        com.lunares.blockfield.common.item.RigCells.register();
        com.lunares.blockfield.common.item.ChestRigMenu.registerMenu();
        com.lunares.blockfield.network.NetworkHandler.register();
        DecorInteractions.register();
        LOGGER.info("Blockfield Common Setup Complete.");
    }
}
