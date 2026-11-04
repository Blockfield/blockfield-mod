package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.item.UniformItem.Role.*;

import com.lunares.blockfield.common.item.UniformItem;

/**
 * Single definition of uniform geometry. The client mesh and the server hit shapes are built from
 * these events; UV stays client-side (the adapter applies {@code texOffs}).
 */
public final class UniformGeometry {
    public static final int CLOTH = 0,
            ARMOR = 1,
            POUCH = 2,
            DARK = 3,
            METAL = 4,
            MARK = 5,
            WEBBING = 6,
            GLASS = 7,
            EDGE = 8,
            RUBBER = 9,
            HARDWARE = 10,
            LEATHER = 11,
            SCRIM = 12,
            SCRIM_DARK = 13,
            SHEMAGH = 14,
            JUTE = 15,
            BRASS = 16,
            HAZARD = 17;

    public interface Sink {
        void child(
                String parent,
                String name,
                float x,
                float y,
                float z,
                float xRot,
                float yRot,
                float zRot);

        void box(
                String parent,
                String name,
                int material,
                float x,
                float y,
                float z,
                float w,
                float h,
                float d);
    }

    private UniformGeometry() {}

    /** Event order is part of the contract (materials order, sniper {@code Random(47)} replay). */
    public static void build(UniformItem.Role role, UniformItem.Side side, Sink sink) {
        sink.child("", "head", 0, 0, 0, 0, 0, 0);
        sink.child("", "hat", 0, 0, 0, 0, 0, 0);
        sink.child("", "body", 0, 0, 0, 0, 0, 0);
        sink.child("", "right_arm", -5, 2, 0, 0, 0, 0);
        sink.child("", "left_arm", 5, 2, 0, 0, 0, 0);
        sink.child("", "right_leg", -1.9F, 12, 0, 0, 0, 0);
        sink.child("", "left_leg", 1.9F, 12, 0, 0, 0, 0);
        boolean blue = side == UniformItem.Side.BLUE;
        base(sink, role, blue);
        switch (role) {
            case INFANTRY, DM -> {
                helmet(sink, blue);
                assaultHelmet(sink);
                plateCarrier(sink, role);
                backTeam(sink, blue, 1.3F, 4.05F);
            }
            case ENGINEER -> {
                helmet(sink, blue);
                goggles(sink);
                box(sink, "head", "headlamp", DARK, -.8F, -8.3F, -5.1F, 1.6F, 1.1F, .6F);
                box(sink, "head", "headlamp_lens", GLASS, -.5F, -8.1F, -5.2F, 1, .7F, .12F);
                engineerVest(sink);
                backTeam(sink, blue, 2.2F, 7.35F);
            }
            case SNIPER -> sniper(sink, blue);
            case AIRBORNE -> {
                hood(sink);
                jumpGoggles(sink);
                airborneRig(sink);
                backTeam(sink, blue, 2.4F, 4.85F);
            }
            case DRONE -> {
                helmet(sink, blue);
                goggles(sink);
                headset(sink);
                droneRig(sink);
                backTeam(sink, blue, 2.2F, 7.35F);
            }
        }
    }

