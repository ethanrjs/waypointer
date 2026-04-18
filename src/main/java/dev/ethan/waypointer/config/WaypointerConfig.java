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
    // a fresh install shows one consistent color scheme across boxes and lines.
    private int tracerColor = 0x4FE05A;
    private double tracerOpacity = 0.95;
    private double beaconOpacity = 0.8;
    private boolean showWaypointNames = true;
    private boolean showCompleted = true;
    private boolean showTracer = true;

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
     * Gates the skip-waypoint keybind. Default {@code true} because it's only wired
     * to a key the user has to press deliberately, but exposing the toggle means a
     * player who accidentally bound skip to a common key can defuse it from the
     * settings screen without unbinding in the vanilla controls menu.
     */
    private boolean skipWaypointKeybindEnabled = true;

    // Transient; never persisted.
    private transient Path file;
    private transient Runnable onSave;

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
        return config;
    }

    public void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Waypointer.LOGGER.error("Failed to write config", e);
        }
        if (onSave != null) onSave.run();
    }

    public void onSave(Runnable cb) { this.onSave = cb; }

    // --- getters/setters ---------------------------------------------------------------------

    public double defaultReachRadius()        { return defaultReachRadius; }
    public boolean resetProgressOnWorldJoin() { return resetProgressOnWorldJoin; }
    public boolean restartRouteWhenComplete() { return restartRouteWhenComplete; }
    public int tracerColor()                  { return tracerColor; }
    public double tracerOpacity()             { return tracerOpacity; }
    public double beaconOpacity()             { return beaconOpacity; }
    public boolean showWaypointNames()        { return showWaypointNames; }
    public boolean showCompleted()            { return showCompleted; }
    public boolean showTracer()               { return showTracer; }
    public boolean preferScoreboardFallback() { return preferScoreboardFallback; }
    public boolean chatCoordDetection()       { return chatCoordDetection; }
    public boolean chatCodecDetection()       { return chatCodecDetection; }
    public boolean exportIncludeNames()        { return exportIncludeNames; }
    public boolean exportIncludeColors()       { return exportIncludeColors; }
    public boolean exportIncludeRadii()        { return exportIncludeRadii; }
    public boolean exportIncludeWaypointFlags(){ return exportIncludeWaypointFlags; }
    public boolean exportIncludeGroupMeta()    { return exportIncludeGroupMeta; }
    public boolean skipWaypointKeybindEnabled() { return skipWaypointKeybindEnabled; }

    public void setDefaultReachRadius(double v)        { this.defaultReachRadius = clamp(v, 0.5, 100); save(); }
    public void setResetProgressOnWorldJoin(boolean v) { this.resetProgressOnWorldJoin = v; save(); }
    public void setRestartRouteWhenComplete(boolean v) { this.restartRouteWhenComplete = v; save(); }
    public void setTracerColor(int v)                  { this.tracerColor = v & 0xFFFFFF; save(); }
    public void setTracerOpacity(double v)             { this.tracerOpacity = clamp(v, 0, 1); save(); }
    public void setBeaconOpacity(double v)             { this.beaconOpacity = clamp(v, 0, 1); save(); }
    public void setShowWaypointNames(boolean v)        { this.showWaypointNames = v; save(); }
    public void setShowCompleted(boolean v)            { this.showCompleted = v; save(); }
    public void setShowTracer(boolean v)               { this.showTracer = v; save(); }
    public void setPreferScoreboardFallback(boolean v) { this.preferScoreboardFallback = v; save(); }
    public void setChatCoordDetection(boolean v)       { this.chatCoordDetection = v; save(); }
    public void setChatCodecDetection(boolean v)       { this.chatCodecDetection = v; save(); }
    public void setExportIncludeNames(boolean v)        { this.exportIncludeNames = v; save(); }
    public void setExportIncludeColors(boolean v)       { this.exportIncludeColors = v; save(); }
    public void setExportIncludeRadii(boolean v)        { this.exportIncludeRadii = v; save(); }
    public void setExportIncludeWaypointFlags(boolean v){ this.exportIncludeWaypointFlags = v; save(); }
    public void setExportIncludeGroupMeta(boolean v)    { this.exportIncludeGroupMeta = v; save(); }
    public void setSkipWaypointKeybindEnabled(boolean v) { this.skipWaypointKeybindEnabled = v; save(); }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
