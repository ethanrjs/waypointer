package com.babbur.waypointer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import com.google.gson.annotations.SerializedName;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointStyle;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static com.babbur.waypointer.util.MathUtil.clamp;

public final class WaypointerConfig {

    public enum BoxStyle { OUTLINED, FILLED, FILLED_OUTLINED, PAINT }

    public enum BeaconBeamMode { OFF, CURRENT, ALL_VISIBLE }

    public enum EtherwarpAlignmentSound {
        OFF(""), EXPERIENCE("entity.experience_orb.pickup"), PLING("block.note_block.pling"), BELL("block.bell.use");

        private final String id;
        EtherwarpAlignmentSound(String id) { this.id = id; }
        public String id() { return id; }
    }

    private static final String FILE_NAME = "config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final double DEFAULT_EDIT_MODE_PLACEMENT_REACH = 5.0;
    public static final double MIN_EDIT_MODE_PLACEMENT_REACH = 1.0;
    public static final double MAX_EDIT_MODE_PLACEMENT_REACH = 64.0;
    private static final int CONFIG_SCHEMA_VERSION = 9;
    private static final int TEMP_DURATION_MIN_SECONDS = 1;
    private static final int TEMP_DURATION_MAX_SECONDS = 24 * 60 * 60;
    private static final int SECONDS_PER_MINUTE = 60;

    private Map<String, WaypointStyle> namedStyles = new LinkedHashMap<>();
    private Map<String, WaypointStyle> zoneStyles = new LinkedHashMap<>();
    private String preferredStyleId = "";
    private Map<String, WaypointStyle> crystalHollowsStyles = new LinkedHashMap<>();
    transient boolean resolvedStyleSnapshot;
    final transient Map<List<WaypointStyle>, WaypointerConfig> resolvedStyles = new LinkedHashMap<>();
    final transient Map<WaypointGroup, WaypointStyles.GroupStyles> groupStyles = new ConcurrentHashMap<>();
    transient volatile int styleGeneration;
    private double routeMinRenderDistance = 0.0;
    private double routeMaxRenderDistance = 0.0;
    private double routeLineWidth = 0.0;
    private double routeLineOpacity = 1.0;
    private double beaconWidthScale = 1.0;
    private double beaconMotionSpeed = 1.0;
    private double tracerCenterGap = 0.0;
    private double labelBackdropOpacity = 1.0;
    private double labelDetailDistance = 16.0;
    private double completedWaypointOpacity = 0.25;
    private double currentWaypointOpacity = 1.0;
    private double upcomingWaypointOpacity = 0.65;
    private double sequenceContextOpacity = 0.35;
    private boolean routeLineDashed;
    private boolean tracerEdgeArrow;
    private boolean labelShowRouteName;
    private boolean labelShowCoordinates;
    private boolean labelDetailOnDemand;
    private boolean preventLabelOverlap;
    private boolean showWaypointRoleBadge;
    private String waypointIcon = "NONE";

    private int configSchemaVersion = CONFIG_SCHEMA_VERSION;
    private double defaultReachRadius = Waypoint.DEFAULT_REACH_RADIUS;

    private double editModePlacementReach = DEFAULT_EDIT_MODE_PLACEMENT_REACH;
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
    private boolean renderAntialiasing = true;
    private double waypointMarkerScale = 1.0;
    private double waypointOutlineOpacity = 1.0;
    private boolean matchWaypointOutlineToWaypointColor = true;
    private int waypointOutlineColor = Waypoint.DEFAULT_COLOR;
    private double beaconOpacity = 0.33;
    private boolean showWaypointNames = true;
    private boolean numberUnnamedWaypoints = true;
    private boolean showWaypointDistances = true;
    private boolean showRouteProgress = false;
    private double labelScale = 1.0;
    private boolean labelFollowCameraEffects = true;
    private double labelOpacity = 1.0;
    private int waypointSkipFadeMs;
    private boolean scaleWaypointTextWithDistance = false;
    private boolean matchWaypointTextToWaypointColor = true;
    private int labelTextColor = 0xFFFFFF;
    private boolean labelColorForAllText;
    private int sequencePreviousWaypointCount = SequenceVisibility.DEFAULT.previous();
    private boolean showCurrentSequenceWaypoint = SequenceVisibility.DEFAULT.current();
    private int sequenceNextWaypointCount = SequenceVisibility.DEFAULT.next();
    private boolean colorSequenceWaypointsByRole = false;
    private int sequencePreviousWaypointColor = 0x808080;
    private int sequenceCurrentWaypointColor = Waypoint.DEFAULT_COLOR;
    private int sequenceNextWaypointColor = 0x00BFFF;
    private boolean showTracer = true;
    private boolean dimSequenceContextWaypoints = true;
    private boolean hideTracerOnStaticRoutes = true;
    private boolean hideWaypointsNearPlayer = false;
    private double hideWaypointsNearRadius = 5.0;
    private boolean hideWaypointLabelsNearPlayer = false;
    private double hideWaypointLabelsNearRadius = 5.0;
    private boolean showLabels = true;
    private double labelMaxDistance = 0.0;
    private boolean hideReachedStaticWaypointsUntilCycleComplete = false;
    private boolean skipAheadOnlyVisibleWaypoints = true;
    private boolean showRouteLines = false;
    private boolean useEtherwarpHeight = false;
    private boolean lineOfSightOnly = false;
    private boolean showDungeonEntryPathToFirstWaypoint = false;
    private boolean showDungeonEntryPathToFollowingWaypoints = false;
    private int dungeonEntryPathColor = 0x00FF00;
    @SerializedName("etherwarpAlignmentSoundId")
    private String etherwarpAlignmentSound = "";
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
    private double menuOpacity = 1.0;

    private boolean chatCoordDetection = true;
    private boolean showChatCoordBlockButton = false;
    private List<String> chatCoordSenderBlacklist = new ArrayList<>();
    private boolean autoAddChatTempWaypoints = false;
    private boolean placeNewWaypointsBelowPlayer = true;
    private boolean focusTempWaypoints = false;
    private boolean showWaypointChatShareButtons = true;
    private boolean chatCodecDetection = true;
    private boolean showContributorBadges = true;
    private WaypointGroup.GradientMode importedRouteColorMode = WaypointGroup.GradientMode.STATIC;
    private int importedRouteDefaultColor = 0x00FF00;
    private boolean exportIncludeNames = true;
    private boolean exportIncludeColors = true;
    private boolean exportIncludeRadii = true;
    private boolean exportIncludeWaypointFlags = true;
    private boolean exportIncludeGroupMeta = true;
    private boolean exportIncludeZone = true;
    private boolean dungeonWaypointsFeatureEnabled = false;
    private boolean skipAheadMechanicEnabled = true;

