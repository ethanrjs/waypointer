package com.babbur.waypointer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static com.babbur.waypointer.util.MathUtil.clamp;

/**
 * User-tunable runtime settings, persisted as JSON alongside the waypoint data.
 *
 * Not using owo-config's annotation processor here because we want dev loop
 * changes to the schema to not require a compile pass over generated sources --
 * a hand-written config with explicit defaults is simpler to evolve and keeps
 * build/runtime coupling low. Values are plain and Gson-friendly.
 *
 * All mutations should go through the setters so the dirty flag trips and the
 * autosave path fires. Callers read fields through getters to keep the door open
 * for future validation/constraints without a visible API churn.
 */
public final class WaypointerConfig {

    /**
     * How the world-space cube is drawn for each waypoint.
     *
     * OUTLINED is the old behaviour -- just the twelve edge lines.
     * FILLED hides the edges and draws six translucent faces, which reads as a
     * volume at distance where thin lines disappear against bright biomes.
     * FILLED_OUTLINED stacks both; the alpha on the fill is tuned so the edges
     * still register as the dominant cue on top.
     */
    public enum BoxStyle { OUTLINED, FILLED, FILLED_OUTLINED }

    /**
     * Which visible waypoints receive a vertical beacon-style guide.
     *
     * OFF preserves the historical render surface. CURRENT keeps the beam focused
     * on the immediate target for each active group, which is the useful default
     * for noisy temp/chat sessions. ALL_VISIBLE is for players who want every
     * shown marker to punch through terrain as a vertical reference.
     */
    public enum BeaconBeamMode { OFF, CURRENT, ALL_VISIBLE }

    private static final String FILE_NAME = "config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CONFIG_SCHEMA_VERSION = 4;
    private static final int TEMP_DURATION_MIN_SECONDS = 1;
    private static final int TEMP_DURATION_MAX_SECONDS = 24 * 60 * 60;
    private static final int SECONDS_PER_MINUTE = 60;

    // Progression
    private int configSchemaVersion = CONFIG_SCHEMA_VERSION;
    private double defaultReachRadius = Waypoint.DEFAULT_REACH_RADIUS;
    /**
     * When {@code true}, every waypoint group's progress index resets to 0 each time
     * the client connects to a world (single-player load or multiplayer join).
     * Matches the expectation that a play session starts fresh; turn off to keep
     * progress across reconnects (still persisted in waypoints.json).
     */
    private boolean resetProgressOnWorldJoin = true;
    /**
     * When {@code true}, finishing the last waypoint (route complete) immediately
     * wraps progress back to the first waypoint so farming / loop routes do not
     * stall in the completed state.
     */
    private boolean restartRouteWhenComplete = true;