    /** Combat shirt, trousers, gloves, boots and the team markings every kit shares. */
    private static void base(Sink sink, UniformItem.Role role, boolean blue) {
        box(sink, "body", "tunic", CLOTH, -4.2F, -.2F, -2.2F, 8.4F, 12.4F, 4.4F);
        box(sink, "body", "belt", WEBBING, -4.3F, 9.5F, -2.6F, 8.6F, 1.4F, 5.2F);
        box(sink, "body", "buckle", HARDWARE, -.7F, 9.65F, -3.15F, 1.4F, 1, .6F);
        for (int side = 0; side < 2; side++) {
            String arm = side == 0 ? "right_arm" : "left_arm";
            float x = side == 0 ? -3.2F : -1.2F;
            box(sink, arm, "sleeve", CLOTH, x, -2.2F, -2.2F, 4.4F, 10.2F, 4.4F);
            // Team colour is the upper sleeve itself; one wide stripe for red, two narrow for
            // blue.
            String shoulder = child(sink, arm, "shoulder", 0, 0, 0, 0, 0, 0);
            box(sink, shoulder, "cover", MARK, x - .15F, -2.35F, -2.35F, 4.7F, 3.4F, 4.7F);
            box(
                    sink,
                    arm,
                    "stripe",
                    MARK,
                    x - .08F,
                    2.2F,
                    -2.28F,
                    4.56F,
                    blue ? .75F : 1.2F,
                    4.56F);
            if (blue)
                box(sink, arm, "stripe_two", MARK, x - .08F, 3.45F, -2.28F, 4.56F, .75F, 4.56F);
            box(sink, arm, "glove", LEATHER, x - .08F, 7.5F, -2.28F, 4.56F, 2.75F, 4.56F);
            box(sink, arm, "elbow", ARMOR, x + .55F, 4.2F, 2.2F, 3.3F, 2.1F, .6F);
            if (role == SNIPER)
                box(
                        sink,
                        arm,
                        "arm_pocket",
                        POUCH,
                        side == 0 ? x - .3F : x + 2.7F,
                        4.7F,
                        -2.75F,
                        2,
                        2,
                        .55F);
            else
                box(
                        sink,
                        arm,
                        "sleeve_pocket",
                        POUCH,
                        side == 0 ? x - .3F : x + 4.4F,
                        4.4F,
                        -1.6F,
                        .3F,
                        2.6F,
                        3.2F);
            String leg = side == 0 ? "right_leg" : "left_leg";
            box(sink, leg, "trousers", CLOTH, -2.12F, -.1F, -2.12F, 4.24F, 10, 4.24F);
            float outside = side == 0 ? -2.25F : 1.95F;
            box(sink, leg, "team_patch", MARK, outside, 1.2F, -2.2F, .3F, 3.6F, 4.4F);
            box(
                    sink,
                    leg,
                    "team_patch_front",
                    MARK,
                    side == 0 ? -2.2F : .75F,
                    1.2F,
                    -2.25F,
                    1.45F,
                    3.6F,
                    .3F);
            box(sink, leg, "knee_strap", DARK, -2.2F, 6.2F, -2.25F, 4.4F, .5F, 4.5F);
            box(
                    sink,
                    leg,
                    "knee",
                    role == ENGINEER ? HARDWARE : ARMOR,
                    -1.65F,
                    5,
                    -3.1F,
                    3.3F,
                    2.9F,
                    1);
            box(
                    sink,
                    leg,
                    "knee_face",
                    role == ENGINEER ? RUBBER : POUCH,
                    -1.35F,
                    5.35F,
                    -3.25F,
                    2.7F,
                    2.2F,
                    .3F);
            box(
                    sink,
                    leg,
                    "cargo_pocket",
                    POUCH,
                    side == 0 ? -2.85F : 1.9F,
                    1.2F,
                    -.9F,
                    .95F,
                    3,
                    2.7F);
            box(
                    sink,
                    leg,
                    "cargo_flap",
                    ARMOR,
                    side == 0 ? -3.05F : 1.9F,
                    1.1F,
                    -1,
                    1.15F,
                    .75F,
                    2.9F);
            box(sink, leg, "trouser_hem", CLOTH, -2.25F, 8.3F, -2.3F, 4.5F, 1.2F, 4.6F);
            box(sink, leg, "boot", LEATHER, -2.2F, 9.4F, -2.35F, 4.4F, 2.6F, 4.6F);
            box(sink, leg, "boot_toe", LEATHER, -2.25F, 10.6F, -2.95F, 4.5F, 1.4F, 1);
            box(sink, leg, "sole", DARK, -2.3F, 11.55F, -3.05F, 4.6F, .6F, 5.35F);
            box(
                    sink,
                    "body",
                    "belt_loop_" + side,
                    POUCH,
                    side == 0 ? -3.3F : 2.8F,
                    9.35F,
                    -2.8F,
                    .5F,
                    1.7F,
                    .3F);
        }
        int wrap = role == SNIPER ? SHEMAGH : DARK;
        box(sink, "head", "mask", wrap, -4.12F, -3F, -4.18F, 8.24F, 3.15F, .45F);
        box(sink, "head", "mask_rear", wrap, -4.16F, -2.7F, 3.9F, 8.32F, 2.85F, .35F);
        for (int side = 0; side < 2; side++)
            box(
                    sink,
                    "head",
                    "mask_side_" + side,
                    wrap,
                    side == 0 ? -4.2F : 3.9F,
                    -2.7F,
                    -4.12F,
                    .3F,
                    2.85F,
                    8.24F);
    }

    /** High-cut helmet: camo cover, team band, rails; ears stay open for the headsets. */
    private static void helmet(Sink sink, boolean blue) {
        box(sink, "head", "crown", CLOTH, -4.5F, -8.6F, -4.5F, 9, 3.1F, 9);
        box(sink, "head", "top", CLOTH, -4.05F, -9.1F, -4.05F, 8.1F, .7F, 8.1F);
        box(sink, "head", "brow", EDGE, -4.6F, -6.3F, -4.95F, 9.2F, .65F, 1.4F);
        box(sink, "head", "rear", CLOTH, -4.5F, -6.2F, 3.7F, 9, 3.2F, .8F);
        for (int side = 0; side < 2; side++) {
            box(
                    sink,
                    "head",
                    "side_" + side,
                    CLOTH,
                    side == 0 ? -4.5F : 3.7F,
                    -6.2F,
                    -4.5F,
                    .8F,
                    1.9F,
                    8.4F);
            box(
                    sink,
                    "head",
                    "helmet_rail_" + side,
                    RUBBER,
                    side == 0 ? -4.72F : 4.42F,
                    -6.05F,
                    -3.2F,
                    .3F,
                    .7F,
                    5.4F);
        }
        box(sink, "head", "helmet_band", MARK, -4.56F, -7.1F, -4.56F, 9.12F, 1.15F, 9.12F);
        box(sink, "head", "helmet_rear_adjuster", DARK, -1.05F, -4.3F, 4.3F, 2.1F, .9F, .4F);
    }

