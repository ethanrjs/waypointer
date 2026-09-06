package com.babbur.waypointer.dungeon.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.config.AsyncSaver;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** User-tunable dungeon-waypoint settings stored in {@code dungeon.json}. */
public final class DungeonConfig {

    private static final String FILE_NAME = "dungeon.json";
    private static final long SAVE_DEBOUNCE_MS = 500L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Master switch. Off skips every dungeon tick + render path entirely. */
    private boolean enabled = true;

    /** Logs a message whenever the detected room changes. */
    private boolean debugLogRoomChanges = false;

    /**
     * Fallback direction for room definitions whose fingerprints do not encode
     * rotation. Players can adjust it with the dungeon command.
     */
    private String defaultDirection = "NW";

    private boolean hideCompletedRooms = true;

    /** Dungeon routes use their own low-noise presentation defaults. */
    private boolean showDungeonRouteLines = true;
    private boolean showDungeonTracers = false;
    /** Number of current and upcoming secret stages shown at once. */
    private int visibleSecretStages = 1;
    private boolean secretCompletionSound = true;

    private transient Path file;
    private transient AsyncSaver saver;
    private transient final List<Runnable> enabledListeners = new ArrayList<>();
    private transient final List<Runnable> changeListeners = new ArrayList<>();
    private transient IOException writeBlockCause;