    // Rendering -- tracer defaults to the same green as Waypoint.DEFAULT_COLOR so
    // a fresh install with matchTracerToWaypointColor=false still shows one
    // consistent color scheme across boxes and lines.
    private int defaultWaypointColor = Waypoint.DEFAULT_COLOR;
    private int tracerColor = 0x4FE05A;
    /**
     * When {@code true} (default), the tracer line to the current waypoint
     * inherits that waypoint's rendered colour -- so gradient groups draw a
     * tracer that smoothly changes hue as the user progresses, and a
     * manually-coloured checkpoint lights its tracer the same shade. Set
     * {@code false} to fall back to the flat {@link #tracerColor} override,
     * which is the old behaviour and useful if a user wants every tracer to
     * read as one distinct visual element regardless of the active waypoint.
     */
    private boolean matchTracerToWaypointColor = true;
    private double tracerOpacity = 0.95;
    /**
     * Pixel width for the crosshair tracer line. Defaults to the historical
     * hardcoded width; clamped so accidental config edits cannot create invisible
     * tracers or giant lines that flood the screen.
     */
    private double tracerThickness = 3.0;
    /**
     * Pixel width for waypoint box outlines in both the normal world renderer
     * and the Iris HUD fallback. Kept separate from tracer thickness because
     * users often want a subtle box but a stronger navigation line.
     */
    private double waypointOutlineThickness = 3.0;
    /**
     * Optional crisp edge mode for waypoint outlines. Default-off keeps the
     * current softer/thicker outline presentation unless the player opts into
     * harder screen-space corners.
     */
    private boolean sharpWaypointEdges = false;
    private double beaconOpacity = 0.8;
    private boolean showWaypointNames = true;
    private boolean showWaypointDistances = true;
    /**
     * Optional route progress percentage row for waypoint HUD labels.
     * Default-off keeps labels compact unless the player asks for route progress
     * at every visible waypoint.
     */
    private boolean showRouteProgress = false;
    /** User multiplier for waypoint HUD label size. */
    private double labelScale = 1.0;
    /**
     * Optional readability mode: labels shrink as their world anchor gets farther
     * from the camera. Default-off preserves the stable fixed-size HUD labels
     * existing users are used to.
     */
    private boolean scaleWaypointTextWithDistance = false;
    /**
     * When {@code true}, the primary waypoint label uses the waypoint's own RGB
     * instead of flat white. Default-on makes color-coded routes read as a single
     * visual system across boxes, tracers, and labels.
     */
    private boolean matchWaypointTextToWaypointColor = true;
    private boolean showCompleted = true;
    private boolean showTracer = true;
    /**
     * When {@code true}, SEQUENCE routes keep the active target prominent and
     * dim the surrounding context points (previous/current-location marker and
     * the point after the active target). Static routes keep their usual alpha.
     */
    private boolean dimSequenceContextWaypoints = true;
    /**
     * When {@code true} (default), groups in {@link com.babbur.waypointer.core.WaypointGroup.LoadMode#STATIC}
     * do not draw the crosshair tracer. Static routes already surface every waypoint, so the
     * line is often visual noise; {@link com.babbur.waypointer.core.WaypointGroup.LoadMode#SEQUENCE}
     * groups still get a tracer to the active breadcrumb target.
     */
    private boolean hideTracerOnStaticRoutes = true;
    /**
     * Optional proximity declutter: waypoints temporarily stop rendering while
     * the player stands near them, then reappear when the player walks away.
     * Default-off keeps existing routes visually stable.
     */
    private boolean hideWaypointsNearPlayer = false;
    /** Radius in blocks for {@link #hideWaypointsNearPlayer}. */
    private double hideWaypointsNearRadius = 5.0;
    /**
     * Optional label-only proximity declutter: labels hide near the player while
     * boxes, beams, and tracers remain visible.
     */
    private boolean hideWaypointLabelsNearPlayer = false;
    /** Radius in blocks for {@link #hideWaypointLabelsNearPlayer}. */
    private double hideWaypointLabelsNearRadius = 5.0;
    /**
     * Optional checklist behavior for STATIC groups: when the player enters a
     * waypoint's reach radius, that marker hides until every waypoint in the
     * group has been reached, then the whole group becomes visible again.
     */
    private boolean hideReachedStaticWaypointsUntilCycleComplete = false;
    /**
     * When skip-ahead is on, limit automatic proximity jumps to currently
     * visible route-context waypoints. Default-on keeps contextual routes from
     * silently jumping to a far-future waypoint the player could not see.
     */
    private boolean skipAheadOnlyVisibleWaypoints = true;
    /** Draw route connector segments between currently visible waypoints. */
    private boolean showRouteLines = false;
    /**
     * Draw a short ground path from the player to the first waypoint of the
     * current dungeon room while that first waypoint is still the active target.
     */
    private boolean showDungeonEntryPathToFirstWaypoint = false;
    /**
     * Extend the dungeon entry path to later active waypoints in sequenced room
     * routes. Default-off keeps the historical entry-only behavior.
     */
    private boolean showDungeonEntryPathToFollowingWaypoints = false;
    /** RGB color for the dungeon entry path and its arrows. */
    private int dungeonEntryPathColor = 0x00FF00;
    /** RGB color for route connector segments. Defaults to import green. */
    private int routeLineColor = 0x00FF00;
    /**
     * When {@code true}, each waypoint label draws a translucent black rectangle
     * behind its text for readability. Some players find it obtrusive in busy
     * routes where labels stack -- turning it off lets the text sit directly
     * against the world, vanilla-nametag style.
     */
    private boolean showLabelBackdrop = true;
    /** Draw the vanilla font shadow behind waypoint label text. */
    private boolean showLabelTextShadow = true;
    /**
     * Maximum number of waypoint labels drawn per frame, nearest first;
     * {@code 0} means unlimited. Defaults to the 32 nearest: labels are the
     * most expensive render feature by far (the perf stress test measured
     * unlimited labels on a dense route at -86% FPS), and more than ~30 are
     * not legible at once anyway. Existing installs keep whatever their saved
     * config says — Gson persists every field explicitly.
     */
    private int maxWaypointLabels = 32;
    /**
     * Optional distance gate for STATIC route markers. Sequenced routes remain
     * uncapped because their current target is navigationally important even
     * when it is far away; huge static overlays are the case where users can
     * trade cosmetic density for frame time.
     */
    private double maxStaticWaypointRenderDistance = 0.0;
    /**
     * Extra vertical offset (blocks) added on top of the renderer's baseline
     * label lift. The renderer already pushes labels {@code 1.6} blocks above
     * the waypoint's bottom corner -- enough to clear the cube at close range
     * but, by user report, not enough at distance where the label projects
     * directly over the marker. Players can dial this up so the label rides
     * higher and stops obscuring the cube from far away. Default {@code 0}
     * preserves the historical placement.
     *
     * <p>Intentionally unclamped: users testing long-distance routes need to
     * push labels much farther than a small "reasonable" range. We only reject
     * NaN / infinity in the setter so JSON stays valid and the renderer never
     * receives a non-finite coordinate.
     */
    private double labelHeightOffset = 0.0;
    private BoxStyle boxStyle = BoxStyle.OUTLINED;
    private BeaconBeamMode beaconBeamMode = BeaconBeamMode.OFF;
    private boolean beaconBeamExtendsBelowWaypoint = false;
    /**
     * When enabled, beacon beams use Minecraft's real beacon beam texture with
     * core/glow layers. Turning it off keeps the older flat colored quad beam,
     * which is cheaper for dense overlays.
     */
    private boolean useBeaconBeamTextures = true;
    /**
     * When enabled, edit-mode transitions and commits play small local UI cues.
     * Default-on makes mode changes feel acknowledged without sending anything
     * to the server.
     */
    private boolean editSounds = true;
    /**
     * When enabled, persistent edit mode draws a small aqua EDIT MODE subtitle on
     * the HUD so players know their clicks are modal.
     */
    private boolean showEditModeSubtitle = true;