    private static void assaultHelmet(Sink sink) {
        box(sink, "head", "mount", RUBBER, -.75F, -7.8F, -4.8F, 1.5F, 2.4F, .5F);
        box(sink, "head", "mount_latch", HARDWARE, -.5F, -5.95F, -4.98F, 1, .3F, .22F);
        box(sink, "head", "nvg_arm", DARK, -.45F, -9.2F, -5.3F, .9F, 1.6F, .6F);
        box(sink, "head", "nvg", DARK, -.8F, -10.1F, -5.9F, 1.6F, 1.3F, 2.2F);
        box(sink, "head", "nvg_lens", GLASS, -.55F, -9.85F, -5.98F, 1.1F, .8F, .12F);
        box(sink, "head", "strobe", DARK, -.6F, -9.6F, 2.2F, 1.2F, .7F, 1.1F);
        box(sink, "head", "counterweight", POUCH, -1.8F, -7.9F, 4.35F, 3.6F, 2.2F, .7F);
        for (int side = 0; side < 2; side++) {
            box(
                    sink,
                    "head",
                    "ear_cup_" + side,
                    DARK,
                    side == 0 ? -5.2F : 4.2F,
                    -5.3F,
                    -1.5F,
                    1,
                    2.9F,
                    2.8F);
            box(
                    sink,
                    "head",
                    "ear_band_" + side,
                    DARK,
                    side == 0 ? -4.9F : 4.55F,
                    -8.9F,
                    -.3F,
                    .35F,
                    3.7F,
                    .6F);
        }
    }

    private static void headset(Sink sink) {
        for (int side = 0; side < 2; side++) {
            box(
                    sink,
                    "head",
                    "headphone_" + side,
                    DARK,
                    side == 0 ? -5.4F : 4.4F,
                    -5.3F,
                    -1.5F,
                    1,
                    3,
                    2.9F);
            box(
                    sink,
                    "head",
                    "headphone_cap_" + side,
                    EDGE,
                    side == 0 ? -5.6F : 5.3F,
                    -4.95F,
                    -1.15F,
                    .3F,
                    2.3F,
                    2.2F);
        }
        box(sink, "head", "mic_arm", DARK, 4.4F, -2.5F, -4.9F, .35F, .35F, 4);
        box(sink, "head", "mic", RUBBER, 2.8F, -2.6F, -5.05F, 1.8F, .6F, .6F);
    }

    /** Plain hood for the airborne kit. */
    private static void hood(Sink sink) {
        box(sink, "head", "crown", CLOTH, -4.5F, -8.6F, -4.5F, 9, 2.5F, 9);
        box(sink, "head", "top", CLOTH, -4.05F, -9.1F, -4.05F, 8.1F, .7F, 8.1F);
        box(sink, "head", "brow", CLOTH, -4.6F, -6.3F, -4.95F, 9.2F, 2.05F, 1.4F);
        box(sink, "head", "rear", CLOTH, -4.5F, -6.2F, 3.7F, 9, 6.5F, .8F);
        for (int side = 0; side < 2; side++) {
            box(
                    sink,
                    "head",
                    "side_" + side,
                    CLOTH,
                    side == 0 ? -4.5F : 3.7F,
                    -6.2F,
                    -4.5F,
                    .8F,
                    6.5F,
                    8.4F);
            box(
                    sink,
                    "head",
                    "hood_hem_" + side,
                    POUCH,
                    side == 0 ? -4.53F : 4.23F,
                    -6.1F,
                    -4.67F,
                    .3F,
                    6.1F,
                    .35F);
        }
    }

    private static void jumpGoggles(Sink sink) {
        box(sink, "head", "jump_goggle_strap", DARK, -4.75F, -7.9F, -4.8F, 9.5F, .7F, 9.4F);
        box(sink, "head", "jump_goggle_frame", RUBBER, -3.6F, -8.6F, -5.6F, 7.2F, 2.1F, 1);
        box(sink, "head", "jump_goggle_lens", GLASS, -3.2F, -8.35F, -5.7F, 6.4F, 1.6F, .15F);
    }

