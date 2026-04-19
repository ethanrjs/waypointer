package dev.ethan.waypointer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ethan.waypointer.Waypointer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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

    private static final String FILE_NAME = "config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Progression
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
    private double beaconOpacity = 0.8;
    private boolean showWaypointNames = true;
    private boolean showCompleted = true;
    private boolean showTracer = true;
    /**
     * When {@code true} (default), groups in {@link dev.ethan.waypointer.core.WaypointGroup.LoadMode#STATIC}
     * do not draw the crosshair tracer. Static routes already surface every waypoint, so the
     * line is often visual noise; {@link dev.ethan.waypointer.core.WaypointGroup.LoadMode#SEQUENCE}
     * groups still get a tracer to the active breadcrumb target.
     */
    private boolean hideTracerOnStaticRoutes = true;
    /**
     * When {@code true}, each waypoint label draws a translucent black rectangle
     * behind its text for readability. Some players find it obtrusive in busy
     * routes where labels stack -- turning it off lets the text sit directly
     * against the world, vanilla-nametag style.
     */
    private boolean showLabelBackdrop = true;
    private BoxStyle boxStyle = BoxStyle.OUTLINED;
    /**
     * When {@code true}, every group renders only a three-waypoint sliding
     * window ({@code currentIndex - 1}, {@code currentIndex}, {@code currentIndex + 1}),
     * regardless of the group's load mode. Reduces label clutter on dense
     * static routes where every checkpoint otherwise fights for screen space.
     *
     * <p>This is stricter than {@link dev.ethan.waypointer.core.WaypointGroup.LoadMode#SEQUENCE}
     * already does for sequence groups -- the flag extends the same behavior
     * to {@link dev.ethan.waypointer.core.WaypointGroup.LoadMode#STATIC}
     * groups without forcing the user to convert their route and lose the
     * "see the whole path at once" option.
     *
     * <p>Off by default so users don't silently lose visibility of their
     * existing static routes on upgrade.
     */
    private boolean windowedRendering = false;

    // Zone detection
    private boolean preferScoreboardFallback = false;

    // Quality-of-life
    private boolean chatCoordDetection = true;
    private boolean chatCodecDetection = true;
    /** Default exports drop names; set to {@code true} to include them at the cost of length. */
    private boolean exportIncludeNames = false;
    /**
     * Per-waypoint colors are tiny (3 bytes each) and visually meaningful, so the
     * default is {@code true}. Disabling forces every imported waypoint to the
     * default color, which is useful when sharing a route someone else will
     * recolor to match their own palette.
     */
    private boolean exportIncludeColors = true;
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
     * When {@code true} (default), adding a waypoint to a group flips that
     * group's {@code skipAheadEnabled} flag off so the player doesn't
     * immediately skip past the just-added point via proximity. Re-enable
     * per-group from the group editor once the route is complete.
     *
     * <p>The trade-off: a player who builds a full route then walks it will
     * likely want skip-ahead on, but they'll have to toggle it back on from
     * the group editor. Defaulting to on protects the much more common case
     * (player adds a new waypoint near the current one and is surprised when
     * the route teleports past it).
     */
    private boolean disableGroupSkipAheadOnWaypointAdd = true;

    /**
     * Gate for the GitHub update checker. Off means no outbound HTTP at all --
     * privacy-minded users can disable it without losing the rest of the mod.
     */
    private boolean checkForUpdates = true;

    /**
     * Default mode for the "Add Temp Waypoint Here" keybind, and the pre-selected
     * mode in the Add Temp modal. Values match
     * {@link dev.ethan.waypointer.core.Waypoint}'s tempMode encoding:
     * 1 = time-based, 2 = until reached, 3 = until server leave.
     */
    private int tempDefaultMode = 2;
    /** Default duration (minutes) for time-based temp waypoints. */
    private int tempDefaultDurationMin = 10;

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

    public static WaypointerConfig load() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(Waypointer.MOD_ID);
        Path file = dir.resolve(FILE_NAME);
        WaypointerConfig config;
        try {
            if (Files.exists(file)) {
                String raw = Files.readString(file);
                config = GSON.fromJson(raw, WaypointerConfig.class);
                if (config == null) config = new WaypointerConfig();
            } else {
                config = new WaypointerConfig();
            }
        } catch (Exception e) {
            Waypointer.LOGGER.error("Failed to read config, using defaults", e);
            config = new WaypointerConfig();
        }
        config.file = file;
        config.saver = new AsyncSaver("config", config::writeToDisk, SAVE_DEBOUNCE_MS);
        return config;
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
    public int tracerColor()                  { return tracerColor; }
    public boolean matchTracerToWaypointColor() { return matchTracerToWaypointColor; }
    public double tracerOpacity()             { return tracerOpacity; }
    public double beaconOpacity()             { return beaconOpacity; }
    public boolean showWaypointNames()        { return showWaypointNames; }
    public boolean showCompleted()            { return showCompleted; }
    public boolean showTracer()               { return showTracer; }
    public boolean hideTracerOnStaticRoutes() { return hideTracerOnStaticRoutes; }
    public boolean showLabelBackdrop()        { return showLabelBackdrop; }
    public BoxStyle boxStyle()                { return boxStyle == null ? BoxStyle.OUTLINED : boxStyle; }
    public boolean windowedRendering()        { return windowedRendering; }
    public boolean preferScoreboardFallback() { return preferScoreboardFallback; }
    public boolean chatCoordDetection()       { return chatCoordDetection; }
    public boolean chatCodecDetection()       { return chatCodecDetection; }
    public boolean exportIncludeNames()        { return exportIncludeNames; }
    public boolean exportIncludeColors()       { return exportIncludeColors; }
    public boolean exportIncludeRadii()        { return exportIncludeRadii; }
    public boolean exportIncludeWaypointFlags(){ return exportIncludeWaypointFlags; }
    public boolean exportIncludeGroupMeta()    { return exportIncludeGroupMeta; }
    public boolean skipAheadMechanicEnabled() { return skipAheadMechanicEnabled; }
    public boolean disableGroupSkipAheadOnWaypointAdd() { return disableGroupSkipAheadOnWaypointAdd; }
    public boolean checkForUpdates()            { return checkForUpdates; }
    public int tempDefaultMode()                { return tempDefaultMode; }
    public int tempDefaultDurationMin()         { return tempDefaultDurationMin; }

    public void setDefaultReachRadius(double v)        { this.defaultReachRadius = clamp(v, 0.5, 100); save(); }
    public void setResetProgressOnWorldJoin(boolean v) { this.resetProgressOnWorldJoin = v; save(); }
    public void setRestartRouteWhenComplete(boolean v) { this.restartRouteWhenComplete = v; save(); }
    public void setTracerColor(int v)                  { this.tracerColor = v & 0xFFFFFF; save(); }
    public void setMatchTracerToWaypointColor(boolean v) { this.matchTracerToWaypointColor = v; save(); }
    public void setTracerOpacity(double v)             { this.tracerOpacity = clamp(v, 0, 1); save(); }
    public void setBeaconOpacity(double v)             { this.beaconOpacity = clamp(v, 0, 1); save(); }
    public void setShowWaypointNames(boolean v)        { this.showWaypointNames = v; save(); }
    public void setShowCompleted(boolean v)            { this.showCompleted = v; save(); }
    public void setShowTracer(boolean v)               { this.showTracer = v; save(); }
    public void setHideTracerOnStaticRoutes(boolean v) { this.hideTracerOnStaticRoutes = v; save(); }
    public void setPreferScoreboardFallback(boolean v) { this.preferScoreboardFallback = v; save(); }
    public void setChatCoordDetection(boolean v)       { this.chatCoordDetection = v; save(); }
    public void setChatCodecDetection(boolean v)       { this.chatCodecDetection = v; save(); }
    public void setExportIncludeNames(boolean v)        { this.exportIncludeNames = v; save(); }
    public void setExportIncludeColors(boolean v)       { this.exportIncludeColors = v; save(); }
    public void setExportIncludeRadii(boolean v)        { this.exportIncludeRadii = v; save(); }
    public void setExportIncludeWaypointFlags(boolean v){ this.exportIncludeWaypointFlags = v; save(); }
    public void setExportIncludeGroupMeta(boolean v)    { this.exportIncludeGroupMeta = v; save(); }
    public void setShowLabelBackdrop(boolean v)        { this.showLabelBackdrop = v; save(); }
    public void setBoxStyle(BoxStyle v)                { this.boxStyle = v == null ? BoxStyle.OUTLINED : v; save(); }
    public void setWindowedRendering(boolean v)        { this.windowedRendering = v; save(); }
    public void setSkipAheadMechanicEnabled(boolean v) { this.skipAheadMechanicEnabled = v; save(); }
    public void setDisableGroupSkipAheadOnWaypointAdd(boolean v) { this.disableGroupSkipAheadOnWaypointAdd = v; save(); }
    public void setCheckForUpdates(boolean v)          { this.checkForUpdates = v; save(); }
    public void setTempDefaultMode(int v) {
        int clamped = (v < 1 || v > 3) ? 2 : v;
        this.tempDefaultMode = clamped;
        save();
    }
    public void setTempDefaultDurationMin(int v) {
        this.tempDefaultDurationMin = Math.max(1, Math.min(24 * 60, v));
        save();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