    // Quality-of-life
    private boolean chatCoordDetection = true;
    /**
     * Plain usernames ignored by chat coordinate detection. Toggled from the
     * clickable red [B] action beside chat coordinates, and intentionally kept as
     * names rather than UUIDs because chat callouts only expose display text.
     */
    private List<String> chatCoordSenderBlacklist = new ArrayList<>();
    /**
     * When {@code true}, detected chat coordinate callouts immediately create
     * temp waypoints using the user's temp expiry defaults. Default-off keeps
     * chat detection click-to-add first unless the player explicitly opts into
     * automatic markers.
     */
    private boolean autoAddChatTempWaypoints = false;
    /**
     * Player-relative add flows default to the block below the player's feet so
     * newly-created markers sit on the floor instead of inside the player's body.
     * Explicit coordinate flows, such as {@code /wp add at}, keep using exactly
     * what the user typed.
     */
    private boolean placeNewWaypointsBelowPlayer = true;
    /**
     * When enabled, every user-created temp waypoint becomes the only waypoint
     * shown in the active zone until the server session ends or the temp vanishes.
     * This is transient render focus, not a persistent route enable/disable.
     */
    private boolean focusTempWaypoints = false;
    private boolean chatCodecDetection = true;
    private boolean showContributorBadges = true;
    /** Default color mode applied after any route import. */
    private WaypointGroup.GradientMode importedRouteColorMode = WaypointGroup.GradientMode.STATIC;
    /** Default one-color import palette: pure green, requested as RGB (0, 255, 0). */
    private int importedRouteDefaultColor = 0x00FF00;
    /** Lossless by default; users can explicitly disable names for smaller shares. */
    private boolean exportIncludeNames = true;
    /** Lossless by default; users can explicitly project colors to recipient defaults. */
    private boolean exportIncludeColors = true;
    /**
     * Per-waypoint custom radii. Included by default so tuned routes retain
     * their exact reach behavior unless the sender explicitly opts out.
     */
    private boolean exportIncludeRadii = true;
    /**
     * Persistent per-waypoint flags. Included by default; compact/lossy export
     * presets can explicitly omit non-structural flags for chat size.
     */
    private boolean exportIncludeWaypointFlags = true;
    /**
     * Group-level metadata: gradient mode, load mode, custom default radius.
     * On by default because a group with a non-default radius or sequenced load
     * mode will play very differently if these are stripped, and a recipient
     * has no way to know the original intent.
     */
    private boolean exportIncludeGroupMeta = true;
    /**
     * Legacy hidden flag retained so older config files and config-code imports
     * keep round-tripping. The dungeon subsystem now installs unconditionally;
     * {@code DungeonConfig.enabled()} is the runtime feature switch.
     */
    private boolean dungeonWaypointsFeatureEnabled = false;
    /**
     * Global gate for the "skip-ahead" proximity mechanic -- the behaviour where
     * walking into a later waypoint's radius advances progress past every
     * waypoint before it (rather than only advancing when the player reaches
     * the current target). Default {@code true} because skip-ahead is what
     * makes the mod useful for non-linear routes; disabling forces strict
     * sequential play for every group regardless of individual group settings.
     *
     * <p>Replaces the previous {@code skipWaypointKeybindEnabled} toggle --
     * the keybind itself is always consumable now (players who don't want to
     * skip just don't bind the key); the setting here is about the automatic
     * advancement the ProximityTracker performs based on position.
     *
     * <p>Works in concert with {@link com.babbur.waypointer.core.WaypointGroup}'s
     * per-group {@code skipAheadEnabled} flag: the group flag can disable
     * skip-ahead on a specific route (e.g. because a waypoint was just added
     * and would be skipped immediately) without touching the global mechanic.
     */
    private boolean skipAheadMechanicEnabled = true;

    /**
     * Experimental compatibility path for Iris shader packs that composite after
     * Waypointer's no-depth world render pass. When enabled, active Iris shaders
     * draw waypoint boxes/tracers as projected HUD overlays instead of world
     * geometry so shader depth buffers cannot hide them.
     */
    private boolean irisShaderHudFallback = false;
    /**
     * Default mode for the "Add Temp Waypoint Here" keybind, and the pre-selected
     * mode in the Add Temp modal. Values match
     * {@link com.babbur.waypointer.core.Waypoint}'s tempMode encoding:
     * 1 = time-based, 2 = until reached, 3 = until server leave.
     */
    private int tempDefaultMode = Waypoint.TEMP_TIME;
    /** Default duration (seconds) for time-based temp waypoints. */
    private int tempDefaultDurationSec = SECONDS_PER_MINUTE;

    /**
     * Debounce window for config writes. Configs mutate in bursts (EditBox
     * responders fire per keystroke, color pickers fire per slider tick); 500ms
     * is long enough for a typing burst to settle and short enough that a user
     * who clicks Done immediately after a change still gets their write before
     * any reasonable "did my change save?" doubt sets in.
     */
    private static final long SAVE_DEBOUNCE_MS = 500L;

    // Transient; never persisted.
    private transient Path file;
    private transient AsyncSaver saver;
    private transient boolean migratedDuringLoad;
    private transient volatile String pendingSnapshotJson;