    /**
     * Heavy plate carrier: open-top mag shingle, admin panel, radio, grenade and medical pouches.
     */
    private static void plateCarrier(Sink sink, UniformItem.Role role) {
        box(sink, "body", "vest_front", ARMOR, -3.8F, .6F, -3.45F, 7.6F, 8.6F, 1.3F);
        box(sink, "body", "chest_plate", POUCH, -3.45F, 1, -4.05F, 6.9F, 4.5F, 1.05F);
        box(sink, "body", "plate_rim", EDGE, -3.45F, 1, -4.12F, 6.9F, .35F, .35F);
        box(sink, "body", "vest_back", ARMOR, -3.8F, .6F, 2.1F, 7.6F, 8.6F, 1.4F);
        box(sink, "body", "back_plate", ARMOR, -3.45F, 1, 3.1F, 6.9F, 7.6F, .9F);
        box(sink, "body", "collar_back", ARMOR, -3.3F, -.8F, 1.5F, 6.6F, 1.6F, 1.4F);
        for (int i = 0; i < 3; i++) {
            float x = -3.4F + i * 2.35F;
            box(sink, "body", "magazine_" + i, POUCH, x, 5.6F, -5.6F, 2.05F, 3.6F, 2.25F);
            box(sink, "body", "mag_" + i, DARK, x + .35F, 4.6F, -5.2F, 1.35F, 1.2F, 1.5F);
        }
        for (int side = 0; side < 2; side++) {
            box(
                    sink,
                    "body",
                    "shoulder_strap_" + side,
                    WEBBING,
                    side == 0 ? -3.75F : 2.6F,
                    -.45F,
                    -3.7F,
                    1.15F,
                    4.6F,
                    7.4F);
        }
        if (role == INFANTRY) {
            box(sink, "body", "flag_patch", MARK, 1.2F, 1.3F, -4.2F, 1.9F, 1.2F, .18F);
        } else badge(sink);
        box(sink, "body", "tq", DARK, 2.75F, .2F, -4.1F, .75F, 2.1F, .5F);
        // Radio rides high on the back panel; its whip antenna clears the shoulder in
        // silhouette.
        box(sink, "body", "assault_panel", POUCH, -2.9F, 1.6F, 4, 5.8F, 6.6F, 1.1F);
        box(sink, "body", "radio_pouch", POUCH, -3.5F, .7F, 4.2F, 1.6F, 3.2F, 1.5F);
        box(sink, "body", "radio", DARK, -3.35F, -.2F, 4.4F, 1.3F, 1.2F, 1.1F);
        box(sink, "body", "radio_antenna", RUBBER, -3.05F, -5.6F, 4.8F, .3F, 5.5F, .3F);
        box(sink, "body", "grenade_pouch", POUCH, 2.1F, 9.25F, -4.5F, 1.6F, 2.1F, 1.7F);
        box(sink, "body", "grenade_flap", ARMOR, 2.02F, 9.1F, -4.62F, 1.76F, .75F, 1.9F);
        box(sink, "body", "ifak", POUCH, 1.2F, 8.2F, 2.4F, 2.4F, 2.6F, 1.6F);
        box(sink, "body", "dump_pouch", POUCH, -3.8F, 8.9F, 2.3F, 2.4F, 2.4F, 1.5F);
    }

    /** Lighter utility vest with tool pouches, belt tools and a toolbox pack. */
    private static void engineerVest(Sink sink) {
        box(sink, "body", "vest_front", ARMOR, -3.8F, .6F, -3.3F, 7.6F, 8.6F, 1.15F);
        box(sink, "body", "vest_back", ARMOR, -3.8F, .6F, 2.1F, 7.6F, 8.6F, 1.3F);
        for (int side = 0; side < 2; side++) {
            box(
                    sink,
                    "body",
                    "shoulder_strap_" + side,
                    WEBBING,
                    side == 0 ? -3.75F : 2.6F,
                    -.45F,
                    -3.55F,
                    1.15F,
                    4.6F,
                    7.2F);
            float x = side == 0 ? -3.5F : 1.4F;
            box(sink, "body", "tool_pouch_" + side, POUCH, x, 5.2F, -4.8F, 2.1F, 3.2F, 1.6F);
            for (int tool = 0; tool < 2; tool++)
                box(
                        sink,
                        "body",
                        "tool_handle_" + side + "_" + tool,
                        tool == 0 ? HAZARD : DARK,
                        x + .3F + tool * .9F,
                        3.9F + tool * .3F,
                        -4.4F,
                        .55F,
                        1.5F,
                        .55F);
        }
        badge(sink);
        box(sink, "body", "tool_bag", POUCH, 2.8F, 9.1F, -4.1F, 2.4F, 3.1F, 2.8F);
        box(sink, "body", "tool_bag_flap", ARMOR, 2.7F, 9, -4.35F, 2.6F, .8F, 3);
        box(sink, "body", "hammer_handle", WEBBING, 3.8F, 7.6F, -4.85F, .65F, 4, .7F);
        box(sink, "body", "hammer_head", METAL, 3.05F, 7.3F, -5.05F, 2.1F, 1, 1.2F);
        box(sink, "body", "hammer_grip", LEATHER, 3.72F, 9.4F, -4.94F, .81F, 2, .86F);
        box(sink, "body", "hammer_striking_face", HARDWARE, 3, 7.2F, -5.12F, .45F, 1.2F, 1.34F);
        box(sink, "body", "wrench_handle", METAL, -4.8F, 8.3F, -4.2F, .7F, 4.5F, .75F);
        box(sink, "body", "wrench_jaw", METAL, -5.4F, 8.2F, -4.2F, 1.8F, .65F, .75F);
        box(sink, "body", "wrench_left", METAL, -5.4F, 7.4F, -4.2F, .5F, 1, .75F);
        box(sink, "body", "wrench_right", METAL, -4.1F, 7.4F, -4.2F, .5F, 1, .75F);
        box(sink, "body", "wrench_loop", METAL, -5.15F, 12, -4.25F, 1.4F, 1.3F, .8F);
        box(sink, "right_leg", "drop_holster", POUCH, -2.95F, 1.4F, -1.5F, .9F, 3.6F, 2.6F);
        box(sink, "right_leg", "screwdriver", HAZARD, -2.85F, .3F, -.6F, .55F, 1.3F, .55F);
        // Toolbox pack with a hazard-striped lid and a coiled cable on the side.
        box(sink, "body", "pack_padding", DARK, -3.25F, 1.8F, 3.35F, 6.5F, 7.6F, 1.1F);
        box(sink, "body", "toolbox", POUCH, -3.1F, 2, 3.9F, 6.2F, 7, 2.8F);
        box(sink, "body", "hazard_lid", HAZARD, -3.25F, 1.75F, 3.8F, 6.5F, 1.2F, 3.1F);
        box(sink, "body", "toolbox_latch_0", METAL, -2.4F, 2.7F, 6.65F, .9F, 1, .3F);
        box(sink, "body", "toolbox_latch_1", METAL, 1.5F, 2.7F, 6.65F, .9F, 1, .3F);
        box(sink, "body", "toolbox_handle", WEBBING, -1.3F, 1.1F, 4.8F, 2.6F, .4F, .4F);
        box(sink, "body", "cable_coil_top", RUBBER, 3.1F, 4.4F, 4.3F, .45F, .45F, 2.45F);
        box(sink, "body", "cable_coil_bottom", RUBBER, 3.1F, 7.15F, 4.3F, .45F, .45F, 2.45F);
        box(sink, "body", "cable_coil_front", RUBBER, 3.1F, 4.4F, 4.3F, .45F, 3.2F, .45F);
        box(sink, "body", "cable_coil_back", RUBBER, 3.1F, 4.4F, 6.3F, .45F, 3.2F, .45F);
    }

