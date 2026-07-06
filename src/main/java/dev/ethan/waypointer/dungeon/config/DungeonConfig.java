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

    /**
     * When {@code true}, log a one-line message to the in-game chat each time
     * the detected current room changes. Off by default so it doesn't spam
     * normal play -- but invaluable when diagnosing why a route isn't
     * lighting up the way the user expects.
     */
    private boolean debugLogRoomChanges = false;

    /**
     * Default direction to assume for newly-detected rooms. Room definitions
     * can identify a room by block fingerprints, but those fingerprints do not
     * infer the room's rotation; defaulting to NW gets the common case (rooms
     * whose canonical frame already matches their world placement) right.
     * Players in other rotations can rotate the guess via the dungeon command.
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
    public boolean debugLogRoomChanges()  { return debugLogRoomChanges; }
    public String  defaultDirection()     { return defaultDirection == null ? "NW" : defaultDirection; }

    public void setEnabled(boolean v)             { this.enabled = v; save(); }
    public void setDebugLogRoomChanges(boolean v) { this.debugLogRoomChanges = v; save(); }
    public void setDefaultDirection(String v) {
        if (v == null) return;
        String upper = v.trim().toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("NW") || upper.equals("NE") || upper.equals("SW") || upper.equals("SE")) {
            this.defaultDirection = upper;
            save();
        }
    }
}
