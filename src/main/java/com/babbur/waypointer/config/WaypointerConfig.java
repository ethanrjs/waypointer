package com.babbur.waypointer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.core.SequenceVisibility;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static com.babbur.waypointer.util.MathUtil.clamp;

public final class WaypointerConfig {

    public enum BoxStyle { OUTLINED, FILLED, FILLED_OUTLINED, PAINT }

    public enum BeaconBeamMode { OFF, CURRENT, ALL_VISIBLE }

    public enum EtherwarpAlignmentSound { OFF, EXPERIENCE, PLING, BELL }

    private static final String FILE_NAME = "config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CONFIG_SCHEMA_VERSION = 7;
    private static final int TEMP_DURATION_MIN_SECONDS = 1;
    private static final int TEMP_DURATION_MAX_SECONDS = 24 * 60 * 60;
    private static final int SECONDS_PER_MINUTE = 60;

    private int configSchemaVersion = CONFIG_SCHEMA_VERSION;
    private double defaultReachRadius = Waypoint.DEFAULT_REACH_RADIUS;
    private boolean resetProgressOnWorldJoin = true;
    private boolean restartRouteWhenComplete = true;
    private boolean showRouteIndicesInGui = false;
    private boolean keepSubwaypointsVisibleUntilNextWaypoint = true;
    private boolean allowBackwardProgress = false;

    private int defaultWaypointColor = Waypoint.DEFAULT_COLOR;
    private int[] waypointPainterPalette;
    private int[] waypointPainterDefaultPalette;
    private String waypointPainterDefaultPixels;
    private transient WaypointPaint waypointPainterDefaultPaintCache;
    private int tracerColor = 0x4FE05A;
    private boolean matchTracerToWaypointColor = true;
    private double tracerOpacity = 0.95;
    private double tracerThickness = 3.0;
    private double waypointOutlineThickness = 5.0;
    private double waypointMarkerScale = 1.0;
    private double waypointOutlineOpacity = 1.0;
    private boolean matchWaypointOutlineToWaypointColor = true;
    private int waypointOutlineColor = Waypoint.DEFAULT_COLOR;
    private double beaconOpacity = 0.33;
    private boolean showWaypointNames = true;
    private boolean showWaypointDistances = true;
    private boolean showRouteProgress = false;
    private double labelScale = 1.0;
    private boolean scaleWaypointTextWithDistance = false;
    private boolean matchWaypointTextToWaypointColor = true;
    private int sequencePreviousWaypointCount = SequenceVisibility.DEFAULT.previous();
    private boolean showCurrentSequenceWaypoint = SequenceVisibility.DEFAULT.current();
    private int sequenceNextWaypointCount = SequenceVisibility.DEFAULT.next();
    private boolean showTracer = true;
    private boolean dimSequenceContextWaypoints = true;
    private boolean hideTracerOnStaticRoutes = true;
    private boolean hideWaypointsNearPlayer = false;
    private double hideWaypointsNearRadius = 5.0;
    private boolean hideWaypointLabelsNearPlayer = false;
    private double hideWaypointLabelsNearRadius = 5.0;
    private boolean hideReachedStaticWaypointsUntilCycleComplete = false;
    private boolean skipAheadOnlyVisibleWaypoints = true;
    private boolean showRouteLines = false;
    private boolean useEtherwarpHeight = false;
    private boolean showDungeonEntryPathToFirstWaypoint = false;
    private boolean showDungeonEntryPathToFollowingWaypoints = false;
    private int dungeonEntryPathColor = 0x00FF00;
    @SerializedName("etherwarpAlignmentSoundType")
    private EtherwarpAlignmentSound etherwarpAlignmentSound = EtherwarpAlignmentSound.OFF;
    private int routeLineColor = 0x00FF00;
    private boolean showLabelBackdrop = true;
    private boolean showLabelTextShadow = true;
    private int maxWaypointLabels = 32;
    private double maxStaticWaypointRenderDistance = 0.0;
    private double labelHeightOffset = 0.0;
    private BoxStyle boxStyle = BoxStyle.FILLED_OUTLINED;
    private BeaconBeamMode beaconBeamMode = BeaconBeamMode.OFF;
    private boolean beaconBeamExtendsBelowWaypoint = false;
    private boolean useBeaconBeamTextures = true;
    private boolean editSounds = true;
    private boolean showEditModeSubtitle = true;