    /**
     * Parachute harness with leg loops, belly reserve with ripcord and main container on the back.
     */
    private static void airborneRig(Sink sink) {
        box(sink, "body", "vest_front", ARMOR, -3.6F, .8F, -3.1F, 7.2F, 5.6F, .9F);
        // Main lift webs lie flat on the chest, run over the shoulders into the container and
        // down into the leg loops.
        for (int side = 0; side < 2; side++) {
            float x = side == 0 ? -2.9F : 1.9F;
            box(sink, "body", "harness_" + side, WEBBING, x, -.4F, -3.4F, 1, 10.2F, .3F);
            box(sink, "body", "harness_shoulder_" + side, WEBBING, x, -.5F, -3.4F, 1, .45F, 5.7F);
            box(sink, "body", "canopy_release_" + side, METAL, x - .1F, 1, -3.62F, 1.2F, 1.1F, .3F);
            box(
                    sink,
                    "body",
                    "harness_adjuster_" + side,
                    HARDWARE,
                    x - .08F,
                    8.6F,
                    -3.6F,
                    1.16F,
                    .7F,
                    .25F);
            String leg = side == 0 ? "right_leg" : "left_leg";
            box(sink, leg, "leg_loop", WEBBING, -2.35F, 1.9F, -2.4F, 4.7F, .75F, 4.8F);
            box(sink, leg, "leg_buckle", HARDWARE, -.55F, 1.75F, -2.65F, 1.1F, 1.05F, .3F);
        }
        box(sink, "body", "chest_strap", WEBBING, -1.95F, 5.35F, -3.38F, 3.9F, .6F, .25F);
        box(sink, "body", "chest_buckle", HARDWARE, -.6F, 5.2F, -3.62F, 1.2F, .9F, .3F);
        box(sink, "body", "reserve", POUCH, -3.1F, 6.9F, -5, 6.2F, 3.3F, 1.6F);
        box(sink, "body", "reserve_flap", ARMOR, -3.25F, 6.7F, -5.2F, 6.5F, 1, 1.9F);
        box(sink, "body", "ripcord", METAL, -1, 8.3F, -5.3F, 2, 1, .35F);
        badge(sink);
        box(sink, "body", "container", POUCH, -3.4F, .6F, 2.2F, 6.8F, 8.9F, 2.6F);
        box(sink, "body", "container_flap", ARMOR, -3.55F, .4F, 2.1F, 7.1F, 1.8F, 2.9F);
        box(sink, "body", "container_pin_cover", ARMOR, -1.2F, 2.8F, 4.75F, 2.4F, 3.2F, .3F);
        box(sink, "left_arm", "altimeter", DARK, 1.2F, 6.9F, -1.4F, .5F, 1.7F, 2.2F);
        box(sink, "left_arm", "altimeter_face", GLASS, 1.65F, 7.05F, -1.2F, .12F, 1.4F, 1.8F);
    }