    public static WaypointerConfig load() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(Waypointer.MOD_ID);
        Path file = dir.resolve(FILE_NAME);
        WaypointerConfig config;
        try {
            if (Files.exists(file)) {
                String raw = Files.readString(file);
                config = fromJson(raw);
            } else {
                config = new WaypointerConfig();
            }
        } catch (Exception e) {
            Waypointer.LOGGER.error("Failed to read config, using defaults", e);
            config = new WaypointerConfig();
        }
        config.file = file;
        config.saver = new AsyncSaver("config", config::writeToDisk, SAVE_DEBOUNCE_MS);
        if (config.migratedDuringLoad) config.save();
        return config;
    }
    static WaypointerConfig fromJson(String raw) {
        WaypointerConfig config = GSON.fromJson(raw, WaypointerConfig.class);
        if (config == null) config = new WaypointerConfig();
        int loadedSchemaVersion = schemaVersion(raw);
        config.migrateLegacyTempDurationMinutes(raw, loadedSchemaVersion);
        config.applyMigrations(loadedSchemaVersion);
        return config;
    }

    private static int schemaVersion(String raw) {
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed == null || !parsed.isJsonObject()) return CONFIG_SCHEMA_VERSION;
            JsonObject root = parsed.getAsJsonObject();
            return root.has("configSchemaVersion")
                    ? root.get("configSchemaVersion").getAsInt()
                    : 1;
        } catch (Exception ignored) {
            return CONFIG_SCHEMA_VERSION;
        }
    }
    private void applyMigrations(int schemaVersion) {
        if (schemaVersion < 2) {
            migrateIssue31TempDefaults();
        }
        configSchemaVersion = CONFIG_SCHEMA_VERSION;
    }
    private void migrateLegacyTempDurationMinutes(String raw, int schemaVersion) {
        int originalDurationSec = tempDefaultDurationSec;
        Integer legacyDurationMin = null;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();
                if (!root.has("tempDefaultDurationSec") && root.has("tempDefaultDurationMin")) {
                    legacyDurationMin = root.get("tempDefaultDurationMin").getAsInt();
                }
            }
        } catch (Exception ignored) {
            legacyDurationMin = null;
        }

        if (legacyDurationMin != null) {
            int migratedMinutes = schemaVersion < 2 && legacyDurationMin == 10
                    ? 1
                    : legacyDurationMin;
            tempDefaultDurationSec = clampTempDefaultDurationSec((long) migratedMinutes * SECONDS_PER_MINUTE);
        } else {
            tempDefaultDurationSec = clampTempDefaultDurationSec(tempDefaultDurationSec);
        }

        if (tempDefaultDurationSec != originalDurationSec) {
            migratedDuringLoad = true;
        }
    }
    private void migrateIssue31TempDefaults() {
        boolean changed = false;
        if (autoAddChatTempWaypoints) {
            autoAddChatTempWaypoints = false;
            changed = true;
        }
        if (tempDefaultMode == Waypoint.TEMP_UNTIL_REACHED) {
            tempDefaultMode = Waypoint.TEMP_TIME;
            changed = true;
        }
        if (changed) {
            migratedDuringLoad = true;
        }
    }

    /**
     * Mark the config dirty. Setters call this instead of hitting the disk
     * directly -- actual writes run on the shared saver thread after a short
     * quiet window (see {@link AsyncSaver}). Shutdown paths must call
     * {@link #flush()} to guarantee the last write completes.
     */
    public void save() {
        if (saver == null) return;
        pendingSnapshotJson = GSON.toJson(this);
        saver.markDirty();
    }

    /**
     * Synchronously flush any pending write. Called on client shutdown so the
     * last mutation lands on disk before the JVM exits.
     */
    public void flush() {
        if (saver != null) saver.flush();
    }

    private void writeToDisk() {
        if (file == null) return;
        String json = pendingSnapshotJson;
        if (json == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Waypointer.LOGGER.error("Failed to write config", e);
        }
    }

    // --- getters/setters ---------------------------------------------------------------------

    public double defaultReachRadius()        { return Waypoint.normalizeDefaultRadius(defaultReachRadius); }
    public boolean resetProgressOnWorldJoin() { return resetProgressOnWorldJoin; }
    public boolean restartRouteWhenComplete() { return restartRouteWhenComplete; }
    public int defaultWaypointColor()         { return defaultWaypointColor & 0xFFFFFF; }
    public int tracerColor()                  { return tracerColor; }
    public boolean matchTracerToWaypointColor() { return matchTracerToWaypointColor; }
    public double tracerOpacity()             { return tracerOpacity; }
    public double tracerThickness()           { return clamp(tracerThickness, 1.0, 12.0); }
    public double waypointOutlineThickness()  { return clamp(waypointOutlineThickness, 1.0, 12.0); }
    public boolean sharpWaypointEdges()        { return sharpWaypointEdges; }
    public double beaconOpacity()             { return beaconOpacity; }
    public boolean showWaypointNames()        { return showWaypointNames; }
    public boolean showWaypointDistances()    { return showWaypointDistances; }
    public boolean showRouteProgress()        { return showRouteProgress; }
        public double labelScale()                { return clamp(labelScale, 0.25, 4.0); }
    public boolean scaleWaypointTextWithDistance() { return scaleWaypointTextWithDistance; }
    public boolean matchWaypointTextToWaypointColor() { return matchWaypointTextToWaypointColor; }
    public boolean showCompleted()            { return showCompleted; }
    public boolean showTracer()               { return showTracer; }
    public boolean dimSequenceContextWaypoints() { return dimSequenceContextWaypoints; }
    public boolean hideTracerOnStaticRoutes() { return hideTracerOnStaticRoutes; }
    public boolean hideWaypointsNearPlayer()  { return hideWaypointsNearPlayer; }
    public double hideWaypointsNearRadius()   { return Math.max(0.5, hideWaypointsNearRadius); }
        public boolean hideWaypointLabelsNearPlayer() { return hideWaypointLabelsNearPlayer; }
        public double hideWaypointLabelsNearRadius() { return Math.max(0.5, hideWaypointLabelsNearRadius); }
    public boolean hideReachedStaticWaypointsUntilCycleComplete() { return hideReachedStaticWaypointsUntilCycleComplete; }
    public boolean skipAheadOnlyVisibleWaypoints() { return skipAheadOnlyVisibleWaypoints; }
    public boolean showRouteLines()           { return showRouteLines; }
    public boolean showDungeonEntryPathToFirstWaypoint() { return showDungeonEntryPathToFirstWaypoint; }
    public boolean showDungeonEntryPathToFollowingWaypoints() { return showDungeonEntryPathToFollowingWaypoints; }
    public int dungeonEntryPathColor()        { return dungeonEntryPathColor & 0xFFFFFF; }
    public int routeLineColor()               { return routeLineColor & 0xFFFFFF; }
    public boolean showLabelBackdrop()        { return showLabelBackdrop; }
    public boolean showLabelTextShadow()      { return showLabelTextShadow; }
    public int maxWaypointLabels()            { return Math.max(0, maxWaypointLabels); }
    public double maxStaticWaypointRenderDistance() {
        return Math.max(0.0, maxStaticWaypointRenderDistance);
    }
    public double labelHeightOffset()         { return labelHeightOffset; }
    public BoxStyle boxStyle()                { return boxStyle == null ? BoxStyle.OUTLINED : boxStyle; }
    public BeaconBeamMode beaconBeamMode()    {
        return beaconBeamMode == null ? BeaconBeamMode.OFF : beaconBeamMode;
    }
    public boolean beaconBeamExtendsBelowWaypoint() { return beaconBeamExtendsBelowWaypoint; }
    public boolean useBeaconBeamTextures() { return useBeaconBeamTextures; }
    public boolean editSounds() { return editSounds; }
    public boolean showEditModeSubtitle() { return showEditModeSubtitle; }
    public boolean chatCoordDetection()       { return chatCoordDetection; }
    public List<String> chatCoordSenderBlacklist() {
        ensureChatCoordSenderBlacklist();
        return List.copyOf(chatCoordSenderBlacklist);
    }
    public boolean isChatCoordSenderBlacklisted(String senderName) {
        String normalized = normalizeChatCoordSender(senderName);
        if (normalized.isEmpty()) return false;
        ensureChatCoordSenderBlacklist();
        for (String blocked : chatCoordSenderBlacklist) {
            if (blocked.equalsIgnoreCase(normalized)) return true;
        }
        return false;
    }
    public boolean autoAddChatTempWaypoints() { return autoAddChatTempWaypoints; }
    public boolean placeNewWaypointsBelowPlayer() { return placeNewWaypointsBelowPlayer; }
    public boolean focusTempWaypoints()       { return focusTempWaypoints; }
    public boolean chatCodecDetection()       { return chatCodecDetection; }
    public boolean showContributorBadges()    { return showContributorBadges; }
        public WaypointGroup.GradientMode importedRouteColorMode() {
        return importedRouteColorMode == null
                ? WaypointGroup.GradientMode.STATIC
                : importedRouteColorMode;
    }
        public int importedRouteDefaultColor()     { return importedRouteDefaultColor & 0xFFFFFF; }
    public boolean exportIncludeNames()        { return exportIncludeNames; }
    public boolean exportIncludeColors()       { return exportIncludeColors; }
    public boolean exportIncludeRadii()        { return exportIncludeRadii; }
    public boolean exportIncludeWaypointFlags(){ return exportIncludeWaypointFlags; }
    public boolean exportIncludeGroupMeta()    { return exportIncludeGroupMeta; }
    public boolean dungeonWaypointsFeatureEnabled() { return dungeonWaypointsFeatureEnabled; }
    public boolean skipAheadMechanicEnabled() { return skipAheadMechanicEnabled; }
    public boolean irisShaderHudFallback()      { return irisShaderHudFallback; }
    public int tempDefaultMode() {
        return tempDefaultMode < Waypoint.TEMP_TIME || tempDefaultMode > Waypoint.TEMP_UNTIL_LEAVE
                ? Waypoint.TEMP_TIME
                : tempDefaultMode;
    }
    public int tempDefaultDurationSec() {
        return clampTempDefaultDurationSec(tempDefaultDurationSec);
    }
    public int tempDefaultDurationMin() {
        int roundedUpMinutes = (tempDefaultDurationSec() + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE;
        return Math.max(1, Math.min(24 * 60, roundedUpMinutes));
    }
    public boolean tempWaypointsExpireByDefault() { return tempDefaultMode() == Waypoint.TEMP_TIME; }
    public long defaultTempExpiresAtMillis(long nowMillis) {
        return tempDefaultMode() == Waypoint.TEMP_TIME
                ? nowMillis + (long) tempDefaultDurationSec() * 1_000L
                : 0L;
    }

    public void setDefaultReachRadius(double v)        { this.defaultReachRadius = Waypoint.normalizeDefaultRadius(v); save(); }
    public void setResetProgressOnWorldJoin(boolean v) { this.resetProgressOnWorldJoin = v; save(); }
    public void setRestartRouteWhenComplete(boolean v) { this.restartRouteWhenComplete = v; save(); }
    public void setDefaultWaypointColor(int v)         { this.defaultWaypointColor = v & 0xFFFFFF; save(); }
    public void setTracerColor(int v)                  { this.tracerColor = v & 0xFFFFFF; save(); }
    public void setMatchTracerToWaypointColor(boolean v) { this.matchTracerToWaypointColor = v; save(); }
    public void setTracerOpacity(double v)             { this.tracerOpacity = clamp(v, 0, 1); save(); }
    public void setTracerThickness(double v) {
        if (!Double.isFinite(v)) return;
        this.tracerThickness = clamp(v, 1.0, 12.0);
        save();
    }
    public void setWaypointOutlineThickness(double v) {
        if (!Double.isFinite(v)) return;
        this.waypointOutlineThickness = clamp(v, 1.0, 12.0);
        save();
    }
    public void setSharpWaypointEdges(boolean v) {
        this.sharpWaypointEdges = v;
        save();
    }
    public void setBeaconOpacity(double v)             { this.beaconOpacity = clamp(v, 0, 1); save(); }
    public void setShowWaypointNames(boolean v)        { this.showWaypointNames = v; save(); }
    public void setShowWaypointDistances(boolean v)    { this.showWaypointDistances = v; save(); }
    public void setShowRouteProgress(boolean v)        { this.showRouteProgress = v; save(); }
        public void setLabelScale(double v) {
        if (!Double.isFinite(v)) return;
        this.labelScale = clamp(v, 0.25, 4.0);
        save();
    }
    public void setScaleWaypointTextWithDistance(boolean v) { this.scaleWaypointTextWithDistance = v; save(); }
    public void setMatchWaypointTextToWaypointColor(boolean v) { this.matchWaypointTextToWaypointColor = v; save(); }
    public void setShowCompleted(boolean v)            { this.showCompleted = v; save(); }
    public void setShowTracer(boolean v)               { this.showTracer = v; save(); }
    public void setDimSequenceContextWaypoints(boolean v) { this.dimSequenceContextWaypoints = v; save(); }
    public void setHideTracerOnStaticRoutes(boolean v) { this.hideTracerOnStaticRoutes = v; save(); }
    public void setHideWaypointsNearPlayer(boolean v) { this.hideWaypointsNearPlayer = v; save(); }
    public void setHideWaypointsNearRadius(double v) {
        if (!Double.isFinite(v)) return;
        this.hideWaypointsNearRadius = clamp(v, 0.5, 100.0);
        save();
    }
        public void setHideWaypointLabelsNearPlayer(boolean v) {
        this.hideWaypointLabelsNearPlayer = v;
        save();
    }
        public void setHideWaypointLabelsNearRadius(double v) {
        if (!Double.isFinite(v)) return;
        this.hideWaypointLabelsNearRadius = clamp(v, 0.5, 100.0);
        save();
    }
    public void setHideReachedStaticWaypointsUntilCycleComplete(boolean v) {
        this.hideReachedStaticWaypointsUntilCycleComplete = v;
        save();
    }
    public void setSkipAheadOnlyVisibleWaypoints(boolean v) {
        this.skipAheadOnlyVisibleWaypoints = v;
        save();
    }
    public void setShowRouteLines(boolean v) {
        this.showRouteLines = v;
        save();
    }
    public void setShowDungeonEntryPathToFirstWaypoint(boolean v) {
        this.showDungeonEntryPathToFirstWaypoint = v;
        save();
    }
    public void setShowDungeonEntryPathToFollowingWaypoints(boolean v) {
        this.showDungeonEntryPathToFollowingWaypoints = v;
        save();
    }
    public void setDungeonEntryPathColor(int v) {
        this.dungeonEntryPathColor = v & 0xFFFFFF;
        save();
    }
    public void setRouteLineColor(int v) {
        this.routeLineColor = v & 0xFFFFFF;
        save();
    }
    public void setChatCoordDetection(boolean v)       { this.chatCoordDetection = v; save(); }
    public boolean addChatCoordSenderBlacklist(String senderName) {
        String normalized = normalizeChatCoordSender(senderName);
        if (normalized.isEmpty() || isChatCoordSenderBlacklisted(normalized)) return false;
        ensureChatCoordSenderBlacklist();
        chatCoordSenderBlacklist.add(normalized);
        save();
        return true;
    }
    public boolean removeChatCoordSenderBlacklist(String senderName) {
        String normalized = normalizeChatCoordSender(senderName);
        if (normalized.isEmpty()) return false;
        ensureChatCoordSenderBlacklist();
        for (int i = 0; i < chatCoordSenderBlacklist.size(); i++) {
            if (chatCoordSenderBlacklist.get(i).equalsIgnoreCase(normalized)) {
                chatCoordSenderBlacklist.remove(i);
                save();
                return true;
            }
        }
        return false;
    }
    public boolean toggleChatCoordSenderBlacklist(String senderName) {
        return removeChatCoordSenderBlacklist(senderName)
                ? false
                : addChatCoordSenderBlacklist(senderName);
    }
    public void setAutoAddChatTempWaypoints(boolean v) { this.autoAddChatTempWaypoints = v; save(); }
    public void setPlaceNewWaypointsBelowPlayer(boolean v) { this.placeNewWaypointsBelowPlayer = v; save(); }
    public void setFocusTempWaypoints(boolean v)       { this.focusTempWaypoints = v; save(); }
    public void setChatCodecDetection(boolean v)       { this.chatCodecDetection = v; save(); }
    public void setShowContributorBadges(boolean v)    { this.showContributorBadges = v; save(); }
        public void setImportedRouteColorMode(WaypointGroup.GradientMode v) {
        this.importedRouteColorMode = v == null ? WaypointGroup.GradientMode.STATIC : v;
        save();
    }
        public void setImportedRouteDefaultColor(int v) {
        this.importedRouteDefaultColor = v & 0xFFFFFF;
        save();
    }
    public void setExportIncludeNames(boolean v)        { this.exportIncludeNames = v; save(); }
    public void setExportIncludeColors(boolean v)       { this.exportIncludeColors = v; save(); }
    public void setExportIncludeRadii(boolean v)        { this.exportIncludeRadii = v; save(); }
    public void setExportIncludeWaypointFlags(boolean v){ this.exportIncludeWaypointFlags = v; save(); }
    public void setExportIncludeGroupMeta(boolean v)    { this.exportIncludeGroupMeta = v; save(); }
    public void setDungeonWaypointsFeatureEnabled(boolean v) { this.dungeonWaypointsFeatureEnabled = v; save(); }
    public void setShowLabelBackdrop(boolean v)        { this.showLabelBackdrop = v; save(); }
    public void setShowLabelTextShadow(boolean v)      { this.showLabelTextShadow = v; save(); }
    public void setMaxWaypointLabels(int v) {
        this.maxWaypointLabels = Math.max(0, v);
        save();
    }
    public void setMaxStaticWaypointRenderDistance(double v) {
        if (!Double.isFinite(v)) return;
        this.maxStaticWaypointRenderDistance = Math.max(0.0, v);
        save();
    }
    public void setLabelHeightOffset(double v) {
        if (!Double.isFinite(v)) return;
        this.labelHeightOffset = v;
        save();
    }
    public void setBoxStyle(BoxStyle v)                { this.boxStyle = v == null ? BoxStyle.OUTLINED : v; save(); }
    public void setBeaconBeamMode(BeaconBeamMode v)    {
        this.beaconBeamMode = v == null ? BeaconBeamMode.OFF : v;
        save();
    }
    public void setBeaconBeamExtendsBelowWaypoint(boolean v) {
        this.beaconBeamExtendsBelowWaypoint = v;
        save();
    }
    public void setUseBeaconBeamTextures(boolean v) {
        this.useBeaconBeamTextures = v;
        save();
    }
    public void setEditSounds(boolean v) {
        this.editSounds = v;
        save();
    }
    public void setShowEditModeSubtitle(boolean v) {
        this.showEditModeSubtitle = v;
        save();
    }
    public void setSkipAheadMechanicEnabled(boolean v) { this.skipAheadMechanicEnabled = v; save(); }
    public void setIrisShaderHudFallback(boolean v)    { this.irisShaderHudFallback = v; save(); }
    public void setTempDefaultMode(int v) {
        int clamped = (v < Waypoint.TEMP_TIME || v > Waypoint.TEMP_UNTIL_LEAVE)
                ? Waypoint.TEMP_TIME
                : v;
        this.tempDefaultMode = clamped;
        save();
    }
    public void setTempDefaultDurationSec(int v) {
        this.tempDefaultDurationSec = clampTempDefaultDurationSec(v);
        save();
    }
    public void setTempDefaultDurationMin(int v) {
        int clampedMinutes = Math.max(1, Math.min(24 * 60, v));
        this.tempDefaultDurationSec = clampedMinutes * SECONDS_PER_MINUTE;
        save();
    }
    public void setTempWaypointsExpireByDefault(boolean v) {
        setTempDefaultMode(v ? Waypoint.TEMP_TIME : Waypoint.TEMP_UNTIL_LEAVE);
    }
    public void replaceWith(WaypointerConfig replacement) {
        if (replacement == null) return;
        configSchemaVersion = CONFIG_SCHEMA_VERSION;
        defaultReachRadius = replacement.defaultReachRadius;
        resetProgressOnWorldJoin = replacement.resetProgressOnWorldJoin;
        restartRouteWhenComplete = replacement.restartRouteWhenComplete;
        defaultWaypointColor = replacement.defaultWaypointColor;
        tracerColor = replacement.tracerColor;
        matchTracerToWaypointColor = replacement.matchTracerToWaypointColor;
        tracerOpacity = replacement.tracerOpacity;
        tracerThickness = replacement.tracerThickness;
        waypointOutlineThickness = replacement.waypointOutlineThickness;
        sharpWaypointEdges = replacement.sharpWaypointEdges;
        beaconOpacity = replacement.beaconOpacity;
        showWaypointNames = replacement.showWaypointNames;
        showWaypointDistances = replacement.showWaypointDistances;
        showRouteProgress = replacement.showRouteProgress;
        labelScale = replacement.labelScale;
        scaleWaypointTextWithDistance = replacement.scaleWaypointTextWithDistance;
        matchWaypointTextToWaypointColor = replacement.matchWaypointTextToWaypointColor;
        showCompleted = replacement.showCompleted;
        showTracer = replacement.showTracer;
        dimSequenceContextWaypoints = replacement.dimSequenceContextWaypoints;
        hideTracerOnStaticRoutes = replacement.hideTracerOnStaticRoutes;
        hideWaypointsNearPlayer = replacement.hideWaypointsNearPlayer;
        hideWaypointsNearRadius = replacement.hideWaypointsNearRadius;
        hideWaypointLabelsNearPlayer = replacement.hideWaypointLabelsNearPlayer;
        hideWaypointLabelsNearRadius = replacement.hideWaypointLabelsNearRadius;
        hideReachedStaticWaypointsUntilCycleComplete = replacement.hideReachedStaticWaypointsUntilCycleComplete;
        skipAheadOnlyVisibleWaypoints = replacement.skipAheadOnlyVisibleWaypoints;
        showRouteLines = replacement.showRouteLines;
        showDungeonEntryPathToFirstWaypoint = replacement.showDungeonEntryPathToFirstWaypoint;
        showDungeonEntryPathToFollowingWaypoints = replacement.showDungeonEntryPathToFollowingWaypoints;
        dungeonEntryPathColor = replacement.dungeonEntryPathColor;
        routeLineColor = replacement.routeLineColor;
        showLabelBackdrop = replacement.showLabelBackdrop;
        showLabelTextShadow = replacement.showLabelTextShadow;
        maxWaypointLabels = replacement.maxWaypointLabels;
        maxStaticWaypointRenderDistance = replacement.maxStaticWaypointRenderDistance;
        labelHeightOffset = replacement.labelHeightOffset;
        boxStyle = replacement.boxStyle;
        beaconBeamMode = replacement.beaconBeamMode;
        beaconBeamExtendsBelowWaypoint = replacement.beaconBeamExtendsBelowWaypoint;
        useBeaconBeamTextures = replacement.useBeaconBeamTextures;
        editSounds = replacement.editSounds;
        showEditModeSubtitle = replacement.showEditModeSubtitle;
        chatCoordDetection = replacement.chatCoordDetection;
        chatCoordSenderBlacklist = new ArrayList<>(replacement.chatCoordSenderBlacklist);
        autoAddChatTempWaypoints = replacement.autoAddChatTempWaypoints;
        placeNewWaypointsBelowPlayer = replacement.placeNewWaypointsBelowPlayer;
        focusTempWaypoints = replacement.focusTempWaypoints;
        chatCodecDetection = replacement.chatCodecDetection;
        showContributorBadges = replacement.showContributorBadges;
        importedRouteColorMode = replacement.importedRouteColorMode;
        importedRouteDefaultColor = replacement.importedRouteDefaultColor;
        exportIncludeNames = replacement.exportIncludeNames;
        exportIncludeColors = replacement.exportIncludeColors;
        exportIncludeRadii = replacement.exportIncludeRadii;
        exportIncludeWaypointFlags = replacement.exportIncludeWaypointFlags;
        exportIncludeGroupMeta = replacement.exportIncludeGroupMeta;
        dungeonWaypointsFeatureEnabled = replacement.dungeonWaypointsFeatureEnabled;
        skipAheadMechanicEnabled = replacement.skipAheadMechanicEnabled;
        irisShaderHudFallback = replacement.irisShaderHudFallback;
        tempDefaultMode = replacement.tempDefaultMode;
        tempDefaultDurationSec = replacement.tempDefaultDurationSec;
        migratedDuringLoad = false;
        save();
    }

    public void disableAllSettings() {
        resetProgressOnWorldJoin = false;
        restartRouteWhenComplete = false;
        matchTracerToWaypointColor = false;
        showWaypointNames = false;
        showWaypointDistances = false;
        showRouteProgress = false;
        sharpWaypointEdges = false;
        beaconOpacity = 0.0;
        scaleWaypointTextWithDistance = false;
        matchWaypointTextToWaypointColor = false;
        showCompleted = false;
        showTracer = false;
        dimSequenceContextWaypoints = false;
        hideTracerOnStaticRoutes = false;
        hideWaypointsNearPlayer = false;
        hideWaypointLabelsNearPlayer = false;
        hideReachedStaticWaypointsUntilCycleComplete = false;
        skipAheadOnlyVisibleWaypoints = false;
        showRouteLines = false;
        showDungeonEntryPathToFirstWaypoint = false;
        showDungeonEntryPathToFollowingWaypoints = false;
        showLabelBackdrop = false;
        showLabelTextShadow = false;
        beaconBeamExtendsBelowWaypoint = false;
        useBeaconBeamTextures = false;
        editSounds = false;
        showEditModeSubtitle = false;
        chatCoordDetection = false;
        autoAddChatTempWaypoints = false;
        placeNewWaypointsBelowPlayer = false;
        focusTempWaypoints = false;
        chatCodecDetection = false;
        showContributorBadges = false;
        importedRouteColorMode = WaypointGroup.GradientMode.MANUAL;
        exportIncludeNames = false;
        exportIncludeColors = false;
        exportIncludeRadii = false;
        exportIncludeWaypointFlags = false;
        exportIncludeGroupMeta = false;
        dungeonWaypointsFeatureEnabled = false;
        skipAheadMechanicEnabled = false;
        irisShaderHudFallback = false;
        beaconBeamMode = BeaconBeamMode.OFF;
        tempDefaultMode = Waypoint.TEMP_UNTIL_LEAVE;
        save();
    }

    public void disableAllSettings(DungeonConfig dungeonConfig) {
        disableAllSettings();
        if (dungeonConfig != null) dungeonConfig.disableAllSettings();
    }
    public void resetToDefaults() {
        WaypointerConfig defaults = new WaypointerConfig();
        configSchemaVersion = CONFIG_SCHEMA_VERSION;
        defaultReachRadius = defaults.defaultReachRadius;
        resetProgressOnWorldJoin = defaults.resetProgressOnWorldJoin;
        restartRouteWhenComplete = defaults.restartRouteWhenComplete;
        defaultWaypointColor = defaults.defaultWaypointColor;
        tracerColor = defaults.tracerColor;
        matchTracerToWaypointColor = defaults.matchTracerToWaypointColor;
        tracerOpacity = defaults.tracerOpacity;
        tracerThickness = defaults.tracerThickness;
        waypointOutlineThickness = defaults.waypointOutlineThickness;
        sharpWaypointEdges = defaults.sharpWaypointEdges;
        beaconOpacity = defaults.beaconOpacity;
        showWaypointNames = defaults.showWaypointNames;
        showWaypointDistances = defaults.showWaypointDistances;
        showRouteProgress = defaults.showRouteProgress;
        labelScale = defaults.labelScale;
        scaleWaypointTextWithDistance = defaults.scaleWaypointTextWithDistance;
        matchWaypointTextToWaypointColor = defaults.matchWaypointTextToWaypointColor;
        showCompleted = defaults.showCompleted;
        showTracer = defaults.showTracer;
        dimSequenceContextWaypoints = defaults.dimSequenceContextWaypoints;
        hideTracerOnStaticRoutes = defaults.hideTracerOnStaticRoutes;
        hideWaypointsNearPlayer = defaults.hideWaypointsNearPlayer;
        hideWaypointsNearRadius = defaults.hideWaypointsNearRadius;
        hideWaypointLabelsNearPlayer = defaults.hideWaypointLabelsNearPlayer;
        hideWaypointLabelsNearRadius = defaults.hideWaypointLabelsNearRadius;
        hideReachedStaticWaypointsUntilCycleComplete = defaults.hideReachedStaticWaypointsUntilCycleComplete;
        skipAheadOnlyVisibleWaypoints = defaults.skipAheadOnlyVisibleWaypoints;
        showRouteLines = defaults.showRouteLines;
        showDungeonEntryPathToFirstWaypoint = defaults.showDungeonEntryPathToFirstWaypoint;
        showDungeonEntryPathToFollowingWaypoints = defaults.showDungeonEntryPathToFollowingWaypoints;
        dungeonEntryPathColor = defaults.dungeonEntryPathColor;
        routeLineColor = defaults.routeLineColor;
        showLabelBackdrop = defaults.showLabelBackdrop;
        showLabelTextShadow = defaults.showLabelTextShadow;
        maxWaypointLabels = defaults.maxWaypointLabels;
        maxStaticWaypointRenderDistance = defaults.maxStaticWaypointRenderDistance;
        labelHeightOffset = defaults.labelHeightOffset;
        boxStyle = defaults.boxStyle;
        beaconBeamMode = defaults.beaconBeamMode;
        beaconBeamExtendsBelowWaypoint = defaults.beaconBeamExtendsBelowWaypoint;
        useBeaconBeamTextures = defaults.useBeaconBeamTextures;
        editSounds = defaults.editSounds;
        showEditModeSubtitle = defaults.showEditModeSubtitle;
        chatCoordDetection = defaults.chatCoordDetection;
        chatCoordSenderBlacklist = new ArrayList<>(defaults.chatCoordSenderBlacklist);
        autoAddChatTempWaypoints = defaults.autoAddChatTempWaypoints;
        placeNewWaypointsBelowPlayer = defaults.placeNewWaypointsBelowPlayer;
        focusTempWaypoints = defaults.focusTempWaypoints;
        chatCodecDetection = defaults.chatCodecDetection;
        showContributorBadges = defaults.showContributorBadges;
        importedRouteColorMode = defaults.importedRouteColorMode;
        importedRouteDefaultColor = defaults.importedRouteDefaultColor;
        exportIncludeNames = defaults.exportIncludeNames;
        exportIncludeColors = defaults.exportIncludeColors;
        exportIncludeRadii = defaults.exportIncludeRadii;
        exportIncludeWaypointFlags = defaults.exportIncludeWaypointFlags;
        exportIncludeGroupMeta = defaults.exportIncludeGroupMeta;
        dungeonWaypointsFeatureEnabled = defaults.dungeonWaypointsFeatureEnabled;
        skipAheadMechanicEnabled = defaults.skipAheadMechanicEnabled;
        irisShaderHudFallback = defaults.irisShaderHudFallback;
        tempDefaultMode = defaults.tempDefaultMode;
        tempDefaultDurationSec = defaults.tempDefaultDurationSec;
        migratedDuringLoad = false;
        save();
    }
    private static int clampTempDefaultDurationSec(long seconds) {
        if (seconds < TEMP_DURATION_MIN_SECONDS) return TEMP_DURATION_MIN_SECONDS;
        if (seconds > TEMP_DURATION_MAX_SECONDS) return TEMP_DURATION_MAX_SECONDS;
        return (int) seconds;
    }

    private void ensureChatCoordSenderBlacklist() {
        if (chatCoordSenderBlacklist == null) {
            chatCoordSenderBlacklist = new ArrayList<>();
        }
    }

    private static String normalizeChatCoordSender(String senderName) {
        if (senderName == null) return "";
        String trimmed = senderName.trim();
        if (trimmed.length() > 16) trimmed = trimmed.substring(0, 16);
        return trimmed;
    }
}
