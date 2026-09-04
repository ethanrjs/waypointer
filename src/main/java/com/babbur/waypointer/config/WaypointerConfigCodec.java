package com.babbur.waypointer.config;

import com.babbur.waypointer.codec.AsciiStreamCodec;
import com.babbur.waypointer.core.WaypointGroup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public final class WaypointerConfigCodec {

    public static final String MAGIC = "WPC:";
    static final int VERSION = 6;
    private static final int LEGACY_VERSION_5 = 5;
    private static final int LEGACY_VERSION_4 = 4;
    private static final int LEGACY_VERSION_3 = 3;
    private static final int LEGACY_VERSION_2 = 2;
    private static final int LEGACY_VERSION_1 = 1;
    private static final int END = 0;
    private static final int MAX_INFLATED_BYTES = 32 * 1024;

    private static final int DEFAULT_REACH_RADIUS = 1;
    private static final int RESET_PROGRESS_ON_WORLD_JOIN = 2;
    private static final int RESTART_ROUTE_WHEN_COMPLETE = 3;
    private static final int DEFAULT_WAYPOINT_COLOR = 4;
    private static final int TRACER_COLOR = 5;
    private static final int MATCH_TRACER_TO_WAYPOINT_COLOR = 6;
    private static final int TRACER_OPACITY = 7;
    private static final int TRACER_THICKNESS = 8;
    private static final int WAYPOINT_OUTLINE_THICKNESS = 9;
    private static final int BEACON_OPACITY = 10;
    private static final int SHOW_WAYPOINT_NAMES = 11;
    private static final int SHOW_WAYPOINT_DISTANCES = 12;
    private static final int SHOW_ROUTE_PROGRESS = 13;
    private static final int LABEL_SCALE = 14;
    private static final int SCALE_WAYPOINT_TEXT_WITH_DISTANCE = 15;
    private static final int MATCH_WAYPOINT_TEXT_TO_WAYPOINT_COLOR = 16;
    private static final int SHOW_COMPLETED = 17;
    private static final int SHOW_TRACER = 18;
    private static final int DIM_SEQUENCE_CONTEXT_WAYPOINTS = 19;
    private static final int HIDE_TRACER_ON_STATIC_ROUTES = 20;
    private static final int HIDE_WAYPOINTS_NEAR_PLAYER = 21;
    private static final int HIDE_WAYPOINTS_NEAR_RADIUS = 22;
    private static final int HIDE_WAYPOINT_LABELS_NEAR_PLAYER = 23;
    private static final int HIDE_WAYPOINT_LABELS_NEAR_RADIUS = 24;
    private static final int HIDE_REACHED_STATIC_WAYPOINTS = 25;
    private static final int SKIP_AHEAD_ONLY_VISIBLE = 26;
    private static final int SHOW_ROUTE_LINES = 27;
    private static final int ROUTE_LINE_COLOR = 28;
    private static final int SHOW_LABEL_BACKDROP = 29;
    private static final int MAX_WAYPOINT_LABELS = 30;
    private static final int MAX_STATIC_RENDER_DISTANCE = 31;
    private static final int LABEL_HEIGHT_OFFSET = 32;
    private static final int BOX_STYLE = 33;
    private static final int BEACON_BEAM_MODE = 34;
    private static final int BEACON_BEAM_EXTENDS_BELOW = 35;
    private static final int CHAT_COORD_DETECTION = 36;
    private static final int CHAT_COORD_SENDER_BLACKLIST = 37;
    private static final int AUTO_ADD_CHAT_TEMP_WAYPOINTS = 38;
    private static final int PLACE_NEW_WAYPOINTS_BELOW_PLAYER = 39;
    private static final int FOCUS_TEMP_WAYPOINTS = 40;
    private static final int CHAT_CODEC_DETECTION = 41;
    private static final int IMPORTED_ROUTE_COLOR_MODE = 42;
    private static final int IMPORTED_ROUTE_DEFAULT_COLOR = 43;
    private static final int EXPORT_INCLUDE_NAMES = 44;
    private static final int EXPORT_INCLUDE_COLORS = 45;
    private static final int EXPORT_INCLUDE_RADII = 46;
    private static final int EXPORT_INCLUDE_WAYPOINT_FLAGS = 47;
    private static final int EXPORT_INCLUDE_GROUP_META = 48;
    private static final int DUNGEON_FEATURE = 49;
    private static final int SKIP_AHEAD_MECHANIC = 50;
    private static final int LEGACY_CHECK_FOR_UPDATES = 51;
    private static final int IRIS_SHADER_HUD_FALLBACK = 52;
    private static final int TEMP_DEFAULT_MODE = 53;
    private static final int TEMP_DEFAULT_DURATION_MIN = 54;
    private static final int LEGACY_SHARP_WAYPOINT_EDGES = 55;
    private static final int EDIT_SOUNDS = 56;
    private static final int SHOW_EDIT_MODE_SUBTITLE = 57;
    private static final int USE_BEACON_BEAM_TEXTURES = 58;
    private static final int TEMP_DEFAULT_DURATION_SEC = 59;
    private static final int DUNGEON_ENTRY_PATH_TO_FIRST_WAYPOINT = 60;
    private static final int DUNGEON_ENTRY_PATH_COLOR = 61;
    private static final int DUNGEON_ENTRY_PATH_TO_FOLLOWING_WAYPOINTS = 62;
    private static final int SHOW_CONTRIBUTOR_BADGES = 63;
    private static final int SHOW_LABEL_TEXT_SHADOW = 64;
    private static final int SHOW_WAYPOINT_CHAT_SHARE_BUTTONS = 65;
    private static final int LEGACY_ROUTE_TIMES_ENABLED = 66;
    private static final int SHOW_ROUTE_INDICES_IN_GUI = 67;
    private static final int KEEP_SUBWAYPOINTS_VISIBLE_UNTIL_NEXT = 68;
    private static final int EXPORT_INCLUDE_ZONE = 69;
    private static final int USE_ETHERWARP_HEIGHT = 70;
    private static final int SHOW_EXPORT_ROUTE_PREVIEW = 71;
    private static final int WAYPOINT_MARKER_SCALE = 72;
    private static final int WAYPOINT_OUTLINE_OPACITY = 73;
    private static final int MATCH_WAYPOINT_OUTLINE_TO_WAYPOINT_COLOR = 74;
    private static final int WAYPOINT_OUTLINE_COLOR = 75;
    private static final int SEQUENCE_PREVIOUS_WAYPOINT_COUNT = 76;
    private static final int SHOW_CURRENT_SEQUENCE_WAYPOINT = 77;
    private static final int SEQUENCE_NEXT_WAYPOINT_COUNT = 78;
    private static final int ETHERWARP_ALIGNMENT_SOUND = 79;
    private static final int ETHERWARP_ALIGNMENT_SOUND_TYPE = 80;
    private static final int CRYSTAL_HOLLOWS_ENABLED = 81;
    private static final int CRYSTAL_HOLLOWS_STRUCTURE_WAYPOINTS = 82;
    private static final int CRYSTAL_HOLLOWS_SHOW_ROUGH_MARKERS = 83;
    private static final int CRYSTAL_HOLLOWS_ENTITY_DETECTION = 84;
    private static final int CRYSTAL_HOLLOWS_CHAT_DETECTION = 85;
    private static final int CRYSTAL_HOLLOWS_WISHING_COMPASS_SOLVER = 86;
    private static final int CRYSTAL_HOLLOWS_COMPASS_RAYS = 87;
    private static final int CRYSTAL_HOLLOWS_ANNOUNCE_DETECTIONS = 88;
    private static final int CRYSTAL_HOLLOWS_NUCLEUS_WAYPOINTS = 89;
    private static final int ALLOW_BACKWARD_PROGRESS = 90;

    private WaypointerConfigCodec() {
    }

    public static String encode(WaypointerConfig config) {
        if (config == null) throw new IllegalArgumentException("config is required");
        WaypointerConfig defaults = new WaypointerConfig();
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeByte(VERSION);
            writeFields(out, config, defaults);
            out.writeByte(END);
            out.flush();
            return MAGIC + AsciiStreamCodec.encode(deflate(raw.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode Waypointer config", e);
        }
    }

    public static WaypointerConfig decode(String code) {
        String trimmed = code == null ? "" : code.trim();
        if (!trimmed.startsWith(MAGIC)) {
            throw new IllegalArgumentException("Config code must start with " + MAGIC);
        }
        String body = trimmed.substring(MAGIC.length());
        if (body.isEmpty()) {
            throw new IllegalArgumentException("Config code body is empty");
        }
        try {
            byte[] inflated = inflate(AsciiStreamCodec.decode(body));
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(inflated));
            int version = in.readUnsignedByte();
            if (version != LEGACY_VERSION_1
                    && version != LEGACY_VERSION_2
                    && version != LEGACY_VERSION_3
                    && version != LEGACY_VERSION_4
                    && version != LEGACY_VERSION_5
                    && version != VERSION) {
                throw new IllegalArgumentException("Unsupported config code version: " + version);
            }
            WaypointerConfig config = version <= LEGACY_VERSION_4
                    ? WaypointerConfig.legacyConfigCodeDefaults()
                    : new WaypointerConfig();
            if (version == LEGACY_VERSION_1) {
                config.setBeaconOpacity(0.8);
            }
            readFields(in, config);
            return config;
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed config code", e);
        }
    }
    private static void writeFields(DataOutputStream out, WaypointerConfig config,
                                    WaypointerConfig defaults) throws IOException {
        writeDouble(out, DEFAULT_REACH_RADIUS, config.defaultReachRadius(), defaults.defaultReachRadius());
        writeBoolean(out, RESET_PROGRESS_ON_WORLD_JOIN, config.resetProgressOnWorldJoin(), defaults.resetProgressOnWorldJoin());
        writeBoolean(out, RESTART_ROUTE_WHEN_COMPLETE, config.restartRouteWhenComplete(), defaults.restartRouteWhenComplete());
        writeBoolean(out, SHOW_ROUTE_INDICES_IN_GUI,
                config.showRouteIndicesInGui(), defaults.showRouteIndicesInGui());
        writeBoolean(out, KEEP_SUBWAYPOINTS_VISIBLE_UNTIL_NEXT,
                config.keepSubwaypointsVisibleUntilNextWaypoint(),
                defaults.keepSubwaypointsVisibleUntilNextWaypoint());
        writeInt(out, DEFAULT_WAYPOINT_COLOR, config.defaultWaypointColor(), defaults.defaultWaypointColor());
        writeInt(out, TRACER_COLOR, config.tracerColor(), defaults.tracerColor());
        writeBoolean(out, MATCH_TRACER_TO_WAYPOINT_COLOR, config.matchTracerToWaypointColor(), defaults.matchTracerToWaypointColor());
        writeDouble(out, TRACER_OPACITY, config.tracerOpacity(), defaults.tracerOpacity());
        writeDouble(out, TRACER_THICKNESS, config.tracerThickness(), defaults.tracerThickness());
        writeDouble(out, WAYPOINT_OUTLINE_THICKNESS, config.waypointOutlineThickness(), defaults.waypointOutlineThickness());
        writeDouble(out, WAYPOINT_MARKER_SCALE, config.waypointMarkerScale(), defaults.waypointMarkerScale());
        writeDouble(out, WAYPOINT_OUTLINE_OPACITY, config.waypointOutlineOpacity(), defaults.waypointOutlineOpacity());
        writeBoolean(out, MATCH_WAYPOINT_OUTLINE_TO_WAYPOINT_COLOR,
                config.matchWaypointOutlineToWaypointColor(),
                defaults.matchWaypointOutlineToWaypointColor());
        writeInt(out, WAYPOINT_OUTLINE_COLOR,
                config.waypointOutlineColor(), defaults.waypointOutlineColor());
        writeDouble(out, BEACON_OPACITY, config.beaconOpacity(), defaults.beaconOpacity());
        writeBoolean(out, SHOW_WAYPOINT_NAMES, config.showWaypointNames(), defaults.showWaypointNames());
        writeBoolean(out, SHOW_WAYPOINT_DISTANCES, config.showWaypointDistances(), defaults.showWaypointDistances());
        writeBoolean(out, SHOW_ROUTE_PROGRESS, config.showRouteProgress(), defaults.showRouteProgress());
        writeDouble(out, LABEL_SCALE, config.labelScale(), defaults.labelScale());
        writeBoolean(out, SCALE_WAYPOINT_TEXT_WITH_DISTANCE, config.scaleWaypointTextWithDistance(), defaults.scaleWaypointTextWithDistance());
        writeBoolean(out, MATCH_WAYPOINT_TEXT_TO_WAYPOINT_COLOR, config.matchWaypointTextToWaypointColor(), defaults.matchWaypointTextToWaypointColor());
        writeInt(out, SEQUENCE_PREVIOUS_WAYPOINT_COUNT,
                config.sequencePreviousWaypointCount(), defaults.sequencePreviousWaypointCount());
        writeBoolean(out, SHOW_CURRENT_SEQUENCE_WAYPOINT,
                config.showCurrentSequenceWaypoint(), defaults.showCurrentSequenceWaypoint());
        writeInt(out, SEQUENCE_NEXT_WAYPOINT_COUNT,
                config.sequenceNextWaypointCount(), defaults.sequenceNextWaypointCount());
        writeBoolean(out, SHOW_TRACER, config.showTracer(), defaults.showTracer());
        writeBoolean(out, DIM_SEQUENCE_CONTEXT_WAYPOINTS, config.dimSequenceContextWaypoints(), defaults.dimSequenceContextWaypoints());
        writeBoolean(out, HIDE_TRACER_ON_STATIC_ROUTES, config.hideTracerOnStaticRoutes(), defaults.hideTracerOnStaticRoutes());
        writeBoolean(out, HIDE_WAYPOINTS_NEAR_PLAYER, config.hideWaypointsNearPlayer(), defaults.hideWaypointsNearPlayer());
        writeDouble(out, HIDE_WAYPOINTS_NEAR_RADIUS, config.hideWaypointsNearRadius(), defaults.hideWaypointsNearRadius());
        writeBoolean(out, HIDE_WAYPOINT_LABELS_NEAR_PLAYER, config.hideWaypointLabelsNearPlayer(), defaults.hideWaypointLabelsNearPlayer());
        writeDouble(out, HIDE_WAYPOINT_LABELS_NEAR_RADIUS, config.hideWaypointLabelsNearRadius(), defaults.hideWaypointLabelsNearRadius());
        writeBoolean(out, HIDE_REACHED_STATIC_WAYPOINTS, config.hideReachedStaticWaypointsUntilCycleComplete(), defaults.hideReachedStaticWaypointsUntilCycleComplete());
        writeBoolean(out, SKIP_AHEAD_ONLY_VISIBLE, config.skipAheadOnlyVisibleWaypoints(), defaults.skipAheadOnlyVisibleWaypoints());
        writeBoolean(out, SHOW_ROUTE_LINES, config.showRouteLines(), defaults.showRouteLines());
        writeBoolean(out, USE_ETHERWARP_HEIGHT,
                config.useEtherwarpHeight(), defaults.useEtherwarpHeight());
        writeBoolean(out, DUNGEON_ENTRY_PATH_TO_FIRST_WAYPOINT,
                config.showDungeonEntryPathToFirstWaypoint(),
                defaults.showDungeonEntryPathToFirstWaypoint());
        writeBoolean(out, DUNGEON_ENTRY_PATH_TO_FOLLOWING_WAYPOINTS,
                config.showDungeonEntryPathToFollowingWaypoints(),
                defaults.showDungeonEntryPathToFollowingWaypoints());
        writeInt(out, DUNGEON_ENTRY_PATH_COLOR,
                config.dungeonEntryPathColor(), defaults.dungeonEntryPathColor());
        writeInt(out, ROUTE_LINE_COLOR, config.routeLineColor(), defaults.routeLineColor());
        writeBoolean(out, SHOW_LABEL_BACKDROP, config.showLabelBackdrop(), defaults.showLabelBackdrop());
        writeBoolean(out, SHOW_LABEL_TEXT_SHADOW,
                config.showLabelTextShadow(), defaults.showLabelTextShadow());
        writeInt(out, MAX_WAYPOINT_LABELS, config.maxWaypointLabels(), defaults.maxWaypointLabels());
        writeDouble(out, MAX_STATIC_RENDER_DISTANCE, config.maxStaticWaypointRenderDistance(), defaults.maxStaticWaypointRenderDistance());
        writeDouble(out, LABEL_HEIGHT_OFFSET, config.labelHeightOffset(), defaults.labelHeightOffset());
        writeEnum(out, BOX_STYLE, config.boxStyle(), defaults.boxStyle());
        writeEnum(out, BEACON_BEAM_MODE, config.beaconBeamMode(), defaults.beaconBeamMode());
        writeBoolean(out, BEACON_BEAM_EXTENDS_BELOW, config.beaconBeamExtendsBelowWaypoint(), defaults.beaconBeamExtendsBelowWaypoint());
        writeBoolean(out, USE_BEACON_BEAM_TEXTURES, config.useBeaconBeamTextures(), defaults.useBeaconBeamTextures());
        writeBoolean(out, EDIT_SOUNDS, config.editSounds(), defaults.editSounds());
        writeBoolean(out, SHOW_EDIT_MODE_SUBTITLE, config.showEditModeSubtitle(), defaults.showEditModeSubtitle());
        writeBoolean(out, CHAT_COORD_DETECTION, config.chatCoordDetection(), defaults.chatCoordDetection());
        writeStringList(out, CHAT_COORD_SENDER_BLACKLIST, config.chatCoordSenderBlacklist(), defaults.chatCoordSenderBlacklist());
        writeBoolean(out, AUTO_ADD_CHAT_TEMP_WAYPOINTS, config.autoAddChatTempWaypoints(), defaults.autoAddChatTempWaypoints());
        writeBoolean(out, PLACE_NEW_WAYPOINTS_BELOW_PLAYER, config.placeNewWaypointsBelowPlayer(), defaults.placeNewWaypointsBelowPlayer());
        writeBoolean(out, FOCUS_TEMP_WAYPOINTS, config.focusTempWaypoints(), defaults.focusTempWaypoints());
        writeBoolean(out, SHOW_WAYPOINT_CHAT_SHARE_BUTTONS,
                config.showWaypointChatShareButtons(), defaults.showWaypointChatShareButtons());
        writeBoolean(out, CHAT_CODEC_DETECTION, config.chatCodecDetection(), defaults.chatCodecDetection());
        writeBoolean(out, SHOW_CONTRIBUTOR_BADGES, config.showContributorBadges(), defaults.showContributorBadges());
        writeEnum(out, IMPORTED_ROUTE_COLOR_MODE, config.importedRouteColorMode(), defaults.importedRouteColorMode());
        writeInt(out, IMPORTED_ROUTE_DEFAULT_COLOR, config.importedRouteDefaultColor(), defaults.importedRouteDefaultColor());
        writeBoolean(out, EXPORT_INCLUDE_NAMES, config.exportIncludeNames(), defaults.exportIncludeNames());
        writeBoolean(out, EXPORT_INCLUDE_COLORS, config.exportIncludeColors(), defaults.exportIncludeColors());
        writeBoolean(out, EXPORT_INCLUDE_RADII, config.exportIncludeRadii(), defaults.exportIncludeRadii());
        writeBoolean(out, EXPORT_INCLUDE_WAYPOINT_FLAGS, config.exportIncludeWaypointFlags(), defaults.exportIncludeWaypointFlags());
        writeBoolean(out, EXPORT_INCLUDE_GROUP_META, config.exportIncludeGroupMeta(), defaults.exportIncludeGroupMeta());
        writeBoolean(out, EXPORT_INCLUDE_ZONE, config.exportIncludeZone(), defaults.exportIncludeZone());
        writeBoolean(out, SHOW_EXPORT_ROUTE_PREVIEW, config.showExportRoutePreview(), defaults.showExportRoutePreview());
        writeBoolean(out, DUNGEON_FEATURE, config.dungeonWaypointsFeatureEnabled(), defaults.dungeonWaypointsFeatureEnabled());
        writeBoolean(out, SKIP_AHEAD_MECHANIC, config.skipAheadMechanicEnabled(), defaults.skipAheadMechanicEnabled());
        writeBoolean(out, IRIS_SHADER_HUD_FALLBACK, config.irisShaderHudFallback(), defaults.irisShaderHudFallback());
        writeInt(out, TEMP_DEFAULT_MODE, config.tempDefaultMode(), defaults.tempDefaultMode());
        writeInt(out, TEMP_DEFAULT_DURATION_SEC, config.tempDefaultDurationSec(), defaults.tempDefaultDurationSec());
        writeEnum(out, ETHERWARP_ALIGNMENT_SOUND_TYPE,
                config.etherwarpAlignmentSound(), defaults.etherwarpAlignmentSound());
        writeBoolean(out, CRYSTAL_HOLLOWS_ENABLED,
                config.crystalHollowsEnabled(), defaults.crystalHollowsEnabled());
        writeBoolean(out, CRYSTAL_HOLLOWS_STRUCTURE_WAYPOINTS,
                config.crystalHollowsStructureWaypoints(),
                defaults.crystalHollowsStructureWaypoints());
        writeBoolean(out, CRYSTAL_HOLLOWS_SHOW_ROUGH_MARKERS,
                config.crystalHollowsShowRoughMarkers(),
                defaults.crystalHollowsShowRoughMarkers());
        writeBoolean(out, CRYSTAL_HOLLOWS_ENTITY_DETECTION,
                config.crystalHollowsEntityDetection(),
                defaults.crystalHollowsEntityDetection());
        writeBoolean(out, CRYSTAL_HOLLOWS_CHAT_DETECTION,
                config.crystalHollowsChatDetection(), defaults.crystalHollowsChatDetection());
        writeBoolean(out, CRYSTAL_HOLLOWS_WISHING_COMPASS_SOLVER,
                config.crystalHollowsWishingCompassSolver(),
                defaults.crystalHollowsWishingCompassSolver());
        writeBoolean(out, CRYSTAL_HOLLOWS_COMPASS_RAYS,
                config.crystalHollowsCompassRays(), defaults.crystalHollowsCompassRays());
        writeBoolean(out, CRYSTAL_HOLLOWS_ANNOUNCE_DETECTIONS,
                config.crystalHollowsAnnounceDetections(),
                defaults.crystalHollowsAnnounceDetections());
        writeBoolean(out, CRYSTAL_HOLLOWS_NUCLEUS_WAYPOINTS,
                config.crystalHollowsNucleusWaypoints(),
                defaults.crystalHollowsNucleusWaypoints());
        writeBoolean(out, ALLOW_BACKWARD_PROGRESS,
                config.allowBackwardProgress(), defaults.allowBackwardProgress());
    }
    private static void readFields(DataInputStream in, WaypointerConfig config) throws IOException {
        while (true) {
            int tag = in.readUnsignedByte();
            if (tag == END) return;
            switch (tag) {
                case DEFAULT_REACH_RADIUS -> config.setDefaultReachRadius(in.readDouble());
                case RESET_PROGRESS_ON_WORLD_JOIN -> config.setResetProgressOnWorldJoin(in.readBoolean());
                case RESTART_ROUTE_WHEN_COMPLETE -> config.setRestartRouteWhenComplete(in.readBoolean());
                case LEGACY_ROUTE_TIMES_ENABLED -> in.readBoolean();
                case SHOW_ROUTE_INDICES_IN_GUI -> config.setShowRouteIndicesInGui(in.readBoolean());
                case KEEP_SUBWAYPOINTS_VISIBLE_UNTIL_NEXT ->
                        config.setKeepSubwaypointsVisibleUntilNextWaypoint(in.readBoolean());
                case DEFAULT_WAYPOINT_COLOR -> config.setDefaultWaypointColor(in.readInt());
                case TRACER_COLOR -> config.setTracerColor(in.readInt());
                case MATCH_TRACER_TO_WAYPOINT_COLOR -> config.setMatchTracerToWaypointColor(in.readBoolean());
                case TRACER_OPACITY -> config.setTracerOpacity(in.readDouble());
                case TRACER_THICKNESS -> config.setTracerThickness(in.readDouble());
                case WAYPOINT_OUTLINE_THICKNESS -> config.setWaypointOutlineThickness(in.readDouble());
                case WAYPOINT_MARKER_SCALE -> config.setWaypointMarkerScale(in.readDouble());
                case WAYPOINT_OUTLINE_OPACITY -> config.setWaypointOutlineOpacity(in.readDouble());
                case MATCH_WAYPOINT_OUTLINE_TO_WAYPOINT_COLOR ->
                        config.setMatchWaypointOutlineToWaypointColor(in.readBoolean());
                case WAYPOINT_OUTLINE_COLOR -> config.setWaypointOutlineColor(in.readInt());
                case LEGACY_SHARP_WAYPOINT_EDGES -> in.readBoolean();
                case BEACON_OPACITY -> config.setBeaconOpacity(in.readDouble());
                case SHOW_WAYPOINT_NAMES -> config.setShowWaypointNames(in.readBoolean());
                case SHOW_WAYPOINT_DISTANCES -> config.setShowWaypointDistances(in.readBoolean());
                case SHOW_ROUTE_PROGRESS -> config.setShowRouteProgress(in.readBoolean());
                case LABEL_SCALE -> config.setLabelScale(in.readDouble());
                case SCALE_WAYPOINT_TEXT_WITH_DISTANCE -> config.setScaleWaypointTextWithDistance(in.readBoolean());
                case MATCH_WAYPOINT_TEXT_TO_WAYPOINT_COLOR -> config.setMatchWaypointTextToWaypointColor(in.readBoolean());
                case SHOW_COMPLETED -> config.setShowCompleted(in.readBoolean());
                case SEQUENCE_PREVIOUS_WAYPOINT_COUNT ->
                        config.setSequencePreviousWaypointCount(in.readInt());
                case SHOW_CURRENT_SEQUENCE_WAYPOINT ->
                        config.setShowCurrentSequenceWaypoint(in.readBoolean());
                case SEQUENCE_NEXT_WAYPOINT_COUNT ->
                        config.setSequenceNextWaypointCount(in.readInt());
                case SHOW_TRACER -> config.setShowTracer(in.readBoolean());
                case DIM_SEQUENCE_CONTEXT_WAYPOINTS -> config.setDimSequenceContextWaypoints(in.readBoolean());
                case HIDE_TRACER_ON_STATIC_ROUTES -> config.setHideTracerOnStaticRoutes(in.readBoolean());
                case HIDE_WAYPOINTS_NEAR_PLAYER -> config.setHideWaypointsNearPlayer(in.readBoolean());
                case HIDE_WAYPOINTS_NEAR_RADIUS -> config.setHideWaypointsNearRadius(in.readDouble());
                case HIDE_WAYPOINT_LABELS_NEAR_PLAYER -> config.setHideWaypointLabelsNearPlayer(in.readBoolean());
                case HIDE_WAYPOINT_LABELS_NEAR_RADIUS -> config.setHideWaypointLabelsNearRadius(in.readDouble());
                case HIDE_REACHED_STATIC_WAYPOINTS -> config.setHideReachedStaticWaypointsUntilCycleComplete(in.readBoolean());
                case SKIP_AHEAD_ONLY_VISIBLE -> config.setSkipAheadOnlyVisibleWaypoints(in.readBoolean());
                case SHOW_ROUTE_LINES -> config.setShowRouteLines(in.readBoolean());
                case USE_ETHERWARP_HEIGHT -> config.setUseEtherwarpHeight(in.readBoolean());
                case DUNGEON_ENTRY_PATH_TO_FIRST_WAYPOINT ->
                        config.setShowDungeonEntryPathToFirstWaypoint(in.readBoolean());
                case DUNGEON_ENTRY_PATH_TO_FOLLOWING_WAYPOINTS ->
                        config.setShowDungeonEntryPathToFollowingWaypoints(in.readBoolean());
                case DUNGEON_ENTRY_PATH_COLOR -> config.setDungeonEntryPathColor(in.readInt());
                case ROUTE_LINE_COLOR -> config.setRouteLineColor(in.readInt());
                case SHOW_LABEL_BACKDROP -> config.setShowLabelBackdrop(in.readBoolean());
                case SHOW_LABEL_TEXT_SHADOW -> config.setShowLabelTextShadow(in.readBoolean());
                case MAX_WAYPOINT_LABELS -> config.setMaxWaypointLabels(in.readInt());
                case MAX_STATIC_RENDER_DISTANCE -> config.setMaxStaticWaypointRenderDistance(in.readDouble());
                case LABEL_HEIGHT_OFFSET -> config.setLabelHeightOffset(in.readDouble());
                case BOX_STYLE -> config.setBoxStyle(readEnum(in, WaypointerConfig.BoxStyle.values(), WaypointerConfig.BoxStyle.FILLED_OUTLINED));
                case BEACON_BEAM_MODE -> config.setBeaconBeamMode(readEnum(in, WaypointerConfig.BeaconBeamMode.values(), WaypointerConfig.BeaconBeamMode.OFF));
                case BEACON_BEAM_EXTENDS_BELOW -> config.setBeaconBeamExtendsBelowWaypoint(in.readBoolean());
                case USE_BEACON_BEAM_TEXTURES -> config.setUseBeaconBeamTextures(in.readBoolean());
                case EDIT_SOUNDS -> config.setEditSounds(in.readBoolean());
                case SHOW_EDIT_MODE_SUBTITLE -> config.setShowEditModeSubtitle(in.readBoolean());
                case CHAT_COORD_DETECTION -> config.setChatCoordDetection(in.readBoolean());
                case CHAT_COORD_SENDER_BLACKLIST -> readStringList(in).forEach(config::addChatCoordSenderBlacklist);
                case AUTO_ADD_CHAT_TEMP_WAYPOINTS -> config.setAutoAddChatTempWaypoints(in.readBoolean());
                case PLACE_NEW_WAYPOINTS_BELOW_PLAYER -> config.setPlaceNewWaypointsBelowPlayer(in.readBoolean());
                case FOCUS_TEMP_WAYPOINTS -> config.setFocusTempWaypoints(in.readBoolean());
                case SHOW_WAYPOINT_CHAT_SHARE_BUTTONS ->
                        config.setShowWaypointChatShareButtons(in.readBoolean());
                case CHAT_CODEC_DETECTION -> config.setChatCodecDetection(in.readBoolean());
                case SHOW_CONTRIBUTOR_BADGES -> config.setShowContributorBadges(in.readBoolean());
                case IMPORTED_ROUTE_COLOR_MODE -> config.setImportedRouteColorMode(readEnum(in, WaypointGroup.GradientMode.values(), WaypointGroup.GradientMode.STATIC));
                case IMPORTED_ROUTE_DEFAULT_COLOR -> config.setImportedRouteDefaultColor(in.readInt());
                case EXPORT_INCLUDE_NAMES -> config.setExportIncludeNames(in.readBoolean());
                case EXPORT_INCLUDE_COLORS -> config.setExportIncludeColors(in.readBoolean());
                case EXPORT_INCLUDE_RADII -> config.setExportIncludeRadii(in.readBoolean());
                case EXPORT_INCLUDE_WAYPOINT_FLAGS -> config.setExportIncludeWaypointFlags(in.readBoolean());
                case EXPORT_INCLUDE_GROUP_META -> config.setExportIncludeGroupMeta(in.readBoolean());
                case EXPORT_INCLUDE_ZONE -> config.setExportIncludeZone(in.readBoolean());
                case SHOW_EXPORT_ROUTE_PREVIEW -> config.setShowExportRoutePreview(in.readBoolean());
                case DUNGEON_FEATURE -> config.setDungeonWaypointsFeatureEnabled(in.readBoolean());
                case SKIP_AHEAD_MECHANIC -> config.setSkipAheadMechanicEnabled(in.readBoolean());
                case LEGACY_CHECK_FOR_UPDATES -> in.readBoolean();
                case IRIS_SHADER_HUD_FALLBACK -> config.setIrisShaderHudFallback(in.readBoolean());
                case TEMP_DEFAULT_MODE -> config.setTempDefaultMode(in.readInt());
                case TEMP_DEFAULT_DURATION_MIN -> config.setTempDefaultDurationMin(in.readInt());
                case TEMP_DEFAULT_DURATION_SEC -> config.setTempDefaultDurationSec(in.readInt());
                // Tag 79 was a boolean in WPC v4 and v5; retain legacy decoding.
                case ETHERWARP_ALIGNMENT_SOUND -> config.setEtherwarpAlignmentSound(
                        in.readBoolean() ? WaypointerConfig.EtherwarpAlignmentSound.EXPERIENCE
                                : WaypointerConfig.EtherwarpAlignmentSound.OFF);
                case ETHERWARP_ALIGNMENT_SOUND_TYPE -> config.setEtherwarpAlignmentSound(readEnum(in,
                        WaypointerConfig.EtherwarpAlignmentSound.values(),
                        WaypointerConfig.EtherwarpAlignmentSound.OFF));
                case CRYSTAL_HOLLOWS_ENABLED -> config.setCrystalHollowsEnabled(in.readBoolean());
                case CRYSTAL_HOLLOWS_STRUCTURE_WAYPOINTS ->
                        config.setCrystalHollowsStructureWaypoints(in.readBoolean());
                case CRYSTAL_HOLLOWS_SHOW_ROUGH_MARKERS ->
                        config.setCrystalHollowsShowRoughMarkers(in.readBoolean());
                case CRYSTAL_HOLLOWS_ENTITY_DETECTION ->
                        config.setCrystalHollowsEntityDetection(in.readBoolean());
                case CRYSTAL_HOLLOWS_CHAT_DETECTION ->
                        config.setCrystalHollowsChatDetection(in.readBoolean());
                case CRYSTAL_HOLLOWS_WISHING_COMPASS_SOLVER ->
                        config.setCrystalHollowsWishingCompassSolver(in.readBoolean());
                case CRYSTAL_HOLLOWS_COMPASS_RAYS ->
                        config.setCrystalHollowsCompassRays(in.readBoolean());
                case CRYSTAL_HOLLOWS_ANNOUNCE_DETECTIONS ->
                        config.setCrystalHollowsAnnounceDetections(in.readBoolean());
                case CRYSTAL_HOLLOWS_NUCLEUS_WAYPOINTS ->
                        config.setCrystalHollowsNucleusWaypoints(in.readBoolean());
                case ALLOW_BACKWARD_PROGRESS ->
                        config.setAllowBackwardProgress(in.readBoolean());
                default -> throw new IllegalArgumentException("Unknown config field tag: " + tag);
            }
        }
    }

    private static void writeBoolean(DataOutputStream out, int tag, boolean actual,
                                     boolean defaultValue) throws IOException {
        if (actual == defaultValue) return;
        out.writeByte(tag);
        out.writeBoolean(actual);
    }

    private static void writeInt(DataOutputStream out, int tag, int actual,
                                 int defaultValue) throws IOException {
        if (actual == defaultValue) return;
        out.writeByte(tag);
        out.writeInt(actual);
    }

    private static void writeDouble(DataOutputStream out, int tag, double actual,
                                    double defaultValue) throws IOException {
        if (Double.compare(actual, defaultValue) == 0) return;
        out.writeByte(tag);
        out.writeDouble(actual);
    }

    private static <E extends Enum<E>> void writeEnum(DataOutputStream out, int tag,
                                                      E actual, E defaultValue) throws IOException {
        if (actual == defaultValue) return;
        out.writeByte(tag);
        out.writeByte(actual.ordinal());
    }

    private static void writeStringList(DataOutputStream out, int tag, List<String> actual,
                                        List<String> defaultValue) throws IOException {
        if (actual.equals(defaultValue)) return;
        out.writeByte(tag);
        out.writeShort(actual.size());
        for (String value : actual) {
            out.writeUTF(value == null ? "" : value);
        }
    }

    private static List<String> readStringList(DataInputStream in) throws IOException {
        int size = in.readUnsignedShort();
        java.util.ArrayList<String> out = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(in.readUTF());
        }
        return out;
    }

    private static <E extends Enum<E>> E readEnum(DataInputStream in, E[] values,
                                                  E fallback) throws IOException {
        int ordinal = in.readUnsignedByte();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    static List<TaggedField> encodeTaggedFields(WaypointerConfig config) throws IOException {
        if (config == null) throw new IllegalArgumentException("config is required");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        writeFields(new DataOutputStream(raw), config, new WaypointerConfig());
        byte[] bytes = raw.toByteArray();
        TreeMap<Integer, byte[]> sorted = new TreeMap<>();
        int offset = 0;
        while (offset < bytes.length) {
            int tag = bytes[offset++] & 0xFF;
            int length = taggedFieldPayloadLength(tag, bytes, offset);
            if (sorted.put(tag, Arrays.copyOfRange(bytes, offset, offset + length)) != null) {
                throw new IOException("duplicate config field tag " + tag);
            }
            offset += length;
        }
        List<TaggedField> fields = new ArrayList<>(sorted.size());
        for (Map.Entry<Integer, byte[]> entry : sorted.entrySet()) {
            fields.add(new TaggedField(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(fields);
    }

    static WaypointerConfig decodeTaggedFields(List<TaggedField> fields) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int previous = 0;
        for (TaggedField field : fields) {
            if (field.tag() <= previous || !isActiveFieldTag(field.tag())) {
                throw new IOException("invalid config field ordering or tag " + field.tag());
            }
            validateTaggedFieldPayload(field.tag(), field.value());
            raw.write(field.tag());
            raw.write(field.value());
            previous = field.tag();
        }
        raw.write(END);
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw.toByteArray()));
        WaypointerConfig config = new WaypointerConfig();
        readFields(input, config);
        if (input.available() != 0) throw new IOException("trailing config field bytes");
        return config;
    }

    static boolean isActiveFieldTag(int tag) {
        return tag >= DEFAULT_REACH_RADIUS && tag <= ALLOW_BACKWARD_PROGRESS
                && tag != SHOW_COMPLETED
                && tag != LEGACY_CHECK_FOR_UPDATES
                && tag != TEMP_DEFAULT_DURATION_MIN
                && tag != LEGACY_SHARP_WAYPOINT_EDGES
                && tag != LEGACY_ROUTE_TIMES_ENABLED;
    }

    static boolean isRgbFieldTag(int tag) {
        return tag == DEFAULT_WAYPOINT_COLOR || tag == TRACER_COLOR
                || tag == ROUTE_LINE_COLOR || tag == IMPORTED_ROUTE_DEFAULT_COLOR
                || tag == DUNGEON_ENTRY_PATH_COLOR || tag == WAYPOINT_OUTLINE_COLOR;
    }

    static boolean isUnsignedIntegerFieldTag(int tag) {
        return tag == MAX_WAYPOINT_LABELS || tag == TEMP_DEFAULT_MODE
                || tag == TEMP_DEFAULT_DURATION_SEC
                || tag == SEQUENCE_PREVIOUS_WAYPOINT_COUNT
                || tag == SEQUENCE_NEXT_WAYPOINT_COUNT;
    }

    static boolean isStringListFieldTag(int tag) {
        return tag == CHAT_COORD_SENDER_BLACKLIST;
    }

    static void validateTaggedFieldPayload(int tag, byte[] value) throws IOException {
        if (!isActiveFieldTag(tag)) throw new IOException("unknown current config field tag " + tag);
        int fixedLength = taggedFieldFixedLength(tag);
        if (fixedLength >= 0) {
            if (value.length != fixedLength) {
                throw new IOException("config field " + tag + " has length " + value.length
                        + ", expected " + fixedLength);
            }
            if (fixedLength == 1 && isBooleanFieldTag(tag)
                    && value[0] != 0 && value[0] != 1) {
                throw new IOException("config field " + tag + " has non-canonical boolean");
            }
            return;
        }
        if (tag != CHAT_COORD_SENDER_BLACKLIST
                || taggedFieldPayloadLength(tag, value, 0) != value.length) {
            throw new IOException("malformed variable-length config field " + tag);
        }
    }

    private static int taggedFieldPayloadLength(int tag, byte[] bytes, int offset)
            throws IOException {
        int fixedLength = taggedFieldFixedLength(tag);
        if (fixedLength >= 0) {
            requireAvailable(bytes, offset, fixedLength);
            return fixedLength;
        }
        if (tag != CHAT_COORD_SENDER_BLACKLIST) {
            throw new IOException("unsupported config field tag " + tag);
        }
        int cursor = offset;
        int count = readUnsignedShort(bytes, cursor);
        cursor += Short.BYTES;
        for (int index = 0; index < count; index++) {
            int length = readUnsignedShort(bytes, cursor);
            cursor += Short.BYTES;
            requireAvailable(bytes, cursor, length);
            cursor += length;
        }
        return cursor - offset;
    }

    private static int taggedFieldFixedLength(int tag) {
        return switch (tag) {
            case DEFAULT_REACH_RADIUS, TRACER_OPACITY, TRACER_THICKNESS,
                    WAYPOINT_OUTLINE_THICKNESS, BEACON_OPACITY, LABEL_SCALE,
                    HIDE_WAYPOINTS_NEAR_RADIUS, HIDE_WAYPOINT_LABELS_NEAR_RADIUS,
                    MAX_STATIC_RENDER_DISTANCE, LABEL_HEIGHT_OFFSET,
                    WAYPOINT_MARKER_SCALE, WAYPOINT_OUTLINE_OPACITY -> Double.BYTES;
            case DEFAULT_WAYPOINT_COLOR, TRACER_COLOR, ROUTE_LINE_COLOR,
                    MAX_WAYPOINT_LABELS, IMPORTED_ROUTE_DEFAULT_COLOR,
                    TEMP_DEFAULT_MODE, TEMP_DEFAULT_DURATION_SEC,
                    DUNGEON_ENTRY_PATH_COLOR, WAYPOINT_OUTLINE_COLOR,
                    SEQUENCE_PREVIOUS_WAYPOINT_COUNT, SEQUENCE_NEXT_WAYPOINT_COUNT -> Integer.BYTES;
            case BOX_STYLE, BEACON_BEAM_MODE, IMPORTED_ROUTE_COLOR_MODE,
                    ETHERWARP_ALIGNMENT_SOUND_TYPE -> 1;
            case CHAT_COORD_SENDER_BLACKLIST -> -1;
            default -> isActiveFieldTag(tag) ? 1 : -2;
        };
    }

    private static boolean isBooleanFieldTag(int tag) {
        return isActiveFieldTag(tag) && taggedFieldFixedLength(tag) == 1
                && tag != BOX_STYLE && tag != BEACON_BEAM_MODE
                && tag != IMPORTED_ROUTE_COLOR_MODE
                && tag != ETHERWARP_ALIGNMENT_SOUND_TYPE;
    }

    private static int readUnsignedShort(byte[] bytes, int offset) throws IOException {
        requireAvailable(bytes, offset, Short.BYTES);
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static void requireAvailable(byte[] bytes, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IOException("truncated config field payload");
        }
    }

    static record TaggedField(int tag, byte[] value) {
        TaggedField {
            if (value == null) throw new IllegalArgumentException("null config field value");
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }

    private static byte[] deflate(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try (DeflaterOutputStream deflaterOut = new DeflaterOutputStream(out, deflater)) {
            deflaterOut.write(input);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 2);
        Inflater inflater = new Inflater(true);
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(input), inflater)) {
            byte[] buffer = new byte[512];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_INFLATED_BYTES) {
                    throw new IllegalArgumentException("Config code is too large");
                }
            }
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }
}