    /** Light chest rig carrying the controller tray; relay pack with antennas; forearm tablet. */
    private static void droneRig(Sink sink) {
        box(sink, "body", "vest_front", ARMOR, -3.7F, .6F, -3.2F, 7.4F, 6.4F, 1);
        box(sink, "body", "vest_back", ARMOR, -3.7F, .6F, 2.1F, 7.4F, 6.4F, 1.2F);
        for (int side = 0; side < 2; side++)
            box(
                    sink,
                    "body",
                    "shoulder_strap_" + side,
                    WEBBING,
                    side == 0 ? -3.75F : 2.6F,
                    -.45F,
                    -3.45F,
                    1.15F,
                    4.6F,
                    7);
        badge(sink);
        box(sink, "body", "tray_strap", WEBBING, -3.9F, 6.9F, -4.3F, 7.8F, .5F, .4F);
        box(sink, "body", "controller", DARK, -2.7F, 7.2F, -5.8F, 5.4F, 3, 2.3F);
        box(sink, "body", "controller_rim", EDGE, -2.5F, 7.4F, -6.02F, 5, 2.6F, .3F);
        box(sink, "body", "screen", GLASS, -1.45F, 7.65F, -6.14F, 2.9F, 1.75F, .2F);
        box(sink, "body", "stick_left", METAL, -2.3F, 8.1F, -6.35F, .5F, .5F, .45F);
        box(sink, "body", "stick_right", METAL, 1.8F, 8.1F, -6.35F, .5F, .5F, .45F);
        for (int side = 0; side < 2; side++) {
            float x = side == 0 ? -2.55F : 2.2F;
            box(sink, "body", "controller_antenna_" + side, RUBBER, x, 4.5F, -5, .35F, 2.9F, .35F);
            box(
                    sink,
                    "body",
                    "battery_" + side,
                    DARK,
                    side == 0 ? -4.6F : 3.4F,
                    8.8F,
                    -3,
                    1.2F,
                    2.4F,
                    1);
            box(
                    sink,
                    "body",
                    "antenna_base_" + side,
                    DARK,
                    1.4F + side * 1.2F,
                    1,
                    6.9F,
                    .6F,
                    2,
                    .6F);
            box(
                    sink,
                    "body",
                    "antenna_" + side,
                    DARK,
                    1.55F + side * 1.2F,
                    -9.5F + side * 1.3F,
                    7.05F,
                    .3F,
                    10.7F - side * 1.3F,
                    .3F);
            box(
                    sink,
                    "body",
                    "antenna_tip_" + side,
                    RUBBER,
                    1.5F + side * 1.2F,
                    -9.65F + side * 1.3F,
                    7,
                    .4F,
                    .65F,
                    .4F);
        }
        box(sink, "body", "radio_cable", RUBBER, -3.6F, .5F, -3.75F, .35F, 6.6F, .4F);
        box(sink, "body", "relay_padding", DARK, -3.25F, 1.8F, 3.2F, 6.5F, 7.6F, 1.1F);
        box(sink, "body", "relay_pack", POUCH, -3.05F, 2, 3.8F, 6.1F, 7, 2.6F);
        box(sink, "body", "relay_flap", ARMOR, -3.2F, 1.8F, 3.6F, 6.4F, 1.7F, 3);
        box(sink, "body", "battery_cells", DARK, -2.6F, 4.6F, 6.35F, 5.2F, 3.4F, .5F);
        box(sink, "left_arm", "tablet", DARK, 1.35F, 4.3F, -2.45F, 1, 3, 3.4F);
        box(sink, "left_arm", "tablet_screen", GLASS, 2.3F, 4.55F, -2.2F, .15F, 2.5F, 2.9F);
    }

    private static void backTeam(Sink sink, boolean blue, float y, float z) {
        box(sink, "body", "back_team", MARK, -2.7F, y, z, 5.4F, blue ? .8F : 1.6F, .18F);
        if (blue) box(sink, "body", "back_team_two", MARK, -2.7F, y + 1.2F, z, 5.4F, .8F, .18F);
    }