    public static DungeonConfig load() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(Waypointer.MOD_ID);
        return load(dir.resolve(FILE_NAME));
    }

    static DungeonConfig load(Path file) {
        DungeonConfig cfg;
        IOException writeBlockCause = null;
        try {
            if (Files.exists(file)) {
                String raw = Files.readString(file);
                if (raw.isBlank()) {
                    throw new IllegalArgumentException("dungeon config JSON is empty");
                }
                cfg = GSON.fromJson(raw, DungeonConfig.class);
                if (cfg == null) {
                    throw new IllegalArgumentException("dungeon config JSON is null");
                }
            } else {
                cfg = new DungeonConfig();
            }
        } catch (Exception e) {
            Waypointer.LOGGER.error("Failed to read dungeon config, using defaults", e);
            cfg = new DungeonConfig();
            writeBlockCause = quarantineInvalidFile(file, e);
        }
        cfg.file = file;
        cfg.writeBlockCause = writeBlockCause;
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
        if (writeBlockCause != null) {
            IOException retryFailure = quarantineInvalidFile(file, writeBlockCause);
            if (retryFailure != null) {
                writeBlockCause = retryFailure;
                throw new UncheckedIOException(
                        "Cannot save dungeon config until the invalid file is preserved: " + file,
                        retryFailure);
            }
            writeBlockCause = null;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write dungeon config to " + file, e);
        }
    }

    private static IOException quarantineInvalidFile(Path file, Exception cause) {
        if (file == null || Files.notExists(file)) return null;
        Path quarantine = file.resolveSibling(file.getFileName() + ".invalid");
        int suffix = 1;
        while (Files.exists(quarantine)) {
            quarantine = file.resolveSibling(file.getFileName() + ".invalid." + suffix++);
        }
        try {
            Files.move(file, quarantine);
            Waypointer.LOGGER.error("Invalid dungeon config moved from {} to {}", file, quarantine, cause);
            return null;
        } catch (IOException quarantineFailure) {
            if (Files.notExists(file)) return null;
            Waypointer.LOGGER.error(
                    "Invalid dungeon config at {} could not be preserved; saves are blocked to prevent data loss",
                    file, quarantineFailure);
            return quarantineFailure;
        }
    }

    // ---- accessors -----------------------------------------------------

    public boolean enabled()              { return enabled; }
    public boolean debugLogRoomChanges()  { return debugLogRoomChanges; }
    public String  defaultDirection()     { return defaultDirection == null ? "NW" : defaultDirection; }
    public boolean hideCompletedRooms()   { return hideCompletedRooms; }
    public boolean showDungeonRouteLines() { return showDungeonRouteLines; }
    public boolean showDungeonTracers() { return showDungeonTracers; }
    public int visibleSecretStages() { return Math.max(1, Math.min(5, visibleSecretStages)); }
    public boolean secretCompletionSound() { return secretCompletionSound; }

    public void setEnabled(boolean v) {
        if (enabled == v) return;
        enabled = v;
        save();
        for (Runnable listener : List.copyOf(enabledListeners)) listener.run();
        fireChanged();
    }

    public void disableAllSettings() {
        boolean notifyEnabledListeners = enabled;
        boolean changed = enabled
                || debugLogRoomChanges
                || hideCompletedRooms
                || showDungeonRouteLines
                || showDungeonTracers
                || secretCompletionSound;
        if (!changed) return;
        enabled = false;
        debugLogRoomChanges = false;
        hideCompletedRooms = false;
        showDungeonRouteLines = false;
        showDungeonTracers = false;
        secretCompletionSound = false;
        save();
        if (notifyEnabledListeners) {
            for (Runnable listener : List.copyOf(enabledListeners)) listener.run();
        }
        fireChanged();
    }

    public void resetToDefaults() {
        DungeonConfig defaults = new DungeonConfig();
        boolean notifyEnabledListeners = enabled != defaults.enabled;
        boolean changed = notifyEnabledListeners
                || debugLogRoomChanges != defaults.debugLogRoomChanges
                || !java.util.Objects.equals(defaultDirection, defaults.defaultDirection)
                || hideCompletedRooms != defaults.hideCompletedRooms
                || showDungeonRouteLines != defaults.showDungeonRouteLines
                || showDungeonTracers != defaults.showDungeonTracers
                || visibleSecretStages != defaults.visibleSecretStages
                || secretCompletionSound != defaults.secretCompletionSound;
        if (!changed) return;
        enabled = defaults.enabled;
        debugLogRoomChanges = defaults.debugLogRoomChanges;
        defaultDirection = defaults.defaultDirection;
        hideCompletedRooms = defaults.hideCompletedRooms;
        showDungeonRouteLines = defaults.showDungeonRouteLines;
        showDungeonTracers = defaults.showDungeonTracers;
        visibleSecretStages = defaults.visibleSecretStages;
        secretCompletionSound = defaults.secretCompletionSound;
        save();
        if (notifyEnabledListeners) {
            for (Runnable listener : List.copyOf(enabledListeners)) listener.run();
        }
        fireChanged();
    }

    public void setDebugLogRoomChanges(boolean v) {
        if (debugLogRoomChanges == v) return;
        debugLogRoomChanges = v;
        save();
        fireChanged();
    }
    public void setHideCompletedRooms(boolean v) {
        if (hideCompletedRooms == v) return;
        hideCompletedRooms = v;
        save();
        fireChanged();
    }
    public void setShowDungeonRouteLines(boolean v) {
        if (showDungeonRouteLines == v) return;
        showDungeonRouteLines = v;
        save();
        fireChanged();
    }
    public void setShowDungeonTracers(boolean v) {
        if (showDungeonTracers == v) return;
        showDungeonTracers = v;
        save();
        fireChanged();
    }
    public void setVisibleSecretStages(int v) {
        int clamped = Math.max(1, Math.min(5, v));
        if (visibleSecretStages == clamped) return;
        visibleSecretStages = clamped;
        save();
        fireChanged();
    }
    public void setSecretCompletionSound(boolean v) {
        if (secretCompletionSound == v) return;
        secretCompletionSound = v;
        save();
        fireChanged();
    }
    public void setDefaultDirection(String v) {
        if (v == null) return;
        String upper = v.trim().toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("NW") || upper.equals("NE") || upper.equals("SW") || upper.equals("SE")) {
            if (upper.equals(defaultDirection)) return;
            defaultDirection = upper;
            save();
            fireChanged();
        }
    }
}
