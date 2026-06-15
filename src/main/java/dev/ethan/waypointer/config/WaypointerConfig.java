package dev.ethan.waypointer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static dev.ethan.waypointer.util.MathUtil.clamp;

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
    private static final int CONFIG_SCHEMA_VERSION = 3;

    // Progression
    private int configSchemaVersion = CONFIG_SCHEMA_VERSION;
    private double defaultReachRadius = 3.0;
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
     * When {@code true} (default), groups in {@link dev.ethan.waypointer.core.WaypointGroup.LoadMode#STATIC}
     * do not draw the crosshair tracer. Static routes already surface every waypoint, so the
     * line is often visual noise; {@link dev.ethan.waypointer.core.WaypointGroup.LoadMode#SEQUENCE}
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
    /** RGB color for route connector segments. Defaults to import green. */
    private int routeLineColor = 0x00FF00;
    /**
     * When {@code true}, each waypoint label draws a translucent black rectangle
     * behind its text for readability. Some players find it obtrusive in busy
     * routes where labels stack -- turning it off lets the text sit directly
     * against the world, vanilla-nametag style.
     */
    private boolean showLabelBackdrop = true;
    /**
     * Maximum number of waypoint labels drawn per frame, nearest first.
     * {@code 0} means unlimited so existing configs keep their historical
     * "draw everything" behaviour until the user opts into the performance
     * tradeoff.
     */
    private int maxWaypointLabels = 0;
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
    /** Default color mode applied after any route import. */
    private WaypointGroup.GradientMode importedRouteColorMode = WaypointGroup.GradientMode.STATIC;
    /** Default one-color import palette: pure green, requested as RGB (0, 255, 0). */
    private int importedRouteDefaultColor = 0x00FF00;
    /** Default exports drop names; set to {@code true} to include them at the cost of length. */
    private boolean exportIncludeNames = false;
    /** Default exports drop colors so shared routes inherit the recipient's palette. */
    private boolean exportIncludeColors = false;
    /**
     * Per-waypoint custom radii. Off by default because most routes use the
     * group default radius; including them only matters when the sender
     * deliberately tuned individual waypoints.
     */
    private boolean exportIncludeRadii = false;
    /**
     * Per-waypoint flags (currently just "shown") -- almost always identical to
     * defaults, so off by default to keep payloads short.
     */
    private boolean exportIncludeWaypointFlags = false;
    /**
     * Group-level metadata: gradient mode, load mode, custom default radius.
     * On by default because a group with a non-default radius or sequenced load
     * mode will play very differently if these are stripped, and a recipient
     * has no way to know the original intent.
     */
    private boolean exportIncludeGroupMeta = true;
    /**
     * Hidden feature flag for the in-progress dungeon waypoint subsystem.
     * Default-off keeps beta builds from registering commands, renderers, tick
     * hooks, or dungeon data stores until the feature is ready to ship.
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
     * <p>Works in concert with {@link dev.ethan.waypointer.core.WaypointGroup}'s
     * per-group {@code skipAheadEnabled} flag: the group flag can disable
     * skip-ahead on a specific route (e.g. because a waypoint was just added
     * and would be skipped immediately) without touching the global mechanic.
     */
    private boolean skipAheadMechanicEnabled = true;

    /**
     * Gate for the GitHub update checker. Off means no outbound HTTP at all --
     * privacy-minded users can disable it without losing the rest of the mod.
     */
    private boolean checkForUpdates = true;
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
     * {@link dev.ethan.waypointer.core.Waypoint}'s tempMode encoding:
     * 1 = time-based, 2 = until reached, 3 = until server leave.
     */
    private int tempDefaultMode = Waypoint.TEMP_TIME;
    /** Default duration (minutes) for time-based temp waypoints. */
    private int tempDefaultDurationMin = 1;

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
        config.applyMigrations(schemaVersion(raw));
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
        if (tempDefaultDurationMin == 10) {
            tempDefaultDurationMin = 1;
            changed = true;
        }
        migratedDuringLoad = changed;
    }

    /**
     * Mark the config dirty. Setters call this instead of hitting the disk
     * directly -- actual writes run on the shared saver thread after a short
     * quiet window (see {@link AsyncSaver}). Shutdown paths must call
     * {@link #flush()} to guarantee the last write completes.
     */
    public void save() {
        if (saver == null) return;
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
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Waypointer.LOGGER.error("Failed to write config", e);
        }
    }

    // --- getters/setters ---------------------------------------------------------------------

    public double defaultReachRadius()        { return defaultReachRadius; }
    public boolean resetProgressOnWorldJoin() { return resetProgressOnWorldJoin; }
    public boolean restartRouteWhenComplete() { return restartRouteWhenComplete; }
    /*[[AI-FN-DOC
Function:
defaultWaypointColor
Purpose:
Expose the RGB color assigned to newly-created user waypoints by default.
Why this exists:
Creation flows need one persisted source of truth instead of hardcoding Waypoint.DEFAULT_COLOR at every command and UI entrypoint.
When to use:
Use when creating new user-facing waypoints or showing the default waypoint color in settings. Do not use for imported routes, which have their own import color policy.
Inputs:
None.
Outputs:
Returns a 24-bit 0xRRGGBB color.
Side effects:
None.
Failure modes:
Malformed persisted alpha bits are masked away before returning.
Important invariants:
The returned color never includes alpha bits, and the default remains Waypoint.DEFAULT_COLOR.
Internal logic:
Mask the stored integer to 24 RGB bits and return it.
Pseudocode:
return defaultWaypointColor & 0xFFFFFF
Implementation notes:
This mirrors tracerColor, routeLineColor, and importedRouteDefaultColor normalization.
AI self-check:
Verify every user-created waypoint path reads this getter instead of Waypoint.DEFAULT_COLOR.
]]*/
    public int defaultWaypointColor()         { return defaultWaypointColor & 0xFFFFFF; }
    public int tracerColor()                  { return tracerColor; }
    public boolean matchTracerToWaypointColor() { return matchTracerToWaypointColor; }
    public double tracerOpacity()             { return tracerOpacity; }
    public double tracerThickness()           { return clamp(tracerThickness, 1.0, 12.0); }
    public double waypointOutlineThickness()  { return clamp(waypointOutlineThickness, 1.0, 12.0); }
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
    /*[[AI-FN-DOC
Function:
skipAheadOnlyVisibleWaypoints
Purpose:
Expose whether automatic skip-ahead should be capped to visible route-context waypoints.
Why this exists:
Progression code needs a stable getter so config validation and defaults stay centralized in WaypointerConfig.
When to use:
Use from proximity progression and settings UI. Do not use for manual skip commands, which intentionally bypass automatic visibility caps.
Inputs:
None.
Outputs:
Returns true when automatic future waypoint skips should require current visibility, false for legacy farthest-nearby behavior.
Side effects:
None.
Failure modes:
None; the field is a primitive boolean with a constructor default.
Important invariants:
The default value is true so users do not skip to invisible far-future route points by accident.
Internal logic:
Return the stored boolean field directly.
Pseudocode:
return skipAheadOnlyVisibleWaypoints
Implementation notes:
No null handling or clamping is needed.
AI self-check:
Verify ProximityTracker reads this getter from the tick path.
]]*/
    public boolean skipAheadOnlyVisibleWaypoints() { return skipAheadOnlyVisibleWaypoints; }
    /*[[AI-FN-DOC
Function:
showRouteLines
Purpose:
Expose whether route connector line rendering is enabled.
Why this exists:
The renderer and settings UI need a single persisted flag for the optional connector overlay.
When to use:
Use from world rendering and settings controls. Do not use for crosshair tracer visibility.
Inputs:
None.
Outputs:
Returns true when visible route connector segments should be drawn.
Side effects:
None.
Failure modes:
None; the field is a primitive boolean.
Important invariants:
The default remains false so existing users do not get extra route geometry without opting in.
Internal logic:
Return the stored boolean field directly.
Pseudocode:
return showRouteLines
Implementation notes:
Kept separate from showTracer so users can configure topology lines and navigation tracers independently.
AI self-check:
Verify WaypointRenderer gates connector drawing on this getter.
]]*/
    public boolean showRouteLines()           { return showRouteLines; }
    /*[[AI-FN-DOC
Function:
routeLineColor
Purpose:
Expose the 24-bit RGB color used for route connector lines.
Why this exists:
Connector rendering and settings UI need a normalized color value that ignores accidental alpha bits in persisted JSON.
When to use:
Use anywhere the connector line color is displayed or rendered. Do not use for tracer, waypoint, or import colors.
Inputs:
None.
Outputs:
Returns the stored color masked to 0xRRGGBB.
Side effects:
None.
Failure modes:
None; masking makes even malformed persisted integer values safe to render as RGB.
Important invariants:
The returned value must never include alpha bits.
Internal logic:
Return routeLineColor bitwise-and 0xFFFFFF.
Pseudocode:
return routeLineColor & 0xFFFFFF
Implementation notes:
Matches the normalization style of other color getters in this config class.
AI self-check:
Verify tests cover alpha masking for this getter.
]]*/
    public int routeLineColor()               { return routeLineColor & 0xFFFFFF; }
    public boolean showLabelBackdrop()        { return showLabelBackdrop; }
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
    public boolean checkForUpdates()            { return checkForUpdates; }
    public boolean irisShaderHudFallback()      { return irisShaderHudFallback; }
    public int tempDefaultMode() {
        return tempDefaultMode < Waypoint.TEMP_TIME || tempDefaultMode > Waypoint.TEMP_UNTIL_LEAVE
                ? Waypoint.TEMP_TIME
                : tempDefaultMode;
    }
    public int tempDefaultDurationMin()         { return Math.max(1, Math.min(24 * 60, tempDefaultDurationMin)); }
    public boolean tempWaypointsExpireByDefault() { return tempDefaultMode() == Waypoint.TEMP_TIME; }
    public long defaultTempExpiresAtMillis(long nowMillis) {
        return tempDefaultMode() == Waypoint.TEMP_TIME
                ? nowMillis + (long) tempDefaultDurationMin() * 60_000L
                : 0L;
    }

    public void setDefaultReachRadius(double v)        { this.defaultReachRadius = clamp(v, 0.5, 100); save(); }
    public void setResetProgressOnWorldJoin(boolean v) { this.resetProgressOnWorldJoin = v; save(); }
    public void setRestartRouteWhenComplete(boolean v) { this.restartRouteWhenComplete = v; save(); }
    /*[[AI-FN-DOC
Function:
setDefaultWaypointColor
Purpose:
Persist the RGB color used for newly-created user waypoints.
Why this exists:
The Colors tab lets users choose the color future waypoints start with, so config needs a validated setter matching the other color settings.
When to use:
Use from settings UI and config-code import when changing the future waypoint default. Do not use to recolor existing route waypoints.
Inputs:
v is an integer color; only the lower 24 RGB bits are kept.
Outputs:
No return value. Mutates config and schedules a save.
Side effects:
Calls save(), which may write config.json asynchronously in normal runtime.
Failure modes:
Out-of-range or alpha-bearing integers are normalized by masking. Save failures are handled by the saver path.
Important invariants:
Stored value is always 0xRRGGBB and does not imply any bulk route recolor.
Internal logic:
Mask v to 24 bits, assign it, then mark the config dirty.
Pseudocode:
defaultWaypointColor = v & 0xFFFFFF
save
Implementation notes:
The masking policy matches every other color setter in this class.
AI self-check:
Confirm tests cover alpha masking and future waypoint creation uses this getter.
]]*/
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
    /*[[AI-FN-DOC
Function:
setSkipAheadOnlyVisibleWaypoints
Purpose:
Persist whether automatic waypoint skip-ahead may target only currently visible route-context waypoints.
Why this exists:
The global skip-ahead mechanic is useful, but long contextual routes should not jump to invisible far-future points by default.
When to use:
Use from settings UI or tests when toggling the visibility-aware cap for proximity skip-ahead. Do not use for manual /wp skipto, which intentionally targets explicit indices.
Inputs:
v is the requested boolean state; true restricts automatic skips to visible route context, false preserves legacy farthest-nearby skip behavior.
Outputs:
No return value. Mutates the config field and schedules an async save.
Side effects:
Updates persisted config state and calls save(), which may write config.json after the debounce window.
Failure modes:
The save helper may be absent during tests or early construction; save() handles that by returning without throwing.
Important invariants:
The setting must default true and must not disable normal current-waypoint progression.
Internal logic:
Assign the boolean directly, then mark the config dirty.
Pseudocode:
set skipAheadOnlyVisibleWaypoints to v
call save
Implementation notes:
No clamping is needed for a boolean. This intentionally lives beside the progression settings so reset/disable can copy it explicitly.
AI self-check:
Verify the field is copied by resetToDefaults, disabled by disableAllSettings, and read by ProximityTracker.
]]*/
    public void setSkipAheadOnlyVisibleWaypoints(boolean v) {
        this.skipAheadOnlyVisibleWaypoints = v;
        save();
    }
    /*[[AI-FN-DOC
Function:
setShowRouteLines
Purpose:
Persist whether visible route connector segments should be rendered between waypoint centers.
Why this exists:
Players requested an optional line between waypoints without forcing that extra visual density on every route.
When to use:
Use from settings UI when toggling the connector overlay. Do not use to control tracer lines, which have their own setting.
Inputs:
v is the requested boolean state; true enables route connector rendering, false disables it.
Outputs:
No return value. Mutates the config field and schedules an async save.
Side effects:
Updates config state and calls save().
Failure modes:
The debounced saver may be null in tests; save() already handles that safely.
Important invariants:
Route connector visibility must be independent from waypoint boxes, beams, and crosshair tracers.
Internal logic:
Assign the new flag, then mark the config dirty.
Pseudocode:
set showRouteLines to v
call save
Implementation notes:
Keeping this separate from showTracer lets users keep crosshair navigation off while still seeing route topology.
AI self-check:
Confirm renderer gates connector drawing on this getter and reset/disable copy the field.
]]*/
    public void setShowRouteLines(boolean v) {
        this.showRouteLines = v;
        save();
    }
    /*[[AI-FN-DOC
Function:
setRouteLineColor
Purpose:
Persist the RGB color used for optional route connector segments.
Why this exists:
Connector lines need their own color so they can be readable without changing waypoint, tracer, or import colors.
When to use:
Use from settings UI or tests when the user picks a connector color. Do not pass ARGB alpha; only RGB is stored.
Inputs:
v is an integer color. Only the low 24 bits are used, so callers may pass standard 0xRRGGBB values.
Outputs:
No return value. Mutates the config field and schedules an async save.
Side effects:
Updates config state and calls save().
Failure modes:
Out-of-range integers are normalized by masking; save failures are logged by the saver path rather than thrown here.
Important invariants:
The stored value is always a 24-bit RGB color.
Internal logic:
Mask the color to 0xFFFFFF, assign it, and mark config dirty.
Pseudocode:
routeLineColor = v bitwise-and 0xFFFFFF
call save
Implementation notes:
Masking matches the existing tracer and import color setters.
AI self-check:
Confirm the settings swatch and renderer both read routeLineColor().
]]*/
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
    public void setSkipAheadMechanicEnabled(boolean v) { this.skipAheadMechanicEnabled = v; save(); }
    public void setCheckForUpdates(boolean v)          { this.checkForUpdates = v; save(); }
    public void setIrisShaderHudFallback(boolean v)    { this.irisShaderHudFallback = v; save(); }
    public void setTempDefaultMode(int v) {
        int clamped = (v < Waypoint.TEMP_TIME || v > Waypoint.TEMP_UNTIL_LEAVE)
                ? Waypoint.TEMP_TIME
                : v;
        this.tempDefaultMode = clamped;
        save();
    }
    public void setTempDefaultDurationMin(int v) {
        this.tempDefaultDurationMin = Math.max(1, Math.min(24 * 60, v));
        save();
    }
    public void setTempWaypointsExpireByDefault(boolean v) {
        setTempDefaultMode(v ? Waypoint.TEMP_TIME : Waypoint.TEMP_UNTIL_LEAVE);
    }

    /*[[AI-FN-DOC
Function:
replaceWith
Purpose:
Replace this live config object's persisted settings with another config snapshot.
Why this exists:
The compact config codec decodes into a fresh default-backed config, but runtime screens and managers hold references to the existing WaypointerConfig instance.
When to use:
Use when importing a config code or restoring a complete settings snapshot. Do not use for partial setting changes; call individual setters instead.
Inputs:
replacement is a WaypointerConfig snapshot. Null is ignored to avoid clobbering settings from a failed decode path.
Outputs:
No return value. Mutates this config to match replacement and schedules a save.
Side effects:
Updates every persisted field, replaces the chat sender blacklist list, clears migration state, and calls save().
Failure modes:
Null input returns without mutation. Save may be skipped in tests when no saver is attached.
Important invariants:
Transient file/saver references remain on this live object. Omitted fields in a decoded replacement remain replacement defaults.
Internal logic:
Guard null, copy scalar fields and defensive-copy lists, reset migration flag, then save once.
Pseudocode:
if replacement is null, return
copy every persisted primitive, enum, and list field from replacement
set migratedDuringLoad false
save
Implementation notes:
This intentionally assigns fields directly instead of calling dozens of setters so importing one config code performs one save and cannot observe a half-applied state.
AI self-check:
Verify newly-added config fields are present here and in resetToDefaults.
]]*/
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
        routeLineColor = replacement.routeLineColor;
        showLabelBackdrop = replacement.showLabelBackdrop;
        maxWaypointLabels = replacement.maxWaypointLabels;
        maxStaticWaypointRenderDistance = replacement.maxStaticWaypointRenderDistance;
        labelHeightOffset = replacement.labelHeightOffset;
        boxStyle = replacement.boxStyle;
        beaconBeamMode = replacement.beaconBeamMode;
        beaconBeamExtendsBelowWaypoint = replacement.beaconBeamExtendsBelowWaypoint;
        chatCoordDetection = replacement.chatCoordDetection;
        chatCoordSenderBlacklist = new ArrayList<>(replacement.chatCoordSenderBlacklist);
        autoAddChatTempWaypoints = replacement.autoAddChatTempWaypoints;
        placeNewWaypointsBelowPlayer = replacement.placeNewWaypointsBelowPlayer;
        focusTempWaypoints = replacement.focusTempWaypoints;
        chatCodecDetection = replacement.chatCodecDetection;
        importedRouteColorMode = replacement.importedRouteColorMode;
        importedRouteDefaultColor = replacement.importedRouteDefaultColor;
        exportIncludeNames = replacement.exportIncludeNames;
        exportIncludeColors = replacement.exportIncludeColors;
        exportIncludeRadii = replacement.exportIncludeRadii;
        exportIncludeWaypointFlags = replacement.exportIncludeWaypointFlags;
        exportIncludeGroupMeta = replacement.exportIncludeGroupMeta;
        dungeonWaypointsFeatureEnabled = replacement.dungeonWaypointsFeatureEnabled;
        skipAheadMechanicEnabled = replacement.skipAheadMechanicEnabled;
        checkForUpdates = replacement.checkForUpdates;
        irisShaderHudFallback = replacement.irisShaderHudFallback;
        tempDefaultMode = replacement.tempDefaultMode;
        tempDefaultDurationMin = replacement.tempDefaultDurationMin;
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
        showLabelBackdrop = false;
        beaconBeamExtendsBelowWaypoint = false;
        chatCoordDetection = false;
        autoAddChatTempWaypoints = false;
        placeNewWaypointsBelowPlayer = false;
        focusTempWaypoints = false;
        chatCodecDetection = false;
        importedRouteColorMode = WaypointGroup.GradientMode.MANUAL;
        exportIncludeNames = false;
        exportIncludeColors = false;
        exportIncludeRadii = false;
        exportIncludeWaypointFlags = false;
        exportIncludeGroupMeta = false;
        dungeonWaypointsFeatureEnabled = false;
        skipAheadMechanicEnabled = false;
        checkForUpdates = false;
        irisShaderHudFallback = false;
        beaconBeamMode = BeaconBeamMode.OFF;
        tempDefaultMode = Waypoint.TEMP_UNTIL_LEAVE;
        save();
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
        routeLineColor = defaults.routeLineColor;
        showLabelBackdrop = defaults.showLabelBackdrop;
        maxWaypointLabels = defaults.maxWaypointLabels;
        maxStaticWaypointRenderDistance = defaults.maxStaticWaypointRenderDistance;
        labelHeightOffset = defaults.labelHeightOffset;
        boxStyle = defaults.boxStyle;
        beaconBeamMode = defaults.beaconBeamMode;
        beaconBeamExtendsBelowWaypoint = defaults.beaconBeamExtendsBelowWaypoint;
        chatCoordDetection = defaults.chatCoordDetection;
        chatCoordSenderBlacklist = new ArrayList<>(defaults.chatCoordSenderBlacklist);
        autoAddChatTempWaypoints = defaults.autoAddChatTempWaypoints;
        placeNewWaypointsBelowPlayer = defaults.placeNewWaypointsBelowPlayer;
        focusTempWaypoints = defaults.focusTempWaypoints;
        chatCodecDetection = defaults.chatCodecDetection;
        importedRouteColorMode = defaults.importedRouteColorMode;
        importedRouteDefaultColor = defaults.importedRouteDefaultColor;
        exportIncludeNames = defaults.exportIncludeNames;
        exportIncludeColors = defaults.exportIncludeColors;
        exportIncludeRadii = defaults.exportIncludeRadii;
        exportIncludeWaypointFlags = defaults.exportIncludeWaypointFlags;
        exportIncludeGroupMeta = defaults.exportIncludeGroupMeta;
        dungeonWaypointsFeatureEnabled = defaults.dungeonWaypointsFeatureEnabled;
        skipAheadMechanicEnabled = defaults.skipAheadMechanicEnabled;
        checkForUpdates = defaults.checkForUpdates;
        irisShaderHudFallback = defaults.irisShaderHudFallback;
        tempDefaultMode = defaults.tempDefaultMode;
        tempDefaultDurationMin = defaults.tempDefaultDurationMin;
        migratedDuringLoad = false;
        save();
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