    private static void sniper(Sink sink, boolean blue) {
        // The ghillie hood stands a unit off the head so the face sits recessed.
        box(sink, "head", "crown", CLOTH, -5, -9.4F, -5, 10, 3, 10);
        box(sink, "head", "top", CLOTH, -4.5F, -9.9F, -4.5F, 9, .6F, 9);
        box(sink, "head", "brow", CLOTH, -5.1F, -6.6F, -5.4F, 10.2F, 2.35F, 1.2F);
        box(sink, "head", "rear", CLOTH, -5, -6.4F, 4.1F, 10, 7.2F, .9F);
        for (int side = 0; side < 2; side++) {
            box(
                    sink,
                    "head",
                    "side_" + side,
                    CLOTH,
                    side == 0 ? -5 : 4.1F,
                    -6.4F,
                    -5,
                    .9F,
                    7.2F,
                    9.1F);
            box(
                    sink,
                    "head",
                    "hood_hem_" + side,
                    POUCH,
                    side == 0 ? -5.03F : 4.73F,
                    -6.3F,
                    -5.2F,
                    .3F,
                    7,
                    .35F);
        }
        var random = new java.util.Random(47);
        // Weighted towards the darker scrim so the hood reads as vegetation, not a wig.
        int[] tones = {SCRIM, SCRIM, SCRIM_DARK, SCRIM_DARK, SCRIM_DARK, JUTE};
        // Ghillie hood: jute strands in three tones hang from the crown over the sides, back
        // and brow.
        for (int i = 0; i < 7; i++)
            strand(
                    sink,
                    "head",
                    "strand_brow_" + i,
                    -4.4F + i * 1.45F + jitter(random, .25F),
                    -9.5F,
                    -5.3F,
                    jitter(random, .2F),
                    -.2F - random.nextFloat() * .35F,
                    jitter(random, .3F),
                    1.8F + random.nextFloat() * 2.6F,
                    .4F + random.nextFloat() * .45F,
                    tones[random.nextInt(tones.length)]);
        for (int i = 0; i < 18; i++)
            strand(
                    sink,
                    "head",
                    "strand_back_" + i,
                    -4.6F + (i % 6) * 1.8F + (i / 6 % 2) * .9F + jitter(random, .35F),
                    -9.5F + i / 6 * 2.3F + jitter(random, .3F),
                    5.05F,
                    (float) Math.PI + jitter(random, .3F),
                    -.1F - random.nextFloat() * .4F,
                    jitter(random, .3F),
                    3 + random.nextFloat() * 4,
                    .45F + random.nextFloat() * .5F,
                    tones[random.nextInt(tones.length)]);
        for (int i = 0; i < 6; i++) {
            float x = -2.6F + i % 3 * 2.6F + jitter(random, .4F),
                    z = -1.8F + i / 3 * 3.6F + jitter(random, .4F);
            tab(
                    sink,
                    "head",
                    "scrim_top_" + i,
                    x,
                    -9.9F,
                    z,
                    (float) Math.atan2(-x, -z) + jitter(random, .5F),
                    -1.62F - random.nextFloat() * .18F,
                    jitter(random, .3F),
                    i % 3 == 0,
                    1 + random.nextFloat() * .3F);
        }
        for (int side = 0; side < 2; side++) {
            float sign = side == 0 ? -1 : 1;
            float yaw = -sign * (float) Math.PI / 2;
            for (int i = 0; i < 16; i++)
                strand(
                        sink,
                        "head",
                        "strand_side_" + side + "_" + i,
                        sign * 5.05F,
                        -9.5F + i / 6 * 2.4F + jitter(random, .3F),
                        -4.6F + (i % 6) * 1.8F + (i / 6 % 2) * .9F + jitter(random, .35F),
                        yaw + jitter(random, .3F),
                        -.1F - random.nextFloat() * .45F,
                        sign * jitter(random, .3F),
                        2.4F + random.nextFloat() * 4,
                        .45F + random.nextFloat() * .5F,
                        tones[random.nextInt(tones.length)]);
            // Strands frame the face opening without covering the eyes.
            for (int i = 0; i < 2; i++)
                strand(
                        sink,
                        "head",
                        "strand_cheek_" + side + "_" + i,
                        sign * (4.45F - i * .45F),
                        -6.4F,
                        -5.3F,
                        sign * .2F,
                        -.2F,
                        sign * jitter(random, .15F),
                        4.2F + i * 1.4F + random.nextFloat(),
                        .6F,
                        tones[random.nextInt(tones.length)]);
        }
        // Ghillie cape over the upper back, drag bag strapped across it.
        for (int i = 0; i < 12; i++)
            strand(
                    sink,
                    "body",
                    "strand_cape_" + i,
                    -3.9F + (i % 6) * 1.56F + jitter(random, .25F),
                    -.3F + i / 6 * 1.4F,
                    2.35F + i / 6 * .3F,
                    (float) Math.PI + jitter(random, .25F),
                    -.06F - random.nextFloat() * .2F,
                    jitter(random, .25F),
                    3.5F + random.nextFloat() * 4,
                    .5F + random.nextFloat() * .5F,
                    tones[random.nextInt(tones.length)]);
        String bag = child(sink, "body", "drag_bag", -2.2F, -1.2F, 2.7F, 0, 0, -.33F);
        box(sink, bag, "bag", CLOTH, -1.15F, 0, 0, 2.3F, 13.2F, 1.5F);
        box(sink, bag, "bag_cap", ARMOR, -1.3F, -.35F, -.1F, 2.6F, 1.6F, 1.7F);
        box(sink, bag, "bag_team", MARK, -1.25F, 2.1F, -.05F, 2.5F, blue ? .55F : 1.1F, 1.6F);
        if (blue) box(sink, bag, "bag_team_two", MARK, -1.25F, 3F, -.05F, 2.5F, .55F, 1.6F);
        for (int i = 0; i < 2; i++)
            box(
                    sink,
                    bag,
                    "bag_strap_" + i,
                    WEBBING,
                    -1.22F,
                    5.8F + i * 4.6F,
                    -.07F,
                    2.44F,
                    .55F,
                    1.64F);
        box(sink, bag, "bag_handle", WEBBING, -.35F, 7.3F, 1.5F, .7F, 2.4F, .3F);
        // Low-profile sniper chest rig with an admin pouch for the emblem.
        box(sink, "body", "rig_panel", ARMOR, -3.4F, 3.3F, -3.05F, 6.8F, 4.6F, .85F);
        for (int side = 0; side < 2; side++) {
            float x = side == 0 ? -3.35F : 1.15F;
            box(sink, "body", "rig_pouch_" + side, POUCH, x, 5.1F, -4.35F, 2.2F, 2.7F, 1.3F);
            box(sink, "body", "rig_flap_" + side, ARMOR, x - .1F, 4.95F, -4.55F, 2.4F, .95F, 1.5F);
            box(
                    sink,
                    "body",
                    "rig_strap_" + side,
                    WEBBING,
                    side == 0 ? -3.3F : 2.3F,
                    -.35F,
                    -2.85F,
                    1,
                    3.8F,
                    5.6F);
            box(
                    sink,
                    "body",
                    "belt_pouch_" + side,
                    POUCH,
                    side == 0 ? -4.05F : 2.35F,
                    9.2F,
                    -3.2F,
                    1.7F,
                    2.2F,
                    1.2F);
        }
        box(sink, "body", "admin_pouch", POUCH, -1.05F, 3.55F, -3.75F, 2.1F, 2.9F, .7F);
        box(sink, "body", "badge", DARK, -.9F, 3.75F, -3.95F, 1.8F, 1.8F, .2F);
        // Bandolier from the left shoulder to the right hip, cartridges painted on both faces.
        String bandolier = child(sink, "body", "bandolier", 2.5F, -.2F, 0, 0, 0, .52F);
        box(sink, bandolier, "cartridges", WEBBING, -.6F, 0, -3.3F, 1.2F, 13.3F, 6.1F);
        box(sink, "body", "shemagh_neck", SHEMAGH, -3.6F, -.9F, -2.75F, 7.2F, 1.7F, 5.3F);
        box(sink, "body", "rangefinder", DARK, -.9F, 9.3F, -3.55F, 1.8F, 1.3F, 1);
        box(sink, "body", "rangefinder_lens", GLASS, -.6F, 9.5F, -3.65F, .55F, .55F, .12F);
    }

