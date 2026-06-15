package dev.ethan.waypointer.config;

import dev.ethan.waypointer.codec.AsciiStreamCodec;
import dev.ethan.waypointer.core.WaypointGroup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Compact pasteable codec for Waypointer settings.
 *
 * <p>The wire body is a tiny tagged diff from default settings, raw-deflated and
 * encoded with Waypointer's chat-safe ASCII stream alphabet. It deliberately
 * does not include waypoint groups; route sharing stays on the {@code WP:}
 * codec and settings sharing uses {@code WPC:}.
 */
public final class WaypointerConfigCodec {

    public static final String MAGIC = "WPC:";
    private static final int VERSION = 1;
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
    private static final int CHECK_FOR_UPDATES = 51;
    private static final int IRIS_SHADER_HUD_FALLBACK = 52;
    private static final int TEMP_DEFAULT_MODE = 53;
    private static final int TEMP_DEFAULT_DURATION_MIN = 54;

    /*[[AI-FN-DOC
Function:
WaypointerConfigCodec constructor
Purpose:
Prevent instantiation of a stateless codec utility class.
Why this exists:
All behavior is exposed through static encode and decode helpers, so instances would be misleading.
When to use:
Never call directly; Java may still reflectively see the constructor.
Inputs:
None.
Outputs:
No return value.
Side effects:
None.
Failure modes:
None.
Important invariants:
The codec remains a pure utility with no instance state.
Internal logic:
Use an empty private constructor.
Pseudocode:
do nothing
Implementation notes:
Matches the style of other codec utility classes in the repo.
AI self-check:
Verify no mutable state is stored on instances.
]]*/
    private WaypointerConfigCodec() {
    }

    /*[[AI-FN-DOC
Function:
encode
Purpose:
Convert a WaypointerConfig into a compact WPC: settings code.
Why this exists:
Users asked for short, fun import/export codes for Waypointer settings without bundling route data.
When to use:
Use from settings UI when the user clicks Copy config code. Do not use for route exports.
Inputs:
config is the settings object to encode; it must not be null.
Outputs:
Returns a string starting with WPC: that can be pasted into the matching decoder.
Side effects:
None.
Failure modes:
Throws IllegalArgumentException for null config and IllegalStateException if binary encoding unexpectedly fails.
Important invariants:
Only differences from a fresh default config are written, and omitted settings decode back to defaults.
Internal logic:
Write a version byte, write tagged non-default fields, terminate with tag 0, deflate the binary body, and ASCII-encode it.
Pseudocode:
if config null, throw
defaults = new WaypointerConfig
open binary output
write version
write non-default fields
write END tag
return MAGIC + AsciiStreamCodec.encode(deflate(binary))
Implementation notes:
DataOutputStream writes are wrapped because ByteArrayOutputStream should not throw normal IO failures.
AI self-check:
Verify every public setting included in replaceWith is represented here or intentionally derived.
]]*/
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

