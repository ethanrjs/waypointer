package com.babbur.waypointer.screen.settings;

import com.babbur.waypointer.config.WaypointerConfig;

/**
 * Bundled settings profiles, built programmatically rather than as baked
 * {@code WPC:} strings — the codec rejects unknown tags and future versions,
 * so a hardcoded code string would break silently on the next codec change,
 * while these builders are checked by the compiler and unit tests.
 *
 * <p>Each preset starts from a fresh defaults instance (absolute, not a delta
 * on the live config) but carries the live chat-sender blacklist over so
 * applying a preset never wipes it. Apply via
 * {@code config.replaceWith(preset)} after a
 * {@link SettingsCatalog#countChangedSettings} confirmation. The "Default"
 * preset is just {@code resetToDefaults()} — no third code path.
 */
public final class SettingsPresets {

    private SettingsPresets() {}

    /**
     * Quiet HUD with low render overhead: navigation essentials (names,
     * tracers off but boxes on) without the chatty extras. Budgets capped.
     */
    public static WaypointerConfig minimal(WaypointerConfig live) {
        WaypointerConfig out = withLiveBlacklist(live);
        out.setShowWaypointDistances(false);
        out.setShowLabelBackdrop(false);
        out.setShowLabelTextShadow(false);
        out.setShowTracer(false);
        out.setDimSequenceContextWaypoints(false);
        out.setEditSounds(false);
        out.setShowEditModeSubtitle(false);
        out.setShowContributorBadges(false);
        out.setMaxWaypointLabels(12);
        out.setMaxStaticWaypointRenderDistance(128);
        return out;
    }

    /** Every display feature on, budgets unlimited. */
    public static WaypointerConfig everything(WaypointerConfig live) {
        WaypointerConfig out = withLiveBlacklist(live);
        out.setShowWaypointNames(true);
        out.setShowWaypointDistances(true);
        out.setShowRouteProgress(true);
        out.setShowLabelBackdrop(true);
        out.setShowLabelTextShadow(true);
        out.setScaleWaypointTextWithDistance(true);
        out.setShowCompleted(true);
        out.setShowTracer(true);
        out.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);
        out.setUseBeaconBeamTextures(true);
        out.setBeaconBeamExtendsBelowWaypoint(true);
        out.setShowRouteLines(true);
        out.setShowDungeonEntryPathToFirstWaypoint(true);
        out.setShowDungeonEntryPathToFollowingWaypoints(true);
        out.setMaxWaypointLabels(0);
        out.setMaxStaticWaypointRenderDistance(0);
        return out;
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