    private static void tab(
            Sink sink,
            String parent,
            String name,
            float x,
            float y,
            float z,
            float yaw,
            float pitch,
            float roll,
            boolean dark,
            float size) {
        String tab = child(sink, parent, name, x, y, z, pitch, yaw, roll);
        box(
                sink,
                tab,
                "cloth",
                dark ? SCRIM_DARK : SCRIM,
                -.65F * size,
                0,
                -.25F,
                1.3F * size,
                1.35F * size,
                .5F);
    }

    /** Loose jute strand hanging from its attachment point, frayed at the free end. */
    private static void strand(
            Sink sink,
            String parent,
            String name,
            float x,
            float y,
            float z,
            float yaw,
            float pitch,
            float roll,
            float length,
            float width,
            int tone) {
        String strand = child(sink, parent, name, x, y, z, pitch, yaw, roll);
        box(sink, strand, "jute", tone, -width / 2, 0, -.11F, width, length, .22F);
    }

    private static float jitter(java.util.Random random, float amount) {
        return (random.nextFloat() * 2 - 1) * amount;
    }

    private static void goggles(Sink sink) {
        box(sink, "head", "goggle_strap_back", DARK, -4.6F, -7.8F, 4.52F, 9.2F, .5F, .25F);
        for (int side = 0; side < 2; side++)
            box(
                    sink,
                    "head",
                    "goggle_strap_side_" + side,
                    DARK,
                    side == 0 ? -4.65F : 4.45F,
                    -7.8F,
                    -4.7F,
                    .2F,
                    .5F,
                    9.3F);
        box(sink, "head", "goggle_bridge", DARK, -.8F, -7.4F, -5.35F, 1.6F, .6F, .8F);
        for (int i = 0; i < 2; i++) {
            float x = -3.75F + i * 4.05F;
            box(sink, "head", "goggle_frame_" + i, DARK, x, -8.6F, -5.75F, 3.45F, 3.05F, 1.6F);
            box(
                    sink,
                    "head",
                    "goggle_rim_" + i,
                    HARDWARE,
                    x + .12F,
                    -8.48F,
                    -6.05F,
                    3.21F,
                    2.81F,
                    .5F);
            box(sink, "head", "lens_" + i, GLASS, x + .48F, -8.1F, -6.1F, 2.49F, 2.05F, .15F);
        }
    }

    /** The role emblem itself is painted onto this plate by the baker. */
    private static void badge(Sink sink) {
        box(sink, "body", "badge", DARK, -1.7F, 1.6F, -4.32F, 3.4F, 3.4F, .35F);
    }

    private static void box(
            Sink sink,
            String parent,
            String name,
            int material,
            float x,
            float y,
            float z,
            float w,
            float h,
            float d) {
        sink.box(parent, name, material, x, y, z, w, h, d);
    }

    private static String child(
            Sink sink,
            String parent,
            String name,
            float x,
            float y,
            float z,
            float xRot,
            float yRot,
            float zRot) {
        sink.child(parent, name, x, y, z, xRot, yRot, zRot);
        return parent.isEmpty() ? name : parent + "/" + name;
    }
}
