package dev.ethan.waypointer.dungeon.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.config.AsyncSaver;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * User-tunable runtime settings for the dungeon-waypoints feature.
 *
 * <p>Lives in its own {@code dungeon.json} file rather than piggy-backing on
 * {@link dev.ethan.waypointer.config.WaypointerConfig} for two reasons:
 *
 * <ol>
 *   <li>The dungeons feature lands as a self-contained subsystem -- breaking
 *       it out keeps the merge surface with sibling work on the main config
 *       narrow (zero overlap, just a new file).</li>
 *   <li>Dungeon settings have a different cadence: they're seldom touched
 *       once configured, so co-locating with the much-busier main config's
 *       schema would dilute that file's churn signal in commit history.</li>
 * </ol>
 *
 * <p>Mirrors the persistence pattern of {@code WaypointerConfig}: hand-written
 * Gson, debounced async saves through {@link AsyncSaver}.
 */
public final class DungeonConfig {

    private static final String FILE_NAME = "dungeon.json";
    private static final long SAVE_DEBOUNCE_MS = 500L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Master switch. Off skips every dungeon tick + render path entirely. */
    private boolean enabled = true;

    /** When {@code true}, render the parent {@link dev.ethan.waypointer.dungeon.DungeonWaypoint} cube + label. */
    private boolean showSecretWaypoints = true;

    /**
     * When {@code true}, render the children {@link dev.ethan.waypointer.dungeon.DungeonHighlight}
     * cubes attached to each waypoint. Letting players turn just the
     * highlights off is useful for room-knowledge speedrunners who want the
     * "where to go" hint but find the per-block decorations visually noisy.
     */
    private boolean showHighlights = true;

    /**
     * When {@code true}, log a one-line message to the in-game chat each time
     * the detected current room changes. Off by default so it doesn't spam
     * normal play -- but invaluable when diagnosing why a route isn't
     * lighting up the way the user expects.
     */
    private boolean debugLogRoomChanges = false;

    /**
     * When {@code true}, render an outline around the entire current room's
     * footprint. Cheap visual confirmation that room detection is working.
     */
    private boolean drawRoomBounds = false;

    /** When {@code false}, found secrets are hidden instead of rendered dimly. */
    private boolean showFoundSecrets = true;

    /**
     * Route rendering mode: ALL shows every secret in the matched room, ACTIVE
     * shows only the current target plus its highlights.
     */
    private String routeRenderMode = "ALL";

    /**
     * Default direction to assume for newly-detected rooms. Block-fingerprint
     * matching is not implemented yet (see issue #9 follow-ups), so the
     * direction is unknown at detection time -- defaulting to NW gets the
     * common case (rooms whose canonical frame already matches their world
     * placement) right. Players in other rotations can rotate the guess via
     * the {@code /waypointer dungeon rotate} client command.
     */
    private String defaultDirection = "NW";

    private transient Path file;
    private transient AsyncSaver saver;

    public static DungeonConfig load() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(Waypointer.MOD_ID);
        Path file = dir.resolve(FILE_NAME);
        DungeonConfig cfg;
        try {
            if (Files.exists(file)) {
                String raw = Files.readString(file);
                cfg = GSON.fromJson(raw, DungeonConfig.class);
                if (cfg == null) cfg = new DungeonConfig();
            } else {
                cfg = new DungeonConfig();
            }
        } catch (Exception e) {
            Waypointer.LOGGER.error("Failed to read dungeon config, using defaults", e);
            cfg = new DungeonConfig();
        }
        cfg.file = file;
        cfg.saver = new AsyncSaver("dungeon-config", cfg::writeToDisk, SAVE_DEBOUNCE_MS);
        return cfg;
    }

    public void save() { if (saver != null) saver.markDirty(); }

    public void flush() { if (saver != null) saver.flush(); }

    private void writeToDisk() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Waypointer.LOGGER.error("Failed to write dungeon config", e);
        }
    }

    // ---- accessors -----------------------------------------------------

    public boolean enabled()              { return enabled; }
    public boolean showSecretWaypoints()  { return showSecretWaypoints; }
    public boolean showHighlights()       { return showHighlights; }
    public boolean debugLogRoomChanges()  { return debugLogRoomChanges; }
    public boolean drawRoomBounds()       { return drawRoomBounds; }
    public boolean showFoundSecrets()     { return showFoundSecrets; }
    public String  routeRenderMode()      { return routeRenderMode == null ? "ALL" : routeRenderMode; }
    public String  defaultDirection()     { return defaultDirection == null ? "NW" : defaultDirection; }

    public void setEnabled(boolean v)             { this.enabled = v; save(); }
    public void setShowSecretWaypoints(boolean v) { this.showSecretWaypoints = v; save(); }
    public void setShowHighlights(boolean v)      { this.showHighlights = v; save(); }
    public void setDebugLogRoomChanges(boolean v) { this.debugLogRoomChanges = v; save(); }
    public void setDrawRoomBounds(boolean v)      { this.drawRoomBounds = v; save(); }
    public void setShowFoundSecrets(boolean v)    { this.showFoundSecrets = v; save(); }
    public void setRouteRenderMode(String v) {
        if (v == null) return;
        String upper = v.trim().toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("ALL") || upper.equals("ACTIVE")) {
            this.routeRenderMode = upper;
            save();
        }
    }
    public void setDefaultDirection(String v) {
        if (v == null) return;
        String upper = v.trim().toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("NW") || upper.equals("NE") || upper.equals("SW") || upper.equals("SE")) {
            this.defaultDirection = upper;
            save();
        }
    }
}
