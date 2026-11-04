package com.lunares.blockfield.workshop;

/** Documents which small surface owns every server workshop action family. */
public final class WorkshopActionCoverage {
    private WorkshopActionCoverage() {}

    public static String surface(String action) {
        if (action.equals("menu") || action.equals("menu_auto")) return "radial";
        if (action.startsWith("workshop:mk:")) return "markup panel / confirm sector";
        if (action.startsWith("workshop:build:")
                || action.startsWith("workshop:tool:")
                || action.equals("workshop:build")
                || action.equals("workshop:tools")
                || action.equals("workshop:selection")) return "selection / clipboard sectors";
        if (action.equals("workshop:hub")
                || action.startsWith("workshop:map:")
                || action.startsWith("workshop:tpxz:")) return "map panel";
        if (action.equals("workshop:ai")
                || action.equals("workshop:library")
                || action.equals("workshop:cancel")
                || action.equals("workshop:ai_undo")
                || action.equals("workshop:extend")
                || action.equals("workshop:chain")) return "AI sectors / panel";
        if (action.equals("workshop:markup")) return "markup panel";
        for (String prefix :
                new String[] {
                    "spectator",
                    "ghost",
                    "players",
                    "team",
                    "tp:",
                    "member:",
                    "summon:",
                    "sendhub:",
                    "grant:",
                    "revoke:"
                })
            if (action.equals("workshop:" + prefix)
                    || prefix.endsWith(":") && action.startsWith("workshop:" + prefix))
                return "people sectors / panel";
        return null;
    }
}