    private boolean crystalHollowsMinimap = false;
    private int crystalHollowsMinimapSize = 144;
    private int crystalHollowsMinimapCorner = 0;
    private boolean crystalHollowsMinimapLabels = true;
    private double minimapScale = 1.0;
    private boolean minimapCustomPosition = false;
    private double minimapPositionX = 0.0;
    private double minimapPositionY = 0.0;
    private Map<String, Boolean> crystalHollowsDetections = new LinkedHashMap<>();
    private boolean crystalHollowsEnabled = true;
    private boolean crystalHollowsStructureWaypoints = false;
    private boolean crystalHollowsHideStructuresFolder = false;
    private boolean crystalHollowsShowRoughMarkers = true;
    private boolean crystalHollowsEntityDetection = true;
    private boolean crystalHollowsChatDetection = true;
    private boolean coordinateShareHelper = true;
    private boolean crystalHollowsMetalDetector = true;
    private boolean crystalHollowsRemoteSharing = true;

    private boolean crystalHollowsSkyblockerSharing = true;
    private boolean crystalHollowsWishingCompassSolver = true;
    private boolean crystalHollowsCompassRays = true;
    private boolean crystalHollowsAnnounceDetections = true;
    private boolean crystalHollowsNucleusWaypoints = false;
    private boolean crystalHollowsCompassAutoFocus = true;
    private boolean crystalHollowsCompassTracers = true;
    private boolean crystalHollowsTracers = true;

    private boolean irisShaderHudFallback = true;
    private int tempDefaultMode = Waypoint.TEMP_TIME;
    private int tempDefaultDurationSec = SECONDS_PER_MINUTE;

    private static final long SAVE_DEBOUNCE_MS = 500L;

    private transient Path file;
    private transient AsyncSaver saver;
    private transient boolean migratedDuringLoad;
    private transient volatile String pendingSnapshotJson;
    private transient IOException writeBlockCause;
    private transient volatile boolean writesBlockedForFutureSchema;

    static WaypointerConfig configCodeBaseline() {
        WaypointerConfig config = new WaypointerConfig();
        config.exportIncludeNames = false;
        config.exportIncludeColors = false;
        config.exportIncludeRadii = false;
        config.exportIncludeWaypointFlags = false;
        config.exportIncludeGroupMeta = false;
        config.exportIncludeZone = false;
        config.crystalHollowsStructureWaypoints = true;
        return config;
    }

    static WaypointerConfig legacyConfigCodeDefaults() {
        WaypointerConfig config = new WaypointerConfig();
        config.crystalHollowsStructureWaypoints = true;
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
        } catch (UnsupportedFutureSchemaException future) {
            Waypointer.LOGGER.error(
                    "Config at {} uses newer schema version {} (current {}); using defaults for this session and blocking writes to preserve the original file",
                    file, future.schemaVersion, CONFIG_SCHEMA_VERSION);
            config = new WaypointerConfig();
            config.writesBlockedForFutureSchema = true;
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
        int loadedSchemaVersion = schemaVersion(raw);
        if (loadedSchemaVersion > CONFIG_SCHEMA_VERSION) {
            throw new UnsupportedFutureSchemaException(loadedSchemaVersion);
        }
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        JsonElement savedNamedStyles = root.remove("namedStyles");
        JsonElement savedZoneStyles = root.remove("zoneStyles");
        JsonElement savedStructureStyles = root.remove("crystalHollowsStyles");
        JsonElement savedPreferredStyle = root.remove("preferredStyleId");
        JsonElement savedDetections = root.remove("crystalHollowsDetections");
        WaypointerConfig config = GSON.fromJson(root, WaypointerConfig.class);
        if (config == null) {
            throw new IllegalArgumentException("config JSON is null");
        }
        config.migrateLegacyTempDurationMinutes(raw, loadedSchemaVersion);
        config.migrateSequenceVisibility(raw, loadedSchemaVersion);
        config.migrateEtherwarpAlignmentSound(raw, loadedSchemaVersion);
        config.applyMigrations(loadedSchemaVersion);

        if (!root.has("labelBackdropOpacity")) config.labelBackdropOpacity = config.labelOpacity();
        config.namedStyles = readStyles(savedNamedStyles);
        config.zoneStyles = readStyles(savedZoneStyles);
        config.crystalHollowsStyles = readStyles(savedStructureStyles);
        config.preferredStyleId = savedPreferredStyle != null && savedPreferredStyle.isJsonPrimitive()
                ? savedPreferredStyle.getAsString() : "";
        if (savedDetections != null && savedDetections.isJsonObject()) {
            savedDetections.getAsJsonObject().entrySet().forEach(entry -> {
                if (!entry.getKey().isBlank() && entry.getKey().length() <= 80
                        && entry.getValue().isJsonPrimitive()
                        && entry.getValue().getAsJsonPrimitive().isBoolean()
                        && !entry.getValue().getAsBoolean())
                    config.crystalHollowsDetections.put(entry.getKey(), false);
            });
        }
        config.minimapScale = config.minimapScale();
        config.minimapPositionX = config.minimapPositionX();
        config.minimapPositionY = config.minimapPositionY();
        config.normalizeStylePreferences();
        return config;
    }

    private static final class UnsupportedFutureSchemaException extends IllegalArgumentException {
        private final int schemaVersion;

        private UnsupportedFutureSchemaException(int schemaVersion) {
            super("config schema version " + schemaVersion
                    + " is newer than supported version " + CONFIG_SCHEMA_VERSION);
            this.schemaVersion = schemaVersion;
        }
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
        if (schemaVersion < 9 && !exportIncludeNames && !exportIncludeColors && !exportIncludeRadii
                && !exportIncludeWaypointFlags && !exportIncludeGroupMeta && !exportIncludeZone) {
            exportIncludeNames = true;
            exportIncludeColors = true;
            exportIncludeRadii = true;
            exportIncludeWaypointFlags = true;
            exportIncludeGroupMeta = true;
            exportIncludeZone = true;
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
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        if (!root.has("etherwarpAlignmentSoundId") && schemaVersion < 8) {
            if (schemaVersion >= 7 && root.has("etherwarpAlignmentSoundType")) {
                try {
                    etherwarpAlignmentSound = EtherwarpAlignmentSound.valueOf(
                            root.get("etherwarpAlignmentSoundType").getAsString()).id();
                } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
                    etherwarpAlignmentSound = "";
                }
            } else if (root.has("etherwarpAlignmentSound")
                    && root.get("etherwarpAlignmentSound").getAsBoolean()) {
                etherwarpAlignmentSound = EtherwarpAlignmentSound.EXPERIENCE.id();
            }
            migratedDuringLoad = true;
        }
        etherwarpAlignmentSound = normalizeSoundId(etherwarpAlignmentSound);
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

