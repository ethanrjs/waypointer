package com.babbur.waypointer.dungeon.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.config.AsyncSaver;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * User-tunable runtime settings for the dungeon-waypoints feature.
 *
 * <p>Lives in its own {@code dungeon.json} file rather than piggy-backing on
 * {@link com.babbur.waypointer.config.WaypointerConfig} for two reasons:
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

    /**
     * Remove a room's route group once every authored secret in it is found
     * (by the player, by trigger detection, or by the map's green checkmark).
     * Mirrors Devonian's "remove on done" -- a finished room shouldn't keep
     * shouting waypoints at the player.
     */
    private boolean hideCompletedRooms = true;

    /**
     * Treat the dungeon map's green checkmark as "all secrets found" for that
     * room. Authoritative even when a teammate collected the secrets, which
     * client-side trigger detection can never see.
     */
    private boolean autoCompleteRoomsOnGreenCheckmark = true;

    /** Dungeon routes use their own low-noise presentation defaults. */
    private boolean showDungeonRouteLines = true;
    private boolean showDungeonTracers = false;
    /** Number of current/future secret stages surfaced at once, SecretRoutes-style. */
    private int visibleSecretStages = 1;
    private boolean secretCompletionSound = true;
    private boolean showPearlTrajectories = true;

    /**
     * User clicked "don't ask again" on the first-join community-routes
     * download prompt. {@code /wpd routes download} keeps working regardless.
     */
    private boolean routesPromptDismissed = false;

    /** Definition-backed room routes explicitly hidden from the route list pill. */
    private List<String> hiddenRouteRoomIds = new ArrayList<>();

    private transient Path file;
    private transient AsyncSaver saver;
    private transient final List<Runnable> enabledListeners = new ArrayList<>();
    private transient final List<Runnable> changeListeners = new ArrayList<>();

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

    public void addEnabledListener(Runnable listener) {
        if (listener != null) enabledListeners.add(listener);
    }

    public void removeEnabledListener(Runnable listener) {
        enabledListeners.remove(listener);
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null) changeListeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private void fireChanged() {
        for (Runnable listener : List.copyOf(changeListeners)) listener.run();
    }

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
    public boolean hideCompletedRooms()   { return hideCompletedRooms; }
    public boolean autoCompleteRoomsOnGreenCheckmark() { return autoCompleteRoomsOnGreenCheckmark; }
    public boolean showDungeonRouteLines() { return showDungeonRouteLines; }
    public boolean showDungeonTracers() { return showDungeonTracers; }
    public int visibleSecretStages() { return Math.max(1, Math.min(5, visibleSecretStages)); }
    public boolean secretCompletionSound() { return secretCompletionSound; }
    public boolean showPearlTrajectories() { return showPearlTrajectories; }
    public boolean routesPromptDismissed()  { return routesPromptDismissed; }

    public boolean roomRouteEnabled(String roomId) {
        if (roomId == null || roomId.isBlank()) return true;
        return hiddenRouteRoomIds == null || !hiddenRouteRoomIds.contains(roomId);
    }

    public void setEnabled(boolean v) {
        if (enabled == v) return;
        enabled = v;
        save();
        for (Runnable listener : List.copyOf(enabledListeners)) listener.run();
        fireChanged();
    }

    public void disableAllSettings() {
        boolean notifyEnabledListeners = enabled;
        enabled = false;
        debugLogRoomChanges = false;
        hideCompletedRooms = false;
        autoCompleteRoomsOnGreenCheckmark = false;
        showDungeonRouteLines = false;
        showDungeonTracers = false;
        secretCompletionSound = false;
        showPearlTrajectories = false;
        save();
        if (notifyEnabledListeners) {
            for (Runnable listener : List.copyOf(enabledListeners)) listener.run();
        }
        fireChanged();
    }

    public void resetToDefaults() {
        DungeonConfig defaults = new DungeonConfig();
        boolean notifyEnabledListeners = enabled != defaults.enabled;
        enabled = defaults.enabled;
        debugLogRoomChanges = defaults.debugLogRoomChanges;
        defaultDirection = defaults.defaultDirection;
        hideCompletedRooms = defaults.hideCompletedRooms;
        autoCompleteRoomsOnGreenCheckmark = defaults.autoCompleteRoomsOnGreenCheckmark;
        showDungeonRouteLines = defaults.showDungeonRouteLines;
        showDungeonTracers = defaults.showDungeonTracers;
        visibleSecretStages = defaults.visibleSecretStages;
        secretCompletionSound = defaults.secretCompletionSound;
        showPearlTrajectories = defaults.showPearlTrajectories;
        routesPromptDismissed = defaults.routesPromptDismissed;
        hiddenRouteRoomIds = new ArrayList<>();
        save();
        if (notifyEnabledListeners) {
            for (Runnable listener : List.copyOf(enabledListeners)) listener.run();
        }
        fireChanged();
    }

    public void setDebugLogRoomChanges(boolean v) { this.debugLogRoomChanges = v; save(); }
    public void setHideCompletedRooms(boolean v)  { this.hideCompletedRooms = v; save(); }
    public void setAutoCompleteRoomsOnGreenCheckmark(boolean v) {
        this.autoCompleteRoomsOnGreenCheckmark = v;
        save();
    }
    public void setShowDungeonRouteLines(boolean v) { showDungeonRouteLines = v; save(); }
    public void setShowDungeonTracers(boolean v) { showDungeonTracers = v; save(); }
    public void setVisibleSecretStages(int v) {
        int clamped = Math.max(1, Math.min(5, v));
        if (visibleSecretStages == clamped) return;
        visibleSecretStages = clamped;
        save();
        fireChanged();
    }
    public void setSecretCompletionSound(boolean v) { secretCompletionSound = v; save(); }
    public void setShowPearlTrajectories(boolean v) { showPearlTrajectories = v; save(); }
    public void setRoutesPromptDismissed(boolean v) { this.routesPromptDismissed = v; save(); }
    public void setRoomRouteEnabled(String roomId, boolean enabled) {
        if (roomId == null || roomId.isBlank()) return;
        if (hiddenRouteRoomIds == null) hiddenRouteRoomIds = new ArrayList<>();
        boolean changed = enabled
                ? hiddenRouteRoomIds.remove(roomId)
                : !hiddenRouteRoomIds.contains(roomId) && hiddenRouteRoomIds.add(roomId);
        if (changed) save();
    }

    public void disableRoomRoutes(Collection<String> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) return;
        if (hiddenRouteRoomIds == null) hiddenRouteRoomIds = new ArrayList<>();
        boolean changed = false;
        for (String roomId : roomIds) {
            if (roomId != null && !roomId.isBlank() && !hiddenRouteRoomIds.contains(roomId)) {
                hiddenRouteRoomIds.add(roomId);
                changed = true;
            }
        }
        if (changed) save();
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
