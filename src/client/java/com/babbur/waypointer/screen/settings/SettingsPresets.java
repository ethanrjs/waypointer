package com.babbur.waypointer.screen.settings;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.dungeon.config.DungeonConfig;

public final class SettingsPresets {

    private SettingsPresets() {}

    public static WaypointerConfig minimal(WaypointerConfig live) {
        WaypointerConfig out = withLiveBlacklist(live);
        out.setShowWaypointDistances(false);
        out.setShowLabelBackdrop(false);
        out.setShowLabelTextShadow(false);
        out.setShowTracer(false);
        out.setDimSequenceContextWaypoints(false);
        out.setEditSounds(false);
        out.setShowEditModeSubtitle(false);
        out.setShowWaypointChatShareButtons(false);
        out.setShowContributorBadges(false);
        out.setMaxWaypointLabels(12);
        out.setMaxStaticWaypointRenderDistance(128);
        return out;
    }

    /** Disable every behavior toggle while retaining the user's stored values. */
    public static void applyDisableAll(WaypointerConfig live, DungeonConfig dungeon) {
        if (live != null) live.disableAllSettings(dungeon);
    }

    private static WaypointerConfig withLiveBlacklist(WaypointerConfig live) {
        WaypointerConfig out = new WaypointerConfig();
        if (live != null) {
            for (String name : live.chatCoordSenderBlacklist()) {
                out.addChatCoordSenderBlacklist(name);
            }
        }
        return out;
    }
}