    private boolean chatCoordDetection = true;
    private List<String> chatCoordSenderBlacklist = new ArrayList<>();
    private boolean autoAddChatTempWaypoints = false;
    private boolean placeNewWaypointsBelowPlayer = true;
    private boolean focusTempWaypoints = false;
    private boolean showWaypointChatShareButtons = true;
    private boolean chatCodecDetection = true;
    private boolean showContributorBadges = true;
    private WaypointGroup.GradientMode importedRouteColorMode = WaypointGroup.GradientMode.STATIC;
    private int importedRouteDefaultColor = 0x00FF00;
    private boolean exportIncludeNames = false;
    private boolean exportIncludeColors = false;
    private boolean exportIncludeRadii = false;
    private boolean exportIncludeWaypointFlags = false;
    private boolean exportIncludeGroupMeta = false;
    private boolean exportIncludeZone = false;
    private boolean showExportRoutePreview = false;
    private boolean dungeonWaypointsFeatureEnabled = false;
    private boolean skipAheadMechanicEnabled = true;

    private boolean crystalHollowsEnabled = true;
    private boolean crystalHollowsStructureWaypoints = true;
    private boolean crystalHollowsShowRoughMarkers = true;
    private boolean crystalHollowsEntityDetection = true;
    private boolean crystalHollowsChatDetection = true;
    private boolean crystalHollowsWishingCompassSolver = true;
    private boolean crystalHollowsCompassRays = true;
    private boolean crystalHollowsAnnounceDetections = true;
    private boolean crystalHollowsNucleusWaypoints = false;

    private boolean irisShaderHudFallback = true;
    private int tempDefaultMode = Waypoint.TEMP_TIME;
    private int tempDefaultDurationSec = SECONDS_PER_MINUTE;

    private static final long SAVE_DEBOUNCE_MS = 500L;

    private transient Path file;
    private transient AsyncSaver saver;
    private transient boolean migratedDuringLoad;
    private transient volatile String pendingSnapshotJson;
    private transient IOException writeBlockCause;

    /**
     * Builds the default state used by WPC versions 1-4. Those versions were
     * written when an omitted export toggle meant {@code true}; setting the
     * values directly keeps config-code decoding detached from persistence.
     */
    static WaypointerConfig legacyConfigCodeDefaults() {
        WaypointerConfig config = new WaypointerConfig();
        config.exportIncludeNames = true;
        config.exportIncludeColors = true;
        config.exportIncludeRadii = true;
        config.exportIncludeWaypointFlags = true;
        config.exportIncludeGroupMeta = true;
        config.exportIncludeZone = true;
        return config;
    }