    /*[[AI-FN-DOC
Function:
decode
Purpose:
Parse a compact WPC: settings code into a fresh WaypointerConfig snapshot.
Why this exists:
Settings import needs a complete replacement object that starts from defaults and applies only fields present in the code.
When to use:
Use from settings UI before calling WaypointerConfig.replaceWith. Do not call for route import payloads.
Inputs:
code is a user-provided string that should start with WPC: and contain an ASCII stream encoded deflated body.
Outputs:
Returns a new WaypointerConfig with decoded settings applied over defaults.
Side effects:
None; the returned config has no saver attached.
Failure modes:
Throws IllegalArgumentException for missing prefix, invalid body characters, unsupported version, corrupt compression, unknown tags, or truncated fields.
Important invariants:
Malformed input never mutates the live config because mutation happens only after this method returns successfully.
Internal logic:
Validate prefix, decode and inflate the body, read the version byte, then apply tagged fields until the END tag.
Pseudocode:
trim code
if prefix missing, throw
body = decode ASCII after prefix
raw = inflate body
read version and validate
config = new defaults
while next tag != END:
  apply tag payload to config
return config
Implementation notes:
The inflate path has a fixed cap to avoid large allocation attacks from pasted text.
AI self-check:
Verify decode creates a new config and never touches the live config object directly.
]]*/
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
            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported config code version: " + version);
            }
            WaypointerConfig config = new WaypointerConfig();
            readFields(in, config);
            return config;
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed config code", e);
        }
    }

    /*[[AI-FN-DOC
Function:
writeFields
Purpose:
Write every non-default WaypointerConfig field into the tagged binary config-code stream.
Why this exists:
The compact codec needs a single audited list of persisted settings so encode, decode, replaceWith, and defaults stay aligned.
When to use:
Use only from encode after the version byte has been written and before the END tag is written.
Inputs:
out is the binary output stream; config is the source settings object; defaults is a fresh default config used for diffing.
Outputs:
No return value. Writes zero or more tagged field payloads to out.
Side effects:
Mutates the output stream position.
Failure modes:
Propagates IOException from DataOutputStream. Null inputs are not expected because encode controls the call.
Important invariants:
Only fields whose public getter value differs from defaults should be written; omitted fields must decode back to defaults.
Internal logic:
Call type-specific write helpers for each config property using stable numeric tags and matching default values.
Pseudocode:
for each supported config property:
  read actual value from config getter
  read default value from defaults getter
  call boolean/int/double/enum/list writer with the assigned tag
Implementation notes:
The explicit field list is long by design: it avoids reflection, keeps tag order stable, and makes schema additions reviewable.
AI self-check:
Verify every field copied by WaypointerConfig.replaceWith is represented here or intentionally derived elsewhere.
]]*/
    private static void writeFields(DataOutputStream out, WaypointerConfig config,
                                    WaypointerConfig defaults) throws IOException {
        writeDouble(out, DEFAULT_REACH_RADIUS, config.defaultReachRadius(), defaults.defaultReachRadius());
        writeBoolean(out, RESET_PROGRESS_ON_WORLD_JOIN, config.resetProgressOnWorldJoin(), defaults.resetProgressOnWorldJoin());
        writeBoolean(out, RESTART_ROUTE_WHEN_COMPLETE, config.restartRouteWhenComplete(), defaults.restartRouteWhenComplete());
        writeInt(out, DEFAULT_WAYPOINT_COLOR, config.defaultWaypointColor(), defaults.defaultWaypointColor());
        writeInt(out, TRACER_COLOR, config.tracerColor(), defaults.tracerColor());
        writeBoolean(out, MATCH_TRACER_TO_WAYPOINT_COLOR, config.matchTracerToWaypointColor(), defaults.matchTracerToWaypointColor());
        writeDouble(out, TRACER_OPACITY, config.tracerOpacity(), defaults.tracerOpacity());
        writeDouble(out, TRACER_THICKNESS, config.tracerThickness(), defaults.tracerThickness());
        writeDouble(out, WAYPOINT_OUTLINE_THICKNESS, config.waypointOutlineThickness(), defaults.waypointOutlineThickness());
        writeDouble(out, BEACON_OPACITY, config.beaconOpacity(), defaults.beaconOpacity());
        writeBoolean(out, SHOW_WAYPOINT_NAMES, config.showWaypointNames(), defaults.showWaypointNames());
        writeBoolean(out, SHOW_WAYPOINT_DISTANCES, config.showWaypointDistances(), defaults.showWaypointDistances());
        writeBoolean(out, SHOW_ROUTE_PROGRESS, config.showRouteProgress(), defaults.showRouteProgress());
        writeDouble(out, LABEL_SCALE, config.labelScale(), defaults.labelScale());
        writeBoolean(out, SCALE_WAYPOINT_TEXT_WITH_DISTANCE, config.scaleWaypointTextWithDistance(), defaults.scaleWaypointTextWithDistance());
        writeBoolean(out, MATCH_WAYPOINT_TEXT_TO_WAYPOINT_COLOR, config.matchWaypointTextToWaypointColor(), defaults.matchWaypointTextToWaypointColor());
        writeBoolean(out, SHOW_COMPLETED, config.showCompleted(), defaults.showCompleted());
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
        writeInt(out, ROUTE_LINE_COLOR, config.routeLineColor(), defaults.routeLineColor());
        writeBoolean(out, SHOW_LABEL_BACKDROP, config.showLabelBackdrop(), defaults.showLabelBackdrop());
        writeInt(out, MAX_WAYPOINT_LABELS, config.maxWaypointLabels(), defaults.maxWaypointLabels());
        writeDouble(out, MAX_STATIC_RENDER_DISTANCE, config.maxStaticWaypointRenderDistance(), defaults.maxStaticWaypointRenderDistance());
        writeDouble(out, LABEL_HEIGHT_OFFSET, config.labelHeightOffset(), defaults.labelHeightOffset());
        writeEnum(out, BOX_STYLE, config.boxStyle(), defaults.boxStyle());
        writeEnum(out, BEACON_BEAM_MODE, config.beaconBeamMode(), defaults.beaconBeamMode());
        writeBoolean(out, BEACON_BEAM_EXTENDS_BELOW, config.beaconBeamExtendsBelowWaypoint(), defaults.beaconBeamExtendsBelowWaypoint());
        writeBoolean(out, CHAT_COORD_DETECTION, config.chatCoordDetection(), defaults.chatCoordDetection());
        writeStringList(out, CHAT_COORD_SENDER_BLACKLIST, config.chatCoordSenderBlacklist(), defaults.chatCoordSenderBlacklist());
        writeBoolean(out, AUTO_ADD_CHAT_TEMP_WAYPOINTS, config.autoAddChatTempWaypoints(), defaults.autoAddChatTempWaypoints());
        writeBoolean(out, PLACE_NEW_WAYPOINTS_BELOW_PLAYER, config.placeNewWaypointsBelowPlayer(), defaults.placeNewWaypointsBelowPlayer());
        writeBoolean(out, FOCUS_TEMP_WAYPOINTS, config.focusTempWaypoints(), defaults.focusTempWaypoints());
        writeBoolean(out, CHAT_CODEC_DETECTION, config.chatCodecDetection(), defaults.chatCodecDetection());
        writeEnum(out, IMPORTED_ROUTE_COLOR_MODE, config.importedRouteColorMode(), defaults.importedRouteColorMode());
        writeInt(out, IMPORTED_ROUTE_DEFAULT_COLOR, config.importedRouteDefaultColor(), defaults.importedRouteDefaultColor());
        writeBoolean(out, EXPORT_INCLUDE_NAMES, config.exportIncludeNames(), defaults.exportIncludeNames());
        writeBoolean(out, EXPORT_INCLUDE_COLORS, config.exportIncludeColors(), defaults.exportIncludeColors());
        writeBoolean(out, EXPORT_INCLUDE_RADII, config.exportIncludeRadii(), defaults.exportIncludeRadii());
        writeBoolean(out, EXPORT_INCLUDE_WAYPOINT_FLAGS, config.exportIncludeWaypointFlags(), defaults.exportIncludeWaypointFlags());
        writeBoolean(out, EXPORT_INCLUDE_GROUP_META, config.exportIncludeGroupMeta(), defaults.exportIncludeGroupMeta());
        writeBoolean(out, DUNGEON_FEATURE, config.dungeonWaypointsFeatureEnabled(), defaults.dungeonWaypointsFeatureEnabled());
        writeBoolean(out, SKIP_AHEAD_MECHANIC, config.skipAheadMechanicEnabled(), defaults.skipAheadMechanicEnabled());
        writeBoolean(out, CHECK_FOR_UPDATES, config.checkForUpdates(), defaults.checkForUpdates());
        writeBoolean(out, IRIS_SHADER_HUD_FALLBACK, config.irisShaderHudFallback(), defaults.irisShaderHudFallback());
        writeInt(out, TEMP_DEFAULT_MODE, config.tempDefaultMode(), defaults.tempDefaultMode());
        writeInt(out, TEMP_DEFAULT_DURATION_MIN, config.tempDefaultDurationMin(), defaults.tempDefaultDurationMin());
    }

    /*[[AI-FN-DOC
Function:
readFields
Purpose:
Read tagged config-code fields into a fresh WaypointerConfig snapshot until the END tag appears.
Why this exists:
Decode must apply a sparse settings diff over defaults while rejecting unknown or truncated data before the live config is replaced.
When to use:
Use only from decode after prefix, compression, and version validation have succeeded.
Inputs:
in is positioned at the first field tag; config is the fresh settings snapshot to mutate.
Outputs:
No return value. Mutates config through public setters.
Side effects:
Reads from the input stream and calls config setters on the snapshot.
Failure modes:
Throws IOException for truncated payloads and IllegalArgumentException for unknown field tags.
Important invariants:
Unknown tags are rejected instead of skipped so unsupported future codes do not silently misconfigure current clients.
Internal logic:
Loop forever, read an unsigned tag, return on END, otherwise read the tag-specific payload and call the matching config setter.
Pseudocode:
while true:
  tag = read unsigned byte
  if tag == END return
  switch tag:
    read payload of expected type
    set corresponding config property
  unknown tag throws
Implementation notes:
Setters provide clamping/masking so decoded values follow the same validation rules as UI-edited values.
AI self-check:
Verify every tag written by writeFields has a matching read branch.
]]*/
    private static void readFields(DataInputStream in, WaypointerConfig config) throws IOException {
        while (true) {
            int tag = in.readUnsignedByte();
            if (tag == END) return;
            switch (tag) {
                case DEFAULT_REACH_RADIUS -> config.setDefaultReachRadius(in.readDouble());
                case RESET_PROGRESS_ON_WORLD_JOIN -> config.setResetProgressOnWorldJoin(in.readBoolean());
                case RESTART_ROUTE_WHEN_COMPLETE -> config.setRestartRouteWhenComplete(in.readBoolean());
                case DEFAULT_WAYPOINT_COLOR -> config.setDefaultWaypointColor(in.readInt());
                case TRACER_COLOR -> config.setTracerColor(in.readInt());
                case MATCH_TRACER_TO_WAYPOINT_COLOR -> config.setMatchTracerToWaypointColor(in.readBoolean());
                case TRACER_OPACITY -> config.setTracerOpacity(in.readDouble());
                case TRACER_THICKNESS -> config.setTracerThickness(in.readDouble());
                case WAYPOINT_OUTLINE_THICKNESS -> config.setWaypointOutlineThickness(in.readDouble());
                case BEACON_OPACITY -> config.setBeaconOpacity(in.readDouble());
                case SHOW_WAYPOINT_NAMES -> config.setShowWaypointNames(in.readBoolean());
                case SHOW_WAYPOINT_DISTANCES -> config.setShowWaypointDistances(in.readBoolean());
                case SHOW_ROUTE_PROGRESS -> config.setShowRouteProgress(in.readBoolean());
                case LABEL_SCALE -> config.setLabelScale(in.readDouble());
                case SCALE_WAYPOINT_TEXT_WITH_DISTANCE -> config.setScaleWaypointTextWithDistance(in.readBoolean());
                case MATCH_WAYPOINT_TEXT_TO_WAYPOINT_COLOR -> config.setMatchWaypointTextToWaypointColor(in.readBoolean());
                case SHOW_COMPLETED -> config.setShowCompleted(in.readBoolean());
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
                case ROUTE_LINE_COLOR -> config.setRouteLineColor(in.readInt());
                case SHOW_LABEL_BACKDROP -> config.setShowLabelBackdrop(in.readBoolean());
                case MAX_WAYPOINT_LABELS -> config.setMaxWaypointLabels(in.readInt());
                case MAX_STATIC_RENDER_DISTANCE -> config.setMaxStaticWaypointRenderDistance(in.readDouble());
                case LABEL_HEIGHT_OFFSET -> config.setLabelHeightOffset(in.readDouble());
                case BOX_STYLE -> config.setBoxStyle(readEnum(in, WaypointerConfig.BoxStyle.values(), WaypointerConfig.BoxStyle.OUTLINED));
                case BEACON_BEAM_MODE -> config.setBeaconBeamMode(readEnum(in, WaypointerConfig.BeaconBeamMode.values(), WaypointerConfig.BeaconBeamMode.OFF));
                case BEACON_BEAM_EXTENDS_BELOW -> config.setBeaconBeamExtendsBelowWaypoint(in.readBoolean());
                case CHAT_COORD_DETECTION -> config.setChatCoordDetection(in.readBoolean());
                case CHAT_COORD_SENDER_BLACKLIST -> readStringList(in).forEach(config::addChatCoordSenderBlacklist);
                case AUTO_ADD_CHAT_TEMP_WAYPOINTS -> config.setAutoAddChatTempWaypoints(in.readBoolean());
                case PLACE_NEW_WAYPOINTS_BELOW_PLAYER -> config.setPlaceNewWaypointsBelowPlayer(in.readBoolean());
                case FOCUS_TEMP_WAYPOINTS -> config.setFocusTempWaypoints(in.readBoolean());
                case CHAT_CODEC_DETECTION -> config.setChatCodecDetection(in.readBoolean());
                case IMPORTED_ROUTE_COLOR_MODE -> config.setImportedRouteColorMode(readEnum(in, WaypointGroup.GradientMode.values(), WaypointGroup.GradientMode.STATIC));
                case IMPORTED_ROUTE_DEFAULT_COLOR -> config.setImportedRouteDefaultColor(in.readInt());
                case EXPORT_INCLUDE_NAMES -> config.setExportIncludeNames(in.readBoolean());
                case EXPORT_INCLUDE_COLORS -> config.setExportIncludeColors(in.readBoolean());
                case EXPORT_INCLUDE_RADII -> config.setExportIncludeRadii(in.readBoolean());
                case EXPORT_INCLUDE_WAYPOINT_FLAGS -> config.setExportIncludeWaypointFlags(in.readBoolean());
                case EXPORT_INCLUDE_GROUP_META -> config.setExportIncludeGroupMeta(in.readBoolean());
                case DUNGEON_FEATURE -> config.setDungeonWaypointsFeatureEnabled(in.readBoolean());
                case SKIP_AHEAD_MECHANIC -> config.setSkipAheadMechanicEnabled(in.readBoolean());
                case CHECK_FOR_UPDATES -> config.setCheckForUpdates(in.readBoolean());
                case IRIS_SHADER_HUD_FALLBACK -> config.setIrisShaderHudFallback(in.readBoolean());
                case TEMP_DEFAULT_MODE -> config.setTempDefaultMode(in.readInt());
                case TEMP_DEFAULT_DURATION_MIN -> config.setTempDefaultDurationMin(in.readInt());
                default -> throw new IllegalArgumentException("Unknown config field tag: " + tag);
            }
        }
    }

    /*[[AI-FN-DOC
Function:
writeBoolean
Purpose:
Write a boolean field tag and value only when it differs from the default value.
Why this exists:
The config codec stores a compact diff, and booleans are numerous enough to benefit from one helper.
When to use:
Use from writeFields for boolean settings.
Inputs:
out is the binary output stream; tag is the stable field id; actual is the config value; defaultValue is the default config value.
Outputs:
No return value. May write one tag byte plus one boolean byte.
Side effects:
Mutates the output stream when actual differs from defaultValue.
Failure modes:
Propagates IOException from DataOutputStream.
Important invariants:
Equal-to-default values must not be written.
Internal logic:
Compare actual to default; return if equal; otherwise write tag and boolean payload.
Pseudocode:
if actual equals defaultValue return
write tag
write boolean actual
Implementation notes:
Keeping this helper tiny makes the field list in writeFields easier to audit.
AI self-check:
Verify callers pass the matching default getter for the same setting.
]]*/
    private static void writeBoolean(DataOutputStream out, int tag, boolean actual,
                                     boolean defaultValue) throws IOException {
        if (actual == defaultValue) return;
        out.writeByte(tag);
        out.writeBoolean(actual);
    }

    /*[[AI-FN-DOC
Function:
writeInt
Purpose:
Write an integer field tag and value only when it differs from the default value.
Why this exists:
Colors, durations, modes, and count limits share the same compact integer wire representation.
When to use:
Use from writeFields for int-backed settings.
Inputs:
out is the binary output stream; tag is the stable field id; actual is the config value; defaultValue is the default config value.
Outputs:
No return value. May write one tag byte plus a four-byte integer.
Side effects:
Mutates the output stream when actual differs from defaultValue.
Failure modes:
Propagates IOException from DataOutputStream.
Important invariants:
Equal-to-default values must be omitted from the diff stream.
Internal logic:
Return for equal values, otherwise write tag and int payload.
Pseudocode:
if actual equals defaultValue return
write tag
write int actual
Implementation notes:
Callers are expected to pass already-normalized getter values, such as masked RGB colors.
AI self-check:
Verify color callers use public getters so alpha bits are not encoded.
]]*/
    private static void writeInt(DataOutputStream out, int tag, int actual,
                                 int defaultValue) throws IOException {
        if (actual == defaultValue) return;
        out.writeByte(tag);
        out.writeInt(actual);
    }

    /*[[AI-FN-DOC
Function:
writeDouble
Purpose:
Write a double field tag and value only when it differs exactly from the default value.
Why this exists:
Several numeric settings are doubles and need a compact shared diff writer.
When to use:
Use from writeFields for double-backed config settings.
Inputs:
out is the binary output stream; tag is the stable field id; actual is the config value; defaultValue is the default config value.
Outputs:
No return value. May write one tag byte plus an eight-byte double.
Side effects:
Mutates the output stream when actual differs from defaultValue.
Failure modes:
Propagates IOException from DataOutputStream.
Important invariants:
Comparison uses Double.compare so negative zero and NaN behavior is explicit; public setters should prevent non-finite persisted values.
Internal logic:
If Double.compare reports equality return, otherwise write tag and double payload.
Pseudocode:
if Double.compare(actual, defaultValue) == 0 return
write tag
write double actual
Implementation notes:
Exact comparison is appropriate because values originate from stored config fields, not calculations that need epsilon tolerance.
AI self-check:
Verify decoded doubles still pass through config setters for clamping.
]]*/
    private static void writeDouble(DataOutputStream out, int tag, double actual,
                                    double defaultValue) throws IOException {
        if (Double.compare(actual, defaultValue) == 0) return;
        out.writeByte(tag);
        out.writeDouble(actual);
    }

    /*[[AI-FN-DOC
Function:
writeEnum
Purpose:
Write an enum field tag and ordinal only when the enum differs from its default.
Why this exists:
Enum-backed config settings need a compact representation without string names in the paste code.
When to use:
Use from writeFields for enum settings whose declaration order is stable for this codec version.
Inputs:
out is the binary output stream; tag is the stable field id; actual is the current enum; defaultValue is the default enum.
Outputs:
No return value. May write one tag byte plus one ordinal byte.
Side effects:
Mutates the output stream when actual differs from defaultValue.
Failure modes:
Propagates IOException. Null actual values are not expected because config getters provide fallbacks.
Important invariants:
Enum ordinal values are versioned by the WPC format version and must be read with the matching enum value array.
Internal logic:
Return when actual equals defaultValue, otherwise write tag and actual.ordinal().
Pseudocode:
if actual == defaultValue return
write tag
write byte actual ordinal
Implementation notes:
The codec is versioned so enum reorderings can be handled with a future version if needed.
AI self-check:
Verify decode uses readEnum with a safe fallback for the same enum type.
]]*/
    private static <E extends Enum<E>> void writeEnum(DataOutputStream out, int tag,
                                                      E actual, E defaultValue) throws IOException {
        if (actual == defaultValue) return;
        out.writeByte(tag);
        out.writeByte(actual.ordinal());
    }

    /*[[AI-FN-DOC
Function:
writeStringList
Purpose:
Write a string-list field only when it differs from the default list.
Why this exists:
The chat sender blacklist is user data that should round-trip through config codes without bloating default exports.
When to use:
Use from writeFields for ordered string-list settings.
Inputs:
out is the binary output stream; tag is the stable field id; actual is the current list; defaultValue is the default list.
Outputs:
No return value. May write tag, list size, and UTF entries.
Side effects:
Mutates the output stream when the list differs from the default.
Failure modes:
Propagates IOException. Null list entries are normalized to empty strings.
Important invariants:
List order is preserved because blacklist display and future list settings may care about user order.
Internal logic:
Return when lists are equal, otherwise write tag, unsigned-short-sized count, and each UTF string.
Pseudocode:
if actual equals defaultValue return
write tag
write short actual size
for each value in actual:
  write UTF value or empty string
Implementation notes:
The config currently keeps the blacklist small; the 32 KiB inflate cap bounds abuse from pasted codes.
AI self-check:
Verify readStringList reads the same count and UTF encoding.
]]*/
    private static void writeStringList(DataOutputStream out, int tag, List<String> actual,
                                        List<String> defaultValue) throws IOException {
        if (actual.equals(defaultValue)) return;
        out.writeByte(tag);
        out.writeShort(actual.size());
        for (String value : actual) {
            out.writeUTF(value == null ? "" : value);
        }
    }

    /*[[AI-FN-DOC
Function:
readStringList
Purpose:
Read an ordered UTF string list from the config-code stream.
Why this exists:
String-list payload decoding is shared by the blacklist field and keeps readFields focused on tag dispatch.
When to use:
Use from readFields immediately after a tag whose payload was written by writeStringList.
Inputs:
in is positioned at the list size field.
Outputs:
Returns a mutable list containing every decoded string in order.
Side effects:
Advances the input stream.
Failure modes:
Propagates IOException for truncated size or UTF payloads.
Important invariants:
The number of UTF reads must match the unsigned-short count written by writeStringList.
Internal logic:
Read the count, allocate an ArrayList of that size, read each UTF entry, and return the list.
Pseudocode:
size = read unsigned short
out = new ArrayList(size)
repeat size times:
  out.add(read UTF)
return out
Implementation notes:
The caller decides how to apply the strings so list normalization remains config-specific.
AI self-check:
Verify the loop cannot read past the declared count.
]]*/
    private static List<String> readStringList(DataInputStream in) throws IOException {
        int size = in.readUnsignedShort();
        java.util.ArrayList<String> out = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(in.readUTF());
        }
        return out;
    }

    /*[[AI-FN-DOC
Function:
readEnum
Purpose:
Read a one-byte enum ordinal and map it to a safe enum value.
Why this exists:
Enum payload decoding should tolerate out-of-range ordinals without crashing older clients unnecessarily.
When to use:
Use from readFields for enum-backed config tags.
Inputs:
in is positioned at the ordinal byte; values is the enum constants array; fallback is used when the ordinal is outside the array.
Outputs:
Returns the decoded enum value or fallback.
Side effects:
Advances the input stream by one byte.
Failure modes:
Propagates IOException if the ordinal byte is missing.
Important invariants:
Valid ordinals map to values from the same enum type that writeEnum encoded.
Internal logic:
Read unsigned byte and return values[ordinal] when it is inside bounds, otherwise fallback.
Pseudocode:
ordinal = read unsigned byte
if ordinal within values length return values[ordinal]
return fallback
Implementation notes:
Unknown enum values are less severe than unknown field tags because the setting can safely revert to a default mode.
AI self-check:
Verify fallback is never null at call sites.
]]*/
    private static <E extends Enum<E>> E readEnum(DataInputStream in, E[] values,
                                                  E fallback) throws IOException {
        int ordinal = in.readUnsignedByte();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    /*[[AI-FN-DOC
Function:
deflate
Purpose:
Compress the raw versioned config-code payload with raw DEFLATE.
Why this exists:
Compressed binary keeps WPC: codes short before the ASCII stream alphabet is applied.
When to use:
Use only from encode after the raw tagged payload has been fully written.
Inputs:
input is the raw binary config payload.
Outputs:
Returns compressed bytes using nowrap/raw DEFLATE mode.
Side effects:
Allocates compression buffers and closes the DeflaterOutputStream.
Failure modes:
Propagates IOException from the compression stream.
Important invariants:
The Deflater must be ended in all paths to avoid native-resource leaks.
Internal logic:
Create ByteArrayOutputStream, create raw best-compression Deflater, write input through DeflaterOutputStream, end deflater, and return bytes.
Pseudocode:
out = byte array stream
deflater = new Deflater(best, nowrap true)
try deflaterOut:
  write input
finally:
  end deflater
return out bytes
Implementation notes:
Best compression is acceptable because settings codes are tiny and copied manually.
AI self-check:
Verify inflate uses matching raw nowrap mode.
]]*/
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

    /*[[AI-FN-DOC
Function:
inflate
Purpose:
Decompress a raw DEFLATE config-code body while enforcing a maximum inflated size.
Why this exists:
Pasted text is untrusted, so decode needs bounded decompression before parsing the binary fields.
When to use:
Use only from decode after ASCII stream decoding succeeds.
Inputs:
input is the compressed raw DEFLATE byte array.
Outputs:
Returns the inflated binary config payload.
Side effects:
Allocates a bounded output buffer and advances an InflaterInputStream.
Failure modes:
Throws IOException for corrupt compression data and IllegalArgumentException when the inflated body exceeds MAX_INFLATED_BYTES.
Important invariants:
The Inflater must be ended in all paths, and output size must stay at or below the configured cap.
Internal logic:
Create raw Inflater, read chunks into an output stream, check size after each write, end the inflater, and return bytes.
Pseudocode:
out = byte array stream
inflater = raw inflater
try input stream:
  while read chunk:
    write chunk
    if out size over max throw
finally:
  inflater.end
return out bytes
Implementation notes:
The cap is intentionally much larger than expected config payloads but small enough to avoid decompression-bomb behavior.
AI self-check:
Verify malformed data cannot mutate live config because decode applies this before returning a replacement object.
]]*/
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