    void invalidateResolvedStyles() {
        resolvedStyles.clear();
        groupStyles.clear();
        styleGeneration++;
    }

    public void save() {
        invalidateResolvedStyles();
        if (saver == null || writesBlockedForFutureSchema) return;
        pendingSnapshotJson = GSON.toJson(this);
        saver.markDirty();
    }

    private static Map<String, WaypointStyle> readStyles(JsonElement encoded) {
        Map<String, WaypointStyle> styles = new LinkedHashMap<>();
        if (encoded == null || !encoded.isJsonObject()) return styles;
        encoded.getAsJsonObject().entrySet().forEach(entry -> {
            if (entry.getKey().isBlank() || entry.getKey().length() > 80
                    || !entry.getValue().isJsonObject()) return;
            JsonElement values = entry.getValue().getAsJsonObject().get("values");
            if (values == null || !values.isJsonObject()) return;
            Map<String, String> properties = new LinkedHashMap<>();
            values.getAsJsonObject().entrySet().forEach(property -> {
                if (property.getValue().isJsonPrimitive())
                    properties.put(property.getKey(), property.getValue().getAsString());
            });
            styles.put(entry.getKey(), WaypointStyles.normalize(new WaypointStyle(properties)));
        });
        return styles;
    }

    private void normalizeStylePreferences() {
        if (namedStyles == null) namedStyles = new LinkedHashMap<>();
        if (zoneStyles == null) zoneStyles = new LinkedHashMap<>();
        zoneStyles.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null
                || entry.getValue().isEmpty());
        if (crystalHollowsStyles == null) crystalHollowsStyles = new LinkedHashMap<>();
        namedStyles.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        crystalHollowsStyles.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        preferredStyleId = preferredStyleId == null ? "" : preferredStyleId.strip();
        routeMinRenderDistance = routeMinRenderDistance();
        routeMaxRenderDistance = routeMaxRenderDistance();
        routeLineWidth = routeLineWidth();
        routeLineOpacity = routeLineOpacity();
        beaconWidthScale = beaconWidthScale();
        beaconMotionSpeed = beaconMotionSpeed();
        tracerCenterGap = tracerCenterGap();
        labelBackdropOpacity = labelBackdropOpacity();
        labelDetailDistance = labelDetailDistance();
        completedWaypointOpacity = completedWaypointOpacity();
        currentWaypointOpacity = currentWaypointOpacity();
        upcomingWaypointOpacity = upcomingWaypointOpacity();
        sequenceContextOpacity = sequenceContextOpacity();
        waypointIcon = waypointIcon();
    }
    public WaypointStyle namedStyle(String id) { return namedStyles.getOrDefault(id, WaypointStyle.EMPTY); }
    public Map<String, WaypointStyle> namedStyles() { return Collections.unmodifiableMap(new LinkedHashMap<>(namedStyles)); }
    public String preferredStyleId() { return preferredStyleId; }
    public void setPreferredStyleId(String id) { preferredStyleId = id == null ? "" : id.strip(); save(); }
    public void putNamedStyle(String id, WaypointStyle style) {
        if (id == null || id.isBlank() || id.length() > 80 || style == null) return;
        namedStyles.put(id.strip(), style); save();
    }
    public WaypointStyle zoneStyle(String zoneKey) {
        return zoneKey == null ? WaypointStyle.EMPTY : zoneStyles.getOrDefault(zoneKey, WaypointStyle.EMPTY);
    }
    public Map<String, WaypointStyle> zoneStyles() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(zoneStyles));
    }
    public void setZoneStyle(String zoneKey, WaypointStyle style) {
        if (zoneKey == null || zoneKey.isBlank() || zoneKey.length() > 80) return;
        if (style == null || style.isEmpty()) zoneStyles.remove(zoneKey);
        else zoneStyles.put(zoneKey, style);
        save();
    }
    public WaypointStyle crystalHollowsStyle(String structureId) {
        return crystalHollowsStyles.getOrDefault(structureId, WaypointStyle.EMPTY);
    }
    public void setCrystalHollowsStyle(String structureId, WaypointStyle style) {
        if (structureId == null || structureId.isBlank() || structureId.length() > 80) return;
        if (style == null || style.isEmpty()) crystalHollowsStyles.remove(structureId);
        else crystalHollowsStyles.put(structureId, style);
        save();
    }
    WaypointerConfig unsavedStyleCopy() {
        WaypointerConfig copy = GSON.fromJson(GSON.toJson(this), WaypointerConfig.class);
        copy.resolvedStyleSnapshot = true;
        return copy;
    }
    public WaypointerConfig exportSnapshot() {
        WaypointerConfig copy = unsavedStyleCopy();
        copy.resolvedStyleSnapshot = false;
        return copy;
    }
    private static double finiteStyleValue(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? clamp(value, min, max) : fallback;
    }
    static double normalizeEditModePlacementReach(double value) {
        if (!Double.isFinite(value)) return DEFAULT_EDIT_MODE_PLACEMENT_REACH;
        return clamp(value, MIN_EDIT_MODE_PLACEMENT_REACH, MAX_EDIT_MODE_PLACEMENT_REACH);
    }
    public double routeMinRenderDistance() { return finiteStyleValue(routeMinRenderDistance, 0.0, 0.0, 100000.0); }
    public void setRouteMinRenderDistance(double value) { if (!Double.isFinite(value)) return; routeMinRenderDistance = clamp(value, 0.0, 100000.0); save(); }
    public double routeMaxRenderDistance() { return finiteStyleValue(routeMaxRenderDistance, 0.0, 0.0, 100000.0); }
    public void setRouteMaxRenderDistance(double value) { if (!Double.isFinite(value)) return; routeMaxRenderDistance = clamp(value, 0.0, 100000.0); save(); }
    public double routeLineWidth() { return finiteStyleValue(routeLineWidth, 0.0, 0.0, 12.0); }
    public void setRouteLineWidth(double value) { if (!Double.isFinite(value)) return; routeLineWidth = clamp(value, 0.0, 12.0); save(); }
    public double routeLineOpacity() { return finiteStyleValue(routeLineOpacity, 1.0, 0.0, 1.0); }
    public void setRouteLineOpacity(double value) { if (!Double.isFinite(value)) return; routeLineOpacity = clamp(value, 0.0, 1.0); save(); }
    public double beaconWidthScale() { return finiteStyleValue(beaconWidthScale, 1.0, 0.1, 8.0); }
    public void setBeaconWidthScale(double value) { if (!Double.isFinite(value)) return; beaconWidthScale = clamp(value, 0.1, 8.0); save(); }
    public double beaconMotionSpeed() { return finiteStyleValue(beaconMotionSpeed, 1.0, 0.0, 5.0); }
    public void setBeaconMotionSpeed(double value) { if (!Double.isFinite(value)) return; beaconMotionSpeed = clamp(value, 0.0, 5.0); save(); }
    public double tracerCenterGap() { return finiteStyleValue(tracerCenterGap, 0.0, 0.0, 200.0); }
    public void setTracerCenterGap(double value) { if (!Double.isFinite(value)) return; tracerCenterGap = clamp(value, 0.0, 200.0); save(); }
    public double labelBackdropOpacity() { return finiteStyleValue(labelBackdropOpacity, 1.0, 0.0, 1.0); }
    public void setLabelBackdropOpacity(double value) { if (!Double.isFinite(value)) return; labelBackdropOpacity = clamp(value, 0.0, 1.0); save(); }
    public double labelDetailDistance() { return finiteStyleValue(labelDetailDistance, 16.0, 0.0, 10000.0); }
    public void setLabelDetailDistance(double value) { if (!Double.isFinite(value)) return; labelDetailDistance = clamp(value, 0.0, 10000.0); save(); }
    public double completedWaypointOpacity() { return finiteStyleValue(completedWaypointOpacity, 0.25, 0.0, 1.0); }
    public void setCompletedWaypointOpacity(double value) { if (!Double.isFinite(value)) return; completedWaypointOpacity = clamp(value, 0.0, 1.0); save(); }
    public double currentWaypointOpacity() { return finiteStyleValue(currentWaypointOpacity, 1.0, 0.0, 1.0); }
    public void setCurrentWaypointOpacity(double value) { if (!Double.isFinite(value)) return; currentWaypointOpacity = clamp(value, 0.0, 1.0); save(); }
    public double upcomingWaypointOpacity() { return finiteStyleValue(upcomingWaypointOpacity, 0.65, 0.0, 1.0); }
    public void setUpcomingWaypointOpacity(double value) { if (!Double.isFinite(value)) return; upcomingWaypointOpacity = clamp(value, 0.0, 1.0); save(); }
    public double sequenceContextOpacity() { return finiteStyleValue(sequenceContextOpacity, 0.35, 0.0, 1.0); }
    public void setSequenceContextOpacity(double value) { if (!Double.isFinite(value)) return; sequenceContextOpacity = clamp(value, 0.0, 1.0); save(); }
    public boolean routeLineDashed() { return routeLineDashed; }
    public void setRouteLineDashed(boolean value) { routeLineDashed = value; save(); }
    public boolean tracerEdgeArrow() { return tracerEdgeArrow; }
    public void setTracerEdgeArrow(boolean value) { tracerEdgeArrow = value; save(); }
    public boolean labelShowRouteName() { return labelShowRouteName; }
    public void setLabelShowRouteName(boolean value) { labelShowRouteName = value; save(); }
    public boolean labelShowCoordinates() { return labelShowCoordinates; }
    public void setLabelShowCoordinates(boolean value) { labelShowCoordinates = value; save(); }
    public boolean labelDetailOnDemand() { return labelDetailOnDemand; }
    public void setLabelDetailOnDemand(boolean value) { labelDetailOnDemand = value; save(); }
    public boolean preventLabelOverlap() { return preventLabelOverlap; }
    public void setPreventLabelOverlap(boolean value) { preventLabelOverlap = value; save(); }
    public boolean showWaypointRoleBadge() { return showWaypointRoleBadge; }
    public void setShowWaypointRoleBadge(boolean value) { showWaypointRoleBadge = value; save(); }

    public static final String CUSTOM_ICON_PREFIX = "CUSTOM:";
    public static final int CUSTOM_ICON_SIZE = 9;
    private static final Pattern CUSTOM_ICON = Pattern.compile(
            "CUSTOM:[01]{" + (CUSTOM_ICON_SIZE * CUSTOM_ICON_SIZE) + "}");
    public static boolean isValidWaypointIcon(String icon) {
        return icon != null && (Set.of("NONE", "DIAMOND", "CIRCLE", "SQUARE", "STAR").contains(icon)
                || CUSTOM_ICON.matcher(icon).matches());
    }
    public String waypointIcon() {
        return isValidWaypointIcon(waypointIcon) ? waypointIcon : "NONE";
    }
    public void setWaypointIcon(String value) {
        waypointIcon = value == null ? "NONE" : value.toUpperCase(Locale.ROOT);
        waypointIcon = waypointIcon();
        save();
    }

    public void flush() {
        if (saver != null) saver.flush();
    }

    private void writeToDisk() {
        if (file == null || writesBlockedForFutureSchema) return;
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
            Storage.writeDurably(tmp, json);
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
            Waypointer.LOGGER.error(
                    "Invalid config at {} could not be preserved; saves are blocked to prevent data loss",
                    file, quarantineFailure);
            return quarantineFailure;
        }
    }

    public int configSchemaVersion()               { return configSchemaVersion; }
    public double defaultReachRadius()        { return Waypoint.normalizeDefaultRadius(defaultReachRadius); }
    public double editModePlacementReach()    { return normalizeEditModePlacementReach(editModePlacementReach); }
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
    public BoxStyle effectiveBoxStyle() {
        return boxStyle();
    }

    public boolean renderAntialiasing() { return renderAntialiasing; }
    public void setRenderAntialiasing(boolean value) {
        renderAntialiasing = value;
        save();
    }
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
    public boolean numberUnnamedWaypoints()   { return numberUnnamedWaypoints; }
    public boolean showWaypointDistances()    { return showWaypointDistances; }
    public boolean showRouteProgress()        { return showRouteProgress; }
    public boolean labelFollowCameraEffects() { return labelFollowCameraEffects; }
    public double labelOpacity() { return Double.isFinite(labelOpacity) ? clamp(labelOpacity, 0, 1) : 1.0; }
        public double labelScale()                { return clamp(labelScale, 0.25, 4.0); }
    public boolean scaleWaypointTextWithDistance() { return scaleWaypointTextWithDistance; }
    public boolean matchWaypointTextToWaypointColor() { return matchWaypointTextToWaypointColor; }
    public int labelTextColor() { return labelTextColor & 0xFFFFFF; }
    public boolean labelColorForAllText() { return labelColorForAllText; }

    public int waypointSkipFadeMs() { return Math.max(0, Math.min(5000, waypointSkipFadeMs)); }
    public void setWaypointSkipFadeMs(int value) {
        waypointSkipFadeMs = Math.max(0, Math.min(5000, value));
        save();
    }

    public boolean showCompleted()            { return sequenceVisibility().previous() > 0; }
    public int sequencePreviousWaypointCount() { return sequenceVisibility().previous(); }
    public boolean showCurrentSequenceWaypoint() { return showCurrentSequenceWaypoint; }
    public int sequenceNextWaypointCount() { return sequenceVisibility().next(); }
    public boolean colorSequenceWaypointsByRole() { return colorSequenceWaypointsByRole; }
    public int sequencePreviousWaypointColor() { return sequencePreviousWaypointColor & 0xFFFFFF; }
    public int sequenceCurrentWaypointColor() { return sequenceCurrentWaypointColor & 0xFFFFFF; }
    public int sequenceNextWaypointColor() { return sequenceNextWaypointColor & 0xFFFFFF; }
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
    public boolean showLabels() { return showLabels; }
    public double labelMaxDistance() { return Double.isFinite(labelMaxDistance) ? Math.max(0.0, labelMaxDistance) : 0.0; }
    public boolean hideReachedStaticWaypointsUntilCycleComplete() { return hideReachedStaticWaypointsUntilCycleComplete; }
    public boolean skipAheadOnlyVisibleWaypoints() { return skipAheadOnlyVisibleWaypoints; }
    public boolean showRouteLines()           { return showRouteLines; }
    public boolean useEtherwarpHeight()       { return useEtherwarpHeight; }
    public boolean lineOfSightOnly()          { return lineOfSightOnly; }
    public boolean showDungeonEntryPathToFirstWaypoint() {
        return showDungeonEntryPathToFirstWaypoint || showDungeonEntryPathToFollowingWaypoints;
    }
    public boolean showDungeonEntryPathToFollowingWaypoints() { return showDungeonEntryPathToFollowingWaypoints; }
    public int dungeonEntryPathColor()        { return dungeonEntryPathColor & 0xFFFFFF; }
    public String etherwarpAlignmentSound() {
        return etherwarpAlignmentSound == null ? "" : etherwarpAlignmentSound;
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
    public double menuOpacity() { return Double.isFinite(menuOpacity) ? clamp(menuOpacity, 0.2, 1.0) : 1.0; }
    public void setMenuOpacity(double value) { if (!Double.isFinite(value)) return; menuOpacity = clamp(value, 0.2, 1.0); save(); }
    public boolean showChatCoordBlockButton() { return showChatCoordBlockButton; }
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
    public boolean dungeonWaypointsFeatureEnabled() { return dungeonWaypointsFeatureEnabled; }
    public boolean skipAheadMechanicEnabled() { return skipAheadMechanicEnabled; }
    public boolean crystalHollowsMinimap() { return crystalHollowsMinimap; }
    public int crystalHollowsMinimapSize() { return clamp(crystalHollowsMinimapSize, 96, 224); }
    public int crystalHollowsMinimapCorner() { return clamp(crystalHollowsMinimapCorner, 0, 3); }
    public boolean crystalHollowsMinimapLabels() { return crystalHollowsMinimapLabels; }
    public double minimapScale() { return finiteStyleValue(minimapScale, 1.0, 0.5, 2.0); }
    public boolean minimapCustomPosition() { return minimapCustomPosition; }
    public double minimapPositionX() { return finiteStyleValue(minimapPositionX, 0.0, 0.0, 1.0); }
    public double minimapPositionY() { return finiteStyleValue(minimapPositionY, 0.0, 0.0, 1.0); }
    public boolean crystalHollowsDetectionEnabled(String structureId) {
        return !Boolean.FALSE.equals(crystalHollowsDetections.get(structureId));
    }
    public boolean crystalHollowsEnabled() { return crystalHollowsEnabled; }
    public boolean crystalHollowsStructureWaypoints() { return crystalHollowsStructureWaypoints; }
    public boolean crystalHollowsHideStructuresFolder() { return crystalHollowsHideStructuresFolder; }
    public boolean crystalHollowsShowRoughMarkers() { return crystalHollowsShowRoughMarkers; }
    public boolean crystalHollowsEntityDetection() { return crystalHollowsEntityDetection; }
    public boolean crystalHollowsChatDetection() { return crystalHollowsChatDetection; }
    public boolean coordinateShareHelper() { return coordinateShareHelper; }
    public boolean crystalHollowsMetalDetector() { return crystalHollowsMetalDetector; }
    public boolean crystalHollowsRemoteSharing() { return crystalHollowsRemoteSharing; }
    public boolean crystalHollowsSkyblockerSharing() { return crystalHollowsSkyblockerSharing; }
    public boolean crystalHollowsWishingCompassSolver() { return crystalHollowsWishingCompassSolver; }
    public boolean crystalHollowsCompassRays() { return crystalHollowsCompassRays; }
    public boolean crystalHollowsAnnounceDetections() { return crystalHollowsAnnounceDetections; }
    public boolean crystalHollowsNucleusWaypoints() { return crystalHollowsNucleusWaypoints; }
    public boolean crystalHollowsCompassAutoFocus() { return crystalHollowsCompassAutoFocus; }
    public boolean crystalHollowsCompassTracers() { return crystalHollowsCompassTracers; }
    public boolean crystalHollowsTracers() { return crystalHollowsTracers; }
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
    public void setEditModePlacementReach(double v)    { this.editModePlacementReach = normalizeEditModePlacementReach(v); save(); }
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
    public void setNumberUnnamedWaypoints(boolean v)   { this.numberUnnamedWaypoints = v; save(); }
    public void setShowWaypointDistances(boolean v)    { this.showWaypointDistances = v; save(); }
    public void setShowRouteProgress(boolean v)        { this.showRouteProgress = v; save(); }
    public void setLabelFollowCameraEffects(boolean v) { this.labelFollowCameraEffects = v; save(); }
    public void setLabelOpacity(double v) {
        if (!Double.isFinite(v)) return;
        this.labelOpacity = clamp(v, 0, 1);
        save();
    }
        public void setLabelScale(double v) {
        if (!Double.isFinite(v)) return;
        this.labelScale = clamp(v, 0.25, 4.0);
        save();
    }
    public void setScaleWaypointTextWithDistance(boolean v) { this.scaleWaypointTextWithDistance = v; save(); }
    public void setMatchWaypointTextToWaypointColor(boolean v) { this.matchWaypointTextToWaypointColor = v; save(); }
    public void setLabelTextColor(int v) { this.labelTextColor = v & 0xFFFFFF; save(); }
    public void setLabelColorForAllText(boolean v) { this.labelColorForAllText = v; save(); }

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
    public void setColorSequenceWaypointsByRole(boolean v) {
        this.colorSequenceWaypointsByRole = v;
        save();
    }
    public void setSequencePreviousWaypointColor(int v) {
        this.sequencePreviousWaypointColor = v & 0xFFFFFF;
        save();
    }
    public void setSequenceCurrentWaypointColor(int v) {
        this.sequenceCurrentWaypointColor = v & 0xFFFFFF;
        save();
    }
    public void setSequenceNextWaypointColor(int v) {
        this.sequenceNextWaypointColor = v & 0xFFFFFF;
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
    public void setShowLabels(boolean v) {
        this.showLabels = v;
        save();
    }
    public void setLabelMaxDistance(double v) {
        this.labelMaxDistance = Double.isFinite(v) ? clamp(v, 0.0, 100000.0) : 0.0;
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
    public void setLineOfSightOnly(boolean v) {
        this.lineOfSightOnly = v;
        save();
    }
    public void setUseEtherwarpHeight(boolean v) {
        this.useEtherwarpHeight = v;
        save();
    }
    public void setShowDungeonEntryPathToFirstWaypoint(boolean v) {
        this.showDungeonEntryPathToFirstWaypoint = v;
        if (!v) this.showDungeonEntryPathToFollowingWaypoints = false;
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
        setEtherwarpAlignmentSound(v == null ? "" : v.id());
    }

    public void setEtherwarpAlignmentSound(String value) {
        this.etherwarpAlignmentSound = normalizeSoundId(value);
        save();
    }

    public static boolean isValidSoundId(String value) {
        return value != null && value.length() <= 256
                && (value.isBlank() || Identifier.tryParse(value.trim()) != null);
    }

    private static String normalizeSoundId(String value) {
        if (value == null || value.isBlank()) return "";
        if (!isValidSoundId(value)) throw new IllegalArgumentException("Invalid Minecraft sound ID");
        Identifier id = Identifier.parse(value.trim());
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }
    public void setRouteLineColor(int v) {
        this.routeLineColor = v & 0xFFFFFF;
        save();
    }
    public void setShowChatCoordBlockButton(boolean v) { this.showChatCoordBlockButton = v; save(); }
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
    public void setDungeonWaypointsFeatureEnabled(boolean v) { this.dungeonWaypointsFeatureEnabled = v; save(); }
    public void setCrystalHollowsMinimap(boolean v) { crystalHollowsMinimap = v; save(); }
    public void setCrystalHollowsMinimapSize(int v) { crystalHollowsMinimapSize = clamp(v, 96, 224); save(); }
    public void setCrystalHollowsMinimapCorner(int v) { crystalHollowsMinimapCorner = clamp(v, 0, 3); minimapCustomPosition = false; save(); }
    public void setCrystalHollowsMinimapLabels(boolean v) { crystalHollowsMinimapLabels = v; save(); }
    public void setMinimapScale(double v) { if (!Double.isFinite(v)) return; minimapScale = clamp(v, 0.5, 2.0); save(); }
    public void setMinimapCustomPosition(boolean v) { minimapCustomPosition = v; save(); }
    public void setMinimapPositionX(double v) { if (!Double.isFinite(v)) return; minimapPositionX = clamp(v, 0.0, 1.0); save(); }
    public void setMinimapPositionY(double v) { if (!Double.isFinite(v)) return; minimapPositionY = clamp(v, 0.0, 1.0); save(); }
    public void setCrystalHollowsDetectionEnabled(String structureId, boolean enabled) {
        if (structureId == null || structureId.isBlank() || structureId.length() > 80) return;
        if (enabled) crystalHollowsDetections.remove(structureId);
        else crystalHollowsDetections.put(structureId, false);
        save();
    }
    public void setCrystalHollowsEnabled(boolean v) { crystalHollowsEnabled = v; save(); }
    public void setCrystalHollowsStructureWaypoints(boolean v) { crystalHollowsStructureWaypoints = v; save(); }
    public void setCrystalHollowsHideStructuresFolder(boolean v) { crystalHollowsHideStructuresFolder = v; save(); }
    public void setCrystalHollowsShowRoughMarkers(boolean v) { crystalHollowsShowRoughMarkers = v; save(); }
    public void setCrystalHollowsEntityDetection(boolean v) { crystalHollowsEntityDetection = v; save(); }
    public void setCrystalHollowsChatDetection(boolean v) { crystalHollowsChatDetection = v; save(); }
    public void setCoordinateShareHelper(boolean v) { coordinateShareHelper = v; save(); }
    public void setCrystalHollowsMetalDetector(boolean v) { crystalHollowsMetalDetector = v; save(); }
    public void setCrystalHollowsRemoteSharing(boolean v) { crystalHollowsRemoteSharing = v; save(); }
    public void setCrystalHollowsSkyblockerSharing(boolean v) { crystalHollowsSkyblockerSharing = v; save(); }
    public void setCrystalHollowsWishingCompassSolver(boolean v) { crystalHollowsWishingCompassSolver = v; save(); }
    public void setCrystalHollowsCompassRays(boolean v) { crystalHollowsCompassRays = v; save(); }
    public void setCrystalHollowsAnnounceDetections(boolean v) { crystalHollowsAnnounceDetections = v; save(); }
    public void setCrystalHollowsNucleusWaypoints(boolean v) { crystalHollowsNucleusWaypoints = v; save(); }
    public void setCrystalHollowsCompassAutoFocus(boolean v) { crystalHollowsCompassAutoFocus = v; save(); }
    public void setCrystalHollowsCompassTracers(boolean v) { crystalHollowsCompassTracers = v; save(); }
    public void setCrystalHollowsTracers(boolean v) { crystalHollowsTracers = v; save(); }
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
        editModePlacementReach = replacement.editModePlacementReach;
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
        renderAntialiasing = replacement.renderAntialiasing;
        waypointMarkerScale = replacement.waypointMarkerScale;
        waypointOutlineOpacity = replacement.waypointOutlineOpacity;
        matchWaypointOutlineToWaypointColor = replacement.matchWaypointOutlineToWaypointColor;
        waypointOutlineColor = replacement.waypointOutlineColor;
        beaconOpacity = replacement.beaconOpacity;
        showWaypointNames = replacement.showWaypointNames;
        numberUnnamedWaypoints = replacement.numberUnnamedWaypoints;
        showWaypointDistances = replacement.showWaypointDistances;
        showRouteProgress = replacement.showRouteProgress;
        labelScale = replacement.labelScale;
        labelFollowCameraEffects = replacement.labelFollowCameraEffects;
        labelOpacity = replacement.labelOpacity;
        waypointSkipFadeMs = replacement.waypointSkipFadeMs;
        scaleWaypointTextWithDistance = replacement.scaleWaypointTextWithDistance;
        matchWaypointTextToWaypointColor = replacement.matchWaypointTextToWaypointColor;
        labelTextColor = replacement.labelTextColor;
        labelColorForAllText = replacement.labelColorForAllText;
        sequencePreviousWaypointCount = replacement.sequencePreviousWaypointCount;
        showCurrentSequenceWaypoint = replacement.showCurrentSequenceWaypoint;
        sequenceNextWaypointCount = replacement.sequenceNextWaypointCount;
        colorSequenceWaypointsByRole = replacement.colorSequenceWaypointsByRole;
        sequencePreviousWaypointColor = replacement.sequencePreviousWaypointColor;
        sequenceCurrentWaypointColor = replacement.sequenceCurrentWaypointColor;
        sequenceNextWaypointColor = replacement.sequenceNextWaypointColor;
        showTracer = replacement.showTracer;
        dimSequenceContextWaypoints = replacement.dimSequenceContextWaypoints;
        hideTracerOnStaticRoutes = replacement.hideTracerOnStaticRoutes;
        hideWaypointsNearPlayer = replacement.hideWaypointsNearPlayer;
        hideWaypointsNearRadius = replacement.hideWaypointsNearRadius;
        hideWaypointLabelsNearPlayer = replacement.hideWaypointLabelsNearPlayer;
        hideWaypointLabelsNearRadius = replacement.hideWaypointLabelsNearRadius;
        showLabels = replacement.showLabels;
        labelMaxDistance = replacement.labelMaxDistance;
        hideReachedStaticWaypointsUntilCycleComplete = replacement.hideReachedStaticWaypointsUntilCycleComplete;
        skipAheadOnlyVisibleWaypoints = replacement.skipAheadOnlyVisibleWaypoints;
        showRouteLines = replacement.showRouteLines;
        useEtherwarpHeight = replacement.useEtherwarpHeight;
        lineOfSightOnly = replacement.lineOfSightOnly;
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
        showChatCoordBlockButton = replacement.showChatCoordBlockButton;
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
        dungeonWaypointsFeatureEnabled = replacement.dungeonWaypointsFeatureEnabled;
        skipAheadMechanicEnabled = replacement.skipAheadMechanicEnabled;
        crystalHollowsMinimap = replacement.crystalHollowsMinimap;
        crystalHollowsMinimapSize = replacement.crystalHollowsMinimapSize;
        crystalHollowsMinimapCorner = replacement.crystalHollowsMinimapCorner;
        crystalHollowsMinimapLabels = replacement.crystalHollowsMinimapLabels;
        crystalHollowsEnabled = replacement.crystalHollowsEnabled;
        crystalHollowsStructureWaypoints = replacement.crystalHollowsStructureWaypoints;
        crystalHollowsHideStructuresFolder = replacement.crystalHollowsHideStructuresFolder;
        crystalHollowsShowRoughMarkers = replacement.crystalHollowsShowRoughMarkers;
        crystalHollowsEntityDetection = replacement.crystalHollowsEntityDetection;
        crystalHollowsChatDetection = replacement.crystalHollowsChatDetection;
        coordinateShareHelper = replacement.coordinateShareHelper;
        crystalHollowsMetalDetector = replacement.crystalHollowsMetalDetector;
        crystalHollowsRemoteSharing = replacement.crystalHollowsRemoteSharing;
        crystalHollowsWishingCompassSolver = replacement.crystalHollowsWishingCompassSolver;
        crystalHollowsCompassRays = replacement.crystalHollowsCompassRays;
        crystalHollowsAnnounceDetections = replacement.crystalHollowsAnnounceDetections;
        crystalHollowsNucleusWaypoints = replacement.crystalHollowsNucleusWaypoints;
        crystalHollowsCompassAutoFocus = replacement.crystalHollowsCompassAutoFocus;
        crystalHollowsCompassTracers = replacement.crystalHollowsCompassTracers;
        crystalHollowsTracers = replacement.crystalHollowsTracers;
        irisShaderHudFallback = replacement.irisShaderHudFallback;
        tempDefaultMode = replacement.tempDefaultMode;
        tempDefaultDurationSec = replacement.tempDefaultDurationSec;
        migratedDuringLoad = false;
        save();
    }

    public void disableAllSettings() {
        preferredStyleId = "";
        waypointIcon = "NONE";
        routeLineDashed = false;
        tracerEdgeArrow = false;
        labelShowRouteName = false;
        labelShowCoordinates = false;
        labelDetailOnDemand = false;
        preventLabelOverlap = false;
        showWaypointRoleBadge = false;

        renderAntialiasing = false;
        waypointSkipFadeMs = 0;
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
        colorSequenceWaypointsByRole = false;
        showTracer = false;
        dimSequenceContextWaypoints = false;
        hideTracerOnStaticRoutes = false;
        hideWaypointsNearPlayer = false;
        hideWaypointLabelsNearPlayer = false;
        hideReachedStaticWaypointsUntilCycleComplete = false;
        skipAheadOnlyVisibleWaypoints = false;
        showRouteLines = false;
        useEtherwarpHeight = false;
        lineOfSightOnly = false;
        showDungeonEntryPathToFirstWaypoint = false;
        showDungeonEntryPathToFollowingWaypoints = false;
        etherwarpAlignmentSound = "";
        showLabelBackdrop = false;
        showLabelTextShadow = false;
        labelFollowCameraEffects = false;
        beaconBeamExtendsBelowWaypoint = false;
        useBeaconBeamTextures = false;
        editSounds = false;
        showEditModeSubtitle = false;
        chatCoordDetection = false;
        showChatCoordBlockButton = false;
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
        dungeonWaypointsFeatureEnabled = false;
        skipAheadMechanicEnabled = false;
        crystalHollowsMinimap = false;
        crystalHollowsMinimapLabels = false;
        crystalHollowsEnabled = false;
        crystalHollowsStructureWaypoints = false;
        crystalHollowsHideStructuresFolder = false;
        crystalHollowsShowRoughMarkers = false;
        crystalHollowsEntityDetection = false;
        crystalHollowsChatDetection = false;
        coordinateShareHelper = false;
        crystalHollowsMetalDetector = false;
        crystalHollowsRemoteSharing = false;
        crystalHollowsSkyblockerSharing = false;
        crystalHollowsWishingCompassSolver = false;
        crystalHollowsCompassRays = false;
        crystalHollowsAnnounceDetections = false;
        crystalHollowsNucleusWaypoints = false;
        crystalHollowsCompassAutoFocus = false;
        crystalHollowsCompassTracers = false;
        crystalHollowsTracers = false;
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

        preferredStyleId = "";
        crystalHollowsStyles.clear();
        zoneStyles.clear();
        routeMinRenderDistance = defaults.routeMinRenderDistance;
        routeMaxRenderDistance = defaults.routeMaxRenderDistance;
        routeLineWidth = defaults.routeLineWidth;
        routeLineOpacity = defaults.routeLineOpacity;
        routeLineDashed = defaults.routeLineDashed;
        beaconWidthScale = defaults.beaconWidthScale;
        beaconMotionSpeed = defaults.beaconMotionSpeed;
        tracerCenterGap = defaults.tracerCenterGap;
        tracerEdgeArrow = defaults.tracerEdgeArrow;
        labelBackdropOpacity = defaults.labelBackdropOpacity;
        labelShowRouteName = defaults.labelShowRouteName;
        labelShowCoordinates = defaults.labelShowCoordinates;
        labelDetailOnDemand = defaults.labelDetailOnDemand;
        labelDetailDistance = defaults.labelDetailDistance;
        preventLabelOverlap = defaults.preventLabelOverlap;
        waypointIcon = defaults.waypointIcon;
        showWaypointRoleBadge = defaults.showWaypointRoleBadge;
        completedWaypointOpacity = defaults.completedWaypointOpacity;
        currentWaypointOpacity = defaults.currentWaypointOpacity;
        upcomingWaypointOpacity = defaults.upcomingWaypointOpacity;
        sequenceContextOpacity = defaults.sequenceContextOpacity;

        configSchemaVersion = CONFIG_SCHEMA_VERSION;
        defaultReachRadius = defaults.defaultReachRadius;
        editModePlacementReach = defaults.editModePlacementReach;
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
        renderAntialiasing = defaults.renderAntialiasing;
        waypointMarkerScale = defaults.waypointMarkerScale;
        waypointOutlineOpacity = defaults.waypointOutlineOpacity;
        matchWaypointOutlineToWaypointColor = defaults.matchWaypointOutlineToWaypointColor;
        waypointOutlineColor = defaults.waypointOutlineColor;
        beaconOpacity = defaults.beaconOpacity;
        showWaypointNames = defaults.showWaypointNames;
        numberUnnamedWaypoints = defaults.numberUnnamedWaypoints;
        showWaypointDistances = defaults.showWaypointDistances;
        showRouteProgress = defaults.showRouteProgress;
        labelScale = defaults.labelScale;
        labelFollowCameraEffects = defaults.labelFollowCameraEffects;
        labelOpacity = defaults.labelOpacity;
        waypointSkipFadeMs = defaults.waypointSkipFadeMs;
        scaleWaypointTextWithDistance = defaults.scaleWaypointTextWithDistance;
        matchWaypointTextToWaypointColor = defaults.matchWaypointTextToWaypointColor;
        labelTextColor = defaults.labelTextColor;
        labelColorForAllText = defaults.labelColorForAllText;
        sequencePreviousWaypointCount = defaults.sequencePreviousWaypointCount;
        showCurrentSequenceWaypoint = defaults.showCurrentSequenceWaypoint;
        sequenceNextWaypointCount = defaults.sequenceNextWaypointCount;
        colorSequenceWaypointsByRole = defaults.colorSequenceWaypointsByRole;
        sequencePreviousWaypointColor = defaults.sequencePreviousWaypointColor;
        sequenceCurrentWaypointColor = defaults.sequenceCurrentWaypointColor;
        sequenceNextWaypointColor = defaults.sequenceNextWaypointColor;
        showTracer = defaults.showTracer;
        dimSequenceContextWaypoints = defaults.dimSequenceContextWaypoints;
        hideTracerOnStaticRoutes = defaults.hideTracerOnStaticRoutes;
        hideWaypointsNearPlayer = defaults.hideWaypointsNearPlayer;
        hideWaypointsNearRadius = defaults.hideWaypointsNearRadius;
        hideWaypointLabelsNearPlayer = defaults.hideWaypointLabelsNearPlayer;
        hideWaypointLabelsNearRadius = defaults.hideWaypointLabelsNearRadius;
        showLabels = defaults.showLabels;
        labelMaxDistance = defaults.labelMaxDistance;
        hideReachedStaticWaypointsUntilCycleComplete = defaults.hideReachedStaticWaypointsUntilCycleComplete;
        skipAheadOnlyVisibleWaypoints = defaults.skipAheadOnlyVisibleWaypoints;
        showRouteLines = defaults.showRouteLines;
        useEtherwarpHeight = defaults.useEtherwarpHeight;
        lineOfSightOnly = defaults.lineOfSightOnly;
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
        menuOpacity = defaults.menuOpacity;
        chatCoordDetection = defaults.chatCoordDetection;
        showChatCoordBlockButton = defaults.showChatCoordBlockButton;
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
        dungeonWaypointsFeatureEnabled = defaults.dungeonWaypointsFeatureEnabled;
        skipAheadMechanicEnabled = defaults.skipAheadMechanicEnabled;
        crystalHollowsMinimap = defaults.crystalHollowsMinimap;
        crystalHollowsMinimapSize = defaults.crystalHollowsMinimapSize;
        crystalHollowsMinimapCorner = defaults.crystalHollowsMinimapCorner;
        crystalHollowsMinimapLabels = defaults.crystalHollowsMinimapLabels;
        minimapScale = defaults.minimapScale;
        minimapCustomPosition = defaults.minimapCustomPosition;
        minimapPositionX = defaults.minimapPositionX;
        minimapPositionY = defaults.minimapPositionY;
        crystalHollowsDetections.clear();
        crystalHollowsEnabled = defaults.crystalHollowsEnabled;
        crystalHollowsStructureWaypoints = defaults.crystalHollowsStructureWaypoints;
        crystalHollowsHideStructuresFolder = defaults.crystalHollowsHideStructuresFolder;
        crystalHollowsShowRoughMarkers = defaults.crystalHollowsShowRoughMarkers;
        crystalHollowsEntityDetection = defaults.crystalHollowsEntityDetection;
        crystalHollowsChatDetection = defaults.crystalHollowsChatDetection;
        coordinateShareHelper = defaults.coordinateShareHelper;
        crystalHollowsMetalDetector = defaults.crystalHollowsMetalDetector;
        crystalHollowsRemoteSharing = defaults.crystalHollowsRemoteSharing;
        crystalHollowsSkyblockerSharing = defaults.crystalHollowsSkyblockerSharing;
        crystalHollowsWishingCompassSolver = defaults.crystalHollowsWishingCompassSolver;
        crystalHollowsCompassRays = defaults.crystalHollowsCompassRays;
        crystalHollowsAnnounceDetections = defaults.crystalHollowsAnnounceDetections;
        crystalHollowsNucleusWaypoints = defaults.crystalHollowsNucleusWaypoints;
        crystalHollowsCompassAutoFocus = defaults.crystalHollowsCompassAutoFocus;
        crystalHollowsCompassTracers = defaults.crystalHollowsCompassTracers;
        crystalHollowsTracers = defaults.crystalHollowsTracers;
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