    public static WaypointerConfig load() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(Waypointer.MOD_ID);
        return load(dir.resolve(FILE_NAME));
    }

    static WaypointerConfig load(Path file) {
        WaypointerConfig config;
        IOException writeBlockCause = null;
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
            writeBlockCause = quarantineInvalidFile(file, e);
        }
        config.file = file;
        config.writeBlockCause = writeBlockCause;
        config.saver = new AsyncSaver("config", config::writeToDisk, SAVE_DEBOUNCE_MS);
        if (config.migratedDuringLoad) config.save();
        return config;
    }
    static WaypointerConfig fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("config JSON is empty");
        }
        WaypointerConfig config = GSON.fromJson(raw, WaypointerConfig.class);
        if (config == null) {
            throw new IllegalArgumentException("config JSON is null");
        }
        int loadedSchemaVersion = schemaVersion(raw);
        config.migrateLegacyTempDurationMinutes(raw, loadedSchemaVersion);
        config.migrateSequenceVisibility(raw, loadedSchemaVersion);
        config.migrateEtherwarpAlignmentSound(raw, loadedSchemaVersion);
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
        if (schemaVersion < 5) {
            irisShaderHudFallback = true;
            migratedDuringLoad = true;
        }
        configSchemaVersion = CONFIG_SCHEMA_VERSION;
    }
    private void migrateSequenceVisibility(String raw, int schemaVersion) {
        if (schemaVersion >= 6) {
            sequencePreviousWaypointCount = sequenceVisibility().previous();
            sequenceNextWaypointCount = sequenceVisibility().next();
            return;
        }
        boolean legacyShowCompleted = true;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();
                if (root.has("showCompleted")) {
                    legacyShowCompleted = root.get("showCompleted").getAsBoolean();
                }
            }
        } catch (Exception ignored) {
            legacyShowCompleted = true;
        }
        sequencePreviousWaypointCount = legacyShowCompleted
                ? SequenceVisibility.DEFAULT.previous() : 0;
        showCurrentSequenceWaypoint = true;
        sequenceNextWaypointCount = SequenceVisibility.DEFAULT.next();
        migratedDuringLoad = true;
    }

    private void migrateEtherwarpAlignmentSound(String raw, int schemaVersion) {
        EtherwarpAlignmentSound previous = etherwarpAlignmentSound;
        if (schemaVersion < 7) {
            boolean legacyEnabled = false;
            try {
                JsonElement parsed = JsonParser.parseString(raw);
                if (parsed != null && parsed.isJsonObject()) {
                    JsonObject root = parsed.getAsJsonObject();
                    if (root.has("etherwarpAlignmentSound")) {
                        legacyEnabled = root.get("etherwarpAlignmentSound").getAsBoolean();
                    }
                }
            } catch (Exception ignored) {
                legacyEnabled = false;
            }
            etherwarpAlignmentSound = legacyEnabled
                    ? EtherwarpAlignmentSound.EXPERIENCE : EtherwarpAlignmentSound.OFF;
        }
        if (etherwarpAlignmentSound == null) {
            etherwarpAlignmentSound = EtherwarpAlignmentSound.OFF;
        }
        if (etherwarpAlignmentSound != previous) migratedDuringLoad = true;
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

    public void save() {
        if (saver == null) return;
        pendingSnapshotJson = GSON.toJson(this);
        saver.markDirty();
    }

    public void flush() {
        if (saver != null) saver.flush();
    }

    private void writeToDisk() {
        if (file == null) return;
        if (writeBlockCause != null) {
            IOException retryFailure = quarantineInvalidFile(file, writeBlockCause);
            if (retryFailure != null) {
                writeBlockCause = retryFailure;
                throw new UncheckedIOException(
                        "Cannot save config until the invalid file is preserved: " + file,
                        retryFailure);
            }
            writeBlockCause = null;
        }
        String json = pendingSnapshotJson;
        if (json == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write config to " + file, e);
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
            Waypointer.LOGGER.error("Invalid config moved from {} to {}", file, quarantine, cause);
            return null;
        } catch (IOException quarantineFailure) {
            if (Files.notExists(file)) return null;
            quarantineFailure.addSuppressed(cause);
            Waypointer.LOGGER.error(
                    "Invalid config at {} could not be preserved; saves are blocked to prevent data loss",
                    file, quarantineFailure);
            return quarantineFailure;
        }
    }


    public int configSchemaVersion()               { return configSchemaVersion; }
    public double defaultReachRadius()        { return Waypoint.normalizeDefaultRadius(defaultReachRadius); }
    public boolean resetProgressOnWorldJoin() { return resetProgressOnWorldJoin; }
    public boolean restartRouteWhenComplete() { return restartRouteWhenComplete; }
    public boolean showRouteIndicesInGui()    { return showRouteIndicesInGui; }
    public boolean keepSubwaypointsVisibleUntilNextWaypoint() {
        return keepSubwaypointsVisibleUntilNextWaypoint;
    }
    public boolean allowBackwardProgress() { return allowBackwardProgress; }
    public int defaultWaypointColor()         { return defaultWaypointColor & 0xFFFFFF; }
    public int[] waypointPainterPalette() {
        int[] source = waypointPainterPalette;
        if (source == null || source.length != WaypointPaint.PALETTE_SIZE) {
            return WaypointPaint.defaultPalette(defaultWaypointColor());
        }
        int[] copy = source.clone();
        for (int i = 0; i < copy.length; i++) copy[i] &= 0xFFFFFF;
        return copy;
    }
    public boolean hasWaypointPainterPalette() {
        return waypointPainterPalette != null
                && waypointPainterPalette.length == WaypointPaint.PALETTE_SIZE;
    }
    public WaypointPaint waypointPainterDefaultPaint() {
        if (waypointPainterDefaultPaintCache != null) return waypointPainterDefaultPaintCache;
        if (waypointPainterDefaultPalette == null || waypointPainterDefaultPixels == null) return null;
        try {
            waypointPainterDefaultPaintCache = new WaypointPaint(waypointPainterDefaultPalette,
                    WaypointPaint.decodePixels(waypointPainterDefaultPixels));
            return waypointPainterDefaultPaintCache;
        } catch (RuntimeException invalidPaint) {
            waypointPainterDefaultPalette = null;
            waypointPainterDefaultPixels = null;
            return null;
        }
    }
    public int tracerColor()                  { return tracerColor; }
    public boolean matchTracerToWaypointColor() { return matchTracerToWaypointColor; }
    public double tracerOpacity()             { return tracerOpacity; }
    public double tracerThickness()           { return clamp(tracerThickness, 1.0, 12.0); }
    public double waypointOutlineThickness()  { return clamp(waypointOutlineThickness, 1.0, 12.0); }
    public double waypointMarkerScale()       { return clamp(waypointMarkerScale, 0.25, 3.0); }
    public double waypointOutlineOpacity()    { return clamp(waypointOutlineOpacity, 0.0, 1.0); }
    public boolean matchWaypointOutlineToWaypointColor() { return matchWaypointOutlineToWaypointColor; }
    public int waypointOutlineColor()         { return waypointOutlineColor & 0xFFFFFF; }
    public int resolvedWaypointOutlineColor(int waypointColor) {
        return matchWaypointOutlineToWaypointColor
                ? waypointColor & 0xFFFFFF
                : waypointOutlineColor();
    }
    public double beaconOpacity()             { return beaconOpacity; }
    public boolean showWaypointNames()        { return showWaypointNames; }
    public boolean showWaypointDistances()    { return showWaypointDistances; }
    public boolean showRouteProgress()        { return showRouteProgress; }
        public double labelScale()                { return clamp(labelScale, 0.25, 4.0); }
    public boolean scaleWaypointTextWithDistance() { return scaleWaypointTextWithDistance; }
    public boolean matchWaypointTextToWaypointColor() { return matchWaypointTextToWaypointColor; }
    /** Maps the old boolean API to previous-step visibility. */
    public boolean showCompleted()            { return sequenceVisibility().previous() > 0; }
    public int sequencePreviousWaypointCount() { return sequenceVisibility().previous(); }
    public boolean showCurrentSequenceWaypoint() { return showCurrentSequenceWaypoint; }
    public int sequenceNextWaypointCount() { return sequenceVisibility().next(); }
    public SequenceVisibility sequenceVisibility() {
        return new SequenceVisibility(sequencePreviousWaypointCount,
                showCurrentSequenceWaypoint, sequenceNextWaypointCount);
    }
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
    public boolean useEtherwarpHeight()       { return useEtherwarpHeight; }
    public boolean showDungeonEntryPathToFirstWaypoint() { return showDungeonEntryPathToFirstWaypoint; }
    public boolean showDungeonEntryPathToFollowingWaypoints() { return showDungeonEntryPathToFollowingWaypoints; }
    public int dungeonEntryPathColor()        { return dungeonEntryPathColor & 0xFFFFFF; }
    public EtherwarpAlignmentSound etherwarpAlignmentSound() {
        return etherwarpAlignmentSound == null
                ? EtherwarpAlignmentSound.OFF : etherwarpAlignmentSound;
    }
    public int routeLineColor()               { return routeLineColor & 0xFFFFFF; }
    public boolean showLabelBackdrop()        { return showLabelBackdrop; }
    public boolean showLabelTextShadow()      { return showLabelTextShadow; }
    public int maxWaypointLabels()            { return Math.max(0, maxWaypointLabels); }
    public double maxStaticWaypointRenderDistance() {
        return Math.max(0.0, maxStaticWaypointRenderDistance);
    }
    public double labelHeightOffset()         { return labelHeightOffset; }
    public BoxStyle boxStyle()                { return boxStyle == null ? BoxStyle.FILLED_OUTLINED : boxStyle; }
    public BeaconBeamMode beaconBeamMode()    {
        return beaconBeamMode == null ? BeaconBeamMode.OFF : beaconBeamMode;
    }
    public boolean beaconBeamExtendsBelowWaypoint() { return beaconBeamExtendsBelowWaypoint; }
    public boolean useBeaconBeamTextures() { return useBeaconBeamTextures; }
    public boolean editSounds() { return editSounds; }
    public boolean showEditModeSubtitle() { return showEditModeSubtitle; }
    public boolean chatCoordDetection()       { return chatCoordDetection; }
    public boolean showWaypointChatShareButtons() { return showWaypointChatShareButtons; }
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
    public boolean exportIncludeZone()         { return exportIncludeZone; }
    public boolean showExportRoutePreview()    { return showExportRoutePreview; }
    public boolean dungeonWaypointsFeatureEnabled() { return dungeonWaypointsFeatureEnabled; }
    public boolean skipAheadMechanicEnabled() { return skipAheadMechanicEnabled; }
    public boolean crystalHollowsEnabled() { return crystalHollowsEnabled; }
    public boolean crystalHollowsStructureWaypoints() { return crystalHollowsStructureWaypoints; }
    public boolean crystalHollowsShowRoughMarkers() { return crystalHollowsShowRoughMarkers; }
    public boolean crystalHollowsEntityDetection() { return crystalHollowsEntityDetection; }
    public boolean crystalHollowsChatDetection() { return crystalHollowsChatDetection; }
    public boolean crystalHollowsWishingCompassSolver() { return crystalHollowsWishingCompassSolver; }
    public boolean crystalHollowsCompassRays() { return crystalHollowsCompassRays; }
    public boolean crystalHollowsAnnounceDetections() { return crystalHollowsAnnounceDetections; }
    public boolean crystalHollowsNucleusWaypoints() { return crystalHollowsNucleusWaypoints; }
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
    public void setShowRouteIndicesInGui(boolean v)    { this.showRouteIndicesInGui = v; save(); }
    public void setKeepSubwaypointsVisibleUntilNextWaypoint(boolean v) {
        this.keepSubwaypointsVisibleUntilNextWaypoint = v;
        save();
    }
    public void setAllowBackwardProgress(boolean v) { this.allowBackwardProgress = v; save(); }
    public void setDefaultWaypointColor(int v)         { this.defaultWaypointColor = v & 0xFFFFFF; save(); }
    public void setWaypointPainterPalette(int[] palette) {
        if (palette == null || palette.length != WaypointPaint.PALETTE_SIZE) {
            throw new IllegalArgumentException("waypoint painter palette must contain 16 colors");
        }
        waypointPainterPalette = palette.clone();
        for (int i = 0; i < waypointPainterPalette.length; i++) {
            waypointPainterPalette[i] &= 0xFFFFFF;
        }
        save();
    }
    public void setWaypointPainterDefaultPaint(WaypointPaint paint) {
        waypointPainterDefaultPalette = paint == null ? null : paint.paletteCopy();
        waypointPainterDefaultPixels = paint == null ? null : paint.pixelsBase64();
        waypointPainterDefaultPaintCache = paint;
        save();
    }
    public void setTracerColor(int v)                  { this.tracerColor = v & 0xFFFFFF; save(); }
    public void setMatchTracerToWaypointColor(boolean v) { this.matchTracerToWaypointColor = v; save(); }
    public void setTracerOpacity(double v) {
        if (!Double.isFinite(v)) return;
        this.tracerOpacity = clamp(v, 0, 1);
        save();
    }
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
    public void setWaypointMarkerScale(double v) {
        if (!Double.isFinite(v)) return;
        this.waypointMarkerScale = clamp(v, 0.25, 3.0);
        save();
    }
    public void setWaypointOutlineOpacity(double v) {
        if (!Double.isFinite(v)) return;
        this.waypointOutlineOpacity = clamp(v, 0.0, 1.0);
        save();
    }
    public void setMatchWaypointOutlineToWaypointColor(boolean v) {
        this.matchWaypointOutlineToWaypointColor = v;
        save();
    }
    public void setWaypointOutlineColor(int v)         { this.waypointOutlineColor = v & 0xFFFFFF; save(); }
    public void setBeaconOpacity(double v) {
        if (!Double.isFinite(v)) return;
        this.beaconOpacity = clamp(v, 0, 1);
        save();
    }
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
    /** Maps the old boolean setting to the default previous-step window. */
    public void setShowCompleted(boolean v) {
        this.sequencePreviousWaypointCount = v ? SequenceVisibility.DEFAULT.previous() : 0;
        save();
    }
    public void setSequencePreviousWaypointCount(int v) {
        this.sequencePreviousWaypointCount = new SequenceVisibility(v, true, 0).previous();
        save();
    }
    public void setShowCurrentSequenceWaypoint(boolean v) {
        this.showCurrentSequenceWaypoint = v;
        save();
    }
    public void setSequenceNextWaypointCount(int v) {
        this.sequenceNextWaypointCount = new SequenceVisibility(0, true, v).next();
        save();
    }
    public void setSequenceVisibility(SequenceVisibility visibility) {
        if (visibility == null) return;
        sequencePreviousWaypointCount = visibility.previous();
        showCurrentSequenceWaypoint = visibility.current();
        sequenceNextWaypointCount = visibility.next();
        save();
    }
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
    public void setUseEtherwarpHeight(boolean v) {
        this.useEtherwarpHeight = v;
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
    public void setEtherwarpAlignmentSound(EtherwarpAlignmentSound v) {
        this.etherwarpAlignmentSound = v == null ? EtherwarpAlignmentSound.OFF : v;
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
    public void setShowWaypointChatShareButtons(boolean v) { this.showWaypointChatShareButtons = v; save(); }
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
    public void setExportIncludeZone(boolean v)         { this.exportIncludeZone = v; save(); }
    public void setShowExportRoutePreview(boolean v)    { this.showExportRoutePreview = v; save(); }
    public void setDungeonWaypointsFeatureEnabled(boolean v) { this.dungeonWaypointsFeatureEnabled = v; save(); }
    public void setCrystalHollowsEnabled(boolean v) { crystalHollowsEnabled = v; save(); }
    public void setCrystalHollowsStructureWaypoints(boolean v) { crystalHollowsStructureWaypoints = v; save(); }
    public void setCrystalHollowsShowRoughMarkers(boolean v) { crystalHollowsShowRoughMarkers = v; save(); }
    public void setCrystalHollowsEntityDetection(boolean v) { crystalHollowsEntityDetection = v; save(); }
    public void setCrystalHollowsChatDetection(boolean v) { crystalHollowsChatDetection = v; save(); }
    public void setCrystalHollowsWishingCompassSolver(boolean v) { crystalHollowsWishingCompassSolver = v; save(); }
    public void setCrystalHollowsCompassRays(boolean v) { crystalHollowsCompassRays = v; save(); }
    public void setCrystalHollowsAnnounceDetections(boolean v) { crystalHollowsAnnounceDetections = v; save(); }
    public void setCrystalHollowsNucleusWaypoints(boolean v) { crystalHollowsNucleusWaypoints = v; save(); }
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
    public void setBoxStyle(BoxStyle v)                { this.boxStyle = v == null ? BoxStyle.FILLED_OUTLINED : v; save(); }
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
        replaceShareableSettingsWith(replacement);
    }
    public void replaceShareableSettingsWith(WaypointerConfig replacement) {
        if (replacement == null) return;
        configSchemaVersion = CONFIG_SCHEMA_VERSION;
        defaultReachRadius = replacement.defaultReachRadius;
        resetProgressOnWorldJoin = replacement.resetProgressOnWorldJoin;
        restartRouteWhenComplete = replacement.restartRouteWhenComplete;
        showRouteIndicesInGui = replacement.showRouteIndicesInGui;
        keepSubwaypointsVisibleUntilNextWaypoint =
                replacement.keepSubwaypointsVisibleUntilNextWaypoint;
        allowBackwardProgress = replacement.allowBackwardProgress;
        defaultWaypointColor = replacement.defaultWaypointColor;
        tracerColor = replacement.tracerColor;
        matchTracerToWaypointColor = replacement.matchTracerToWaypointColor;
        tracerOpacity = replacement.tracerOpacity;
        tracerThickness = replacement.tracerThickness;
        waypointOutlineThickness = replacement.waypointOutlineThickness;
        waypointMarkerScale = replacement.waypointMarkerScale;
        waypointOutlineOpacity = replacement.waypointOutlineOpacity;
        matchWaypointOutlineToWaypointColor = replacement.matchWaypointOutlineToWaypointColor;
        waypointOutlineColor = replacement.waypointOutlineColor;
        beaconOpacity = replacement.beaconOpacity;
        showWaypointNames = replacement.showWaypointNames;
        showWaypointDistances = replacement.showWaypointDistances;
        showRouteProgress = replacement.showRouteProgress;
        labelScale = replacement.labelScale;
        scaleWaypointTextWithDistance = replacement.scaleWaypointTextWithDistance;
        matchWaypointTextToWaypointColor = replacement.matchWaypointTextToWaypointColor;
        sequencePreviousWaypointCount = replacement.sequencePreviousWaypointCount;
        showCurrentSequenceWaypoint = replacement.showCurrentSequenceWaypoint;
        sequenceNextWaypointCount = replacement.sequenceNextWaypointCount;
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
        useEtherwarpHeight = replacement.useEtherwarpHeight;
        showDungeonEntryPathToFirstWaypoint = replacement.showDungeonEntryPathToFirstWaypoint;
        showDungeonEntryPathToFollowingWaypoints = replacement.showDungeonEntryPathToFollowingWaypoints;
        dungeonEntryPathColor = replacement.dungeonEntryPathColor;
        etherwarpAlignmentSound = replacement.etherwarpAlignmentSound;
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
        showWaypointChatShareButtons = replacement.showWaypointChatShareButtons;
        chatCodecDetection = replacement.chatCodecDetection;
        showContributorBadges = replacement.showContributorBadges;
        importedRouteColorMode = replacement.importedRouteColorMode;
        importedRouteDefaultColor = replacement.importedRouteDefaultColor;
        exportIncludeNames = replacement.exportIncludeNames;
        exportIncludeColors = replacement.exportIncludeColors;
        exportIncludeRadii = replacement.exportIncludeRadii;
        exportIncludeWaypointFlags = replacement.exportIncludeWaypointFlags;
        exportIncludeGroupMeta = replacement.exportIncludeGroupMeta;
        exportIncludeZone = replacement.exportIncludeZone;
        showExportRoutePreview = replacement.showExportRoutePreview;
        dungeonWaypointsFeatureEnabled = replacement.dungeonWaypointsFeatureEnabled;
        skipAheadMechanicEnabled = replacement.skipAheadMechanicEnabled;
        crystalHollowsEnabled = replacement.crystalHollowsEnabled;
        crystalHollowsStructureWaypoints = replacement.crystalHollowsStructureWaypoints;
        crystalHollowsShowRoughMarkers = replacement.crystalHollowsShowRoughMarkers;
        crystalHollowsEntityDetection = replacement.crystalHollowsEntityDetection;
        crystalHollowsChatDetection = replacement.crystalHollowsChatDetection;
        crystalHollowsWishingCompassSolver = replacement.crystalHollowsWishingCompassSolver;
        crystalHollowsCompassRays = replacement.crystalHollowsCompassRays;
        crystalHollowsAnnounceDetections = replacement.crystalHollowsAnnounceDetections;
        crystalHollowsNucleusWaypoints = replacement.crystalHollowsNucleusWaypoints;
        irisShaderHudFallback = replacement.irisShaderHudFallback;
        tempDefaultMode = replacement.tempDefaultMode;
        tempDefaultDurationSec = replacement.tempDefaultDurationSec;
        migratedDuringLoad = false;
        save();
    }

    public void disableAllSettings() {
        resetProgressOnWorldJoin = false;
        restartRouteWhenComplete = false;
        showRouteIndicesInGui = false;
        keepSubwaypointsVisibleUntilNextWaypoint = false;
        allowBackwardProgress = false;
        matchTracerToWaypointColor = false;
        matchWaypointOutlineToWaypointColor = false;
        showWaypointNames = false;
        showWaypointDistances = false;
        showRouteProgress = false;
        beaconOpacity = 0.0;
        waypointOutlineOpacity = 0.0;
        scaleWaypointTextWithDistance = false;
        matchWaypointTextToWaypointColor = false;
        sequencePreviousWaypointCount = 0;
        showCurrentSequenceWaypoint = false;
        sequenceNextWaypointCount = 0;
        showTracer = false;
        dimSequenceContextWaypoints = false;
        hideTracerOnStaticRoutes = false;
        hideWaypointsNearPlayer = false;
        hideWaypointLabelsNearPlayer = false;
        hideReachedStaticWaypointsUntilCycleComplete = false;
        skipAheadOnlyVisibleWaypoints = false;
        showRouteLines = false;
        useEtherwarpHeight = false;
        showDungeonEntryPathToFirstWaypoint = false;
        showDungeonEntryPathToFollowingWaypoints = false;
        etherwarpAlignmentSound = EtherwarpAlignmentSound.OFF;
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
        showWaypointChatShareButtons = false;
        chatCodecDetection = false;
        showContributorBadges = false;
        importedRouteColorMode = WaypointGroup.GradientMode.MANUAL;
        exportIncludeNames = false;
        exportIncludeColors = false;
        exportIncludeRadii = false;
        exportIncludeWaypointFlags = false;
        exportIncludeGroupMeta = false;
        exportIncludeZone = false;
        showExportRoutePreview = false;
        dungeonWaypointsFeatureEnabled = false;
        skipAheadMechanicEnabled = false;
        crystalHollowsEnabled = false;
        crystalHollowsStructureWaypoints = false;
        crystalHollowsShowRoughMarkers = false;
        crystalHollowsEntityDetection = false;
        crystalHollowsChatDetection = false;
        crystalHollowsWishingCompassSolver = false;
        crystalHollowsCompassRays = false;
        crystalHollowsAnnounceDetections = false;
        crystalHollowsNucleusWaypoints = false;
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
        showRouteIndicesInGui = defaults.showRouteIndicesInGui;
        keepSubwaypointsVisibleUntilNextWaypoint =
                defaults.keepSubwaypointsVisibleUntilNextWaypoint;
        allowBackwardProgress = defaults.allowBackwardProgress;
        defaultWaypointColor = defaults.defaultWaypointColor;
        waypointPainterPalette = null;
        waypointPainterDefaultPalette = null;
        waypointPainterDefaultPixels = null;
        waypointPainterDefaultPaintCache = null;
        tracerColor = defaults.tracerColor;
        matchTracerToWaypointColor = defaults.matchTracerToWaypointColor;
        tracerOpacity = defaults.tracerOpacity;
        tracerThickness = defaults.tracerThickness;
        waypointOutlineThickness = defaults.waypointOutlineThickness;
        waypointMarkerScale = defaults.waypointMarkerScale;
        waypointOutlineOpacity = defaults.waypointOutlineOpacity;
        matchWaypointOutlineToWaypointColor = defaults.matchWaypointOutlineToWaypointColor;
        waypointOutlineColor = defaults.waypointOutlineColor;
        beaconOpacity = defaults.beaconOpacity;
        showWaypointNames = defaults.showWaypointNames;
        showWaypointDistances = defaults.showWaypointDistances;
        showRouteProgress = defaults.showRouteProgress;
        labelScale = defaults.labelScale;
        scaleWaypointTextWithDistance = defaults.scaleWaypointTextWithDistance;
        matchWaypointTextToWaypointColor = defaults.matchWaypointTextToWaypointColor;
        sequencePreviousWaypointCount = defaults.sequencePreviousWaypointCount;
        showCurrentSequenceWaypoint = defaults.showCurrentSequenceWaypoint;
        sequenceNextWaypointCount = defaults.sequenceNextWaypointCount;
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
        useEtherwarpHeight = defaults.useEtherwarpHeight;
        showDungeonEntryPathToFirstWaypoint = defaults.showDungeonEntryPathToFirstWaypoint;
        showDungeonEntryPathToFollowingWaypoints = defaults.showDungeonEntryPathToFollowingWaypoints;
        dungeonEntryPathColor = defaults.dungeonEntryPathColor;
        etherwarpAlignmentSound = defaults.etherwarpAlignmentSound;
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
        showWaypointChatShareButtons = defaults.showWaypointChatShareButtons;
        chatCodecDetection = defaults.chatCodecDetection;
        showContributorBadges = defaults.showContributorBadges;
        importedRouteColorMode = defaults.importedRouteColorMode;
        importedRouteDefaultColor = defaults.importedRouteDefaultColor;
        exportIncludeNames = defaults.exportIncludeNames;
        exportIncludeColors = defaults.exportIncludeColors;
        exportIncludeRadii = defaults.exportIncludeRadii;
        exportIncludeWaypointFlags = defaults.exportIncludeWaypointFlags;
        exportIncludeGroupMeta = defaults.exportIncludeGroupMeta;
        exportIncludeZone = defaults.exportIncludeZone;
        showExportRoutePreview = defaults.showExportRoutePreview;
        dungeonWaypointsFeatureEnabled = defaults.dungeonWaypointsFeatureEnabled;
        skipAheadMechanicEnabled = defaults.skipAheadMechanicEnabled;
        crystalHollowsEnabled = defaults.crystalHollowsEnabled;
        crystalHollowsStructureWaypoints = defaults.crystalHollowsStructureWaypoints;
        crystalHollowsShowRoughMarkers = defaults.crystalHollowsShowRoughMarkers;
        crystalHollowsEntityDetection = defaults.crystalHollowsEntityDetection;
        crystalHollowsChatDetection = defaults.crystalHollowsChatDetection;
        crystalHollowsWishingCompassSolver = defaults.crystalHollowsWishingCompassSolver;
        crystalHollowsCompassRays = defaults.crystalHollowsCompassRays;
        crystalHollowsAnnounceDetections = defaults.crystalHollowsAnnounceDetections;
        crystalHollowsNucleusWaypoints = defaults.crystalHollowsNucleusWaypoints;
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
