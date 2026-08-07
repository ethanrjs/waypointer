package com.babbur.waypointer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and writes the user's waypoint groups as JSON at
 * {@code <config>/waypointer/waypoints.json}.
 *
 * Intentionally hand-written (not Gson auto-binding) so we can evolve the schema
 * without breaking on field renames and can version the file. Saves are atomic:
 * write to .tmp, then move. That prevents a crash mid-write from nuking the user's
 * entire route library.
 */
public final class Storage {

    public static final int SCHEMA_VERSION = 1;
    private static final String FILE_NAME = "waypoints.json";

    /**
     * Quiet window before a dirty marker triggers a disk write. Waypoint
     * mutations clump hard -- dragging to reorder fires a listener per swap,
     * gradient repaint fires once per waypoint, bulk import fires once per
     * waypoint. Debouncing collapses these into one write per intent while
     * still feeling instant to the user.
     */
    private static final long SAVE_DEBOUNCE_MS = 400L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private AsyncSaver saver;
    private ActiveGroupManager managerRef;
    private volatile String pendingSnapshotJson;
    /**
     * Set when the manager reports a persistent change, cleared by
     * {@link #pumpPendingSnapshot()}. Serializing the whole library is O(all
     * waypoints), so doing it inline on every data-changed event made bulk
     * operations quadratic -- hiding a hundred routes or closing the route list
     * re-serialized the library once per affected route. The flag defers that
     * work to at most once per client tick.
     */
    private boolean snapshotStale;
    private int snapshotCount;
    private volatile int writeCount;
    private volatile boolean writesBlocked;
    private int pendingCanonicalRewriteGroups;

    public Storage(Path file) {
        this.file = file;
    }

    public static Storage defaultLocation() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(Waypointer.MOD_ID);
        return new Storage(dir.resolve(FILE_NAME));
    }

    public Path file() {
        return file;
    }

    /** Read-only storage health used by the troubleshooting report. */
    public DebugSnapshot debugSnapshot() {
        boolean exists = Files.isRegularFile(file);
        long size = -1L;
        long modifiedAtMillis = -1L;
        if (exists) {
            try {
                size = Files.size(file);
                modifiedAtMillis = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException ignored) {
                // The status flags remain useful even if metadata races a file replacement.
            }
        }
        return new DebugSnapshot(file.getFileName().toString(), exists, size, modifiedAtMillis,
                saver != null, writesBlocked, pendingSnapshotJson != null, snapshotCount, writeCount);
    }

    public record DebugSnapshot(String fileName, boolean exists, long sizeBytes,
                                long modifiedAtMillis, boolean attached,
                                boolean writesBlocked, boolean snapshotReady,
                                int snapshotsCaptured, int writesCompleted) {
    }

    public void load(ActiveGroupManager manager) {
        pendingCanonicalRewriteGroups = 0;
        if (!Files.exists(file)) return;

        String raw;
        try {
            raw = Files.readString(file);
        } catch (IOException e) {
            writesBlocked = true;
            Waypointer.LOGGER.error("Failed to load waypoints from {}", file, e);
            return;
        }

        ParsedGroups parsedGroups;
        try {
            parsedGroups = parseGroups(raw);
        } catch (RuntimeException e) {
            quarantineInvalidFile(e);
            return;
        }

        writesBlocked = false;
        pendingCanonicalRewriteGroups = parsedGroups.canonicalizedZoneCount();
        manager.replaceAll(parsedGroups.groups());
        Waypointer.LOGGER.info("Loaded {} waypoint group(s) from {}", parsedGroups.groups().size(), file);
    }

    /**
     * Wire storage to persistent data changes. Transient temp markers, API
     * overlays, and generated dungeon mirrors still invalidate render/API data
     * listeners, but never serialize the user's route library. The persistent
     * listener path is the only live-save channel -- callers don't invoke
     * {@link #save(ActiveGroupManager)} directly any more. Kept separate from
     * {@link #load} so callers can rehydrate before the listener is active.
     * If loading canonicalized legacy zone IDs, attaching schedules one atomic
     * rewrite of the migrated library.
     */
    public void attach(ActiveGroupManager manager) {
        this.managerRef = manager;
        this.pendingSnapshotJson = captureSnapshot(manager);
        this.saver = new AsyncSaver("waypoints", this::writeToDisk, SAVE_DEBOUNCE_MS);
        manager.addPersistentDataListener(this::markDirtyFromManager);
        if (pendingCanonicalRewriteGroups > 0) {
            Waypointer.LOGGER.info("Migrating zone IDs for {} waypoint group(s) in {}",
                    pendingCanonicalRewriteGroups, file);
            pendingCanonicalRewriteGroups = 0;
            saver.markDirty();
        }
    }

    /**
     * Public entrypoint for explicit saves (e.g. tests, one-off writes before
     * {@link #attach} has run). Normal live saves go through the async path
     * driven by {@link #attach}'s listener.
     */
    public void save(ActiveGroupManager manager) {
        boolean attachedToSameManager = saver != null && managerRef == manager;
        this.managerRef = manager;
        this.pendingSnapshotJson = captureSnapshot(manager);
        if (attachedToSameManager) {
            saver.markDirty();
            return;
        }
        writeToDisk();
    }

    /**
     * Synchronously flush any pending waypoint write. Called on client
     * shutdown so an atomic rename in flight lands before the JVM exits.
     */
    public void flush() {
        pumpPendingSnapshot();
        if (saver != null) saver.flush();
    }

    /**
     * Serialize the library if anything changed since the last pump, then arm
     * the debounced write. Must be called from the thread that mutates the
     * manager (the client thread) -- that is the whole reason the snapshot is
     * taken here rather than on the saver thread.
     *
     * <p>Cheap and safe to call every tick: with no pending change it does
     * nothing at all.
     */
    public void pumpPendingSnapshot() {
        if (!snapshotStale) return;
        ActiveGroupManager manager = managerRef;
        if (manager == null) {
            snapshotStale = false;
            return;
        }
        snapshotStale = false;
        pendingSnapshotJson = captureSnapshot(manager);
        if (saver != null) saver.markDirty();
    }

    private void markDirtyFromManager() {
        if (managerRef == null) return;
        // Deliberately does not serialize: a burst of changes costs one flag
        // write, and the next pump pays for a single snapshot covering them all.
        snapshotStale = true;
    }

    private void writeToDisk() {
        String json = pendingSnapshotJson;
        if (json == null || writesBlocked) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            writeCount++;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save waypoints to " + file, e);
        }
    }

    private void quarantineInvalidFile(RuntimeException cause) {
        writesBlocked = true;
        Path quarantine = file.resolveSibling(file.getFileName() + ".invalid");
        int suffix = 1;
        while (Files.exists(quarantine)) {
            quarantine = file.resolveSibling(file.getFileName() + ".invalid." + suffix++);
        }

        try {
            Files.move(file, quarantine);
            writesBlocked = false;
            Waypointer.LOGGER.error("Invalid waypoint data moved from {} to {}", file, quarantine, cause);
        } catch (IOException quarantineFailure) {
            writesBlocked = true;
            cause.addSuppressed(quarantineFailure);
            Waypointer.LOGGER.error(
                    "Invalid waypoint data at {} could not be quarantined; saves are disabled to prevent data loss",
                    file, cause);
        }
    }

    private static String snapshotToJson(ActiveGroupManager manager) {
        if (manager == null) return null;
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_VERSION);
        JsonArray groups = new JsonArray();
        for (WaypointGroup g : manager.allGroups()) {
            if (!g.temp() && !g.runtimeOnly()) groups.add(groupToJson(g));
        }
        root.add("groups", groups);
        return GSON.toJson(root);
    }

    private String captureSnapshot(ActiveGroupManager manager) {
        snapshotCount++;
        return snapshotToJson(manager);
    }

    int snapshotCount() {
        return snapshotCount;
    }

    int writeCount() {
        return writeCount;
    }

    // --- JSON codec -----------------------------------------------------------------

    private record ParsedGroups(List<WaypointGroup> groups, int canonicalizedZoneCount) {
    }

    private static ParsedGroups parseGroups(String raw) {
        if (raw.isBlank()) {
            throw new IllegalArgumentException("waypoints file must not be blank");
        }
        JsonElement parsed = GSON.fromJson(raw, JsonElement.class);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("waypoints root must be a JSON object");
        }

        JsonObject root = parsed.getAsJsonObject();
        JsonElement schemaElement = root.get("schema");
        if (schemaElement == null
                || schemaElement.isJsonNull()
                || !schemaElement.isJsonPrimitive()
                || !schemaElement.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("waypoints schema must be " + SCHEMA_VERSION);
        }
        int schema = schemaElement.getAsBigDecimal().intValueExact();
        if (schema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported waypoints schema " + schema);
        }

        if (!root.has("groups")) {
            throw new IllegalArgumentException("waypoints groups are missing");
        }

        JsonElement groupsElement = root.get("groups");
        if (groupsElement == null || groupsElement.isJsonNull() || !groupsElement.isJsonArray()) {
            throw new IllegalArgumentException("waypoints groups must be a JSON array");
        }

        JsonArray groupsJson = groupsElement.getAsJsonArray();
        List<WaypointGroup> groups = new ArrayList<>(groupsJson.size());
        Set<String> groupIds = new HashSet<>(groupsJson.size());
        int canonicalizedZoneCount = 0;
        for (JsonElement el : groupsJson) {
            if (el == null || !el.isJsonObject()) {
                throw new IllegalArgumentException("waypoint group entry must be a JSON object");
            }
            JsonObject groupJson = el.getAsJsonObject();
            String storedZone = groupJson.has("zone")
                    ? groupJson.get("zone").getAsString()
                    : "unknown";
            WaypointGroup group = groupFromJson(groupJson);
            if (!group.zoneId().equals(storedZone)) canonicalizedZoneCount++;
            if (!groupIds.add(group.id())) {
                throw new IllegalArgumentException("duplicate waypoint group id " + group.id());
            }
            groups.add(group);
        }
        return new ParsedGroups(List.copyOf(groups), canonicalizedZoneCount);
    }

        static JsonObject groupToJson(WaypointGroup g) {
        JsonObject o = new JsonObject();
        o.addProperty("id", g.id());
        o.addProperty("name", g.name());
        o.addProperty("zone", g.zoneId());
        o.addProperty("enabled", g.enabled());
        o.addProperty("currentIndex", g.currentIndex());
        o.addProperty("gradientMode", g.gradientMode().name());
        o.addProperty("loadMode", g.loadMode().name());
        o.addProperty("defaultRadius", g.defaultRadius());
        o.addProperty("skipAheadEnabled", g.skipAheadEnabled());
        if (g.bestTimeMillis() >= 0L) o.addProperty("bestTimeMillis", g.bestTimeMillis());
        o.addProperty("staticColor", g.staticColor());
        // Per-group gradient endpoints. Stored as ints rather than hex strings
        // because the rest of the waypoint colour fields are already ints -- one
        // less parser branch in load().
        o.addProperty("gradientStartColor", g.gradientStartColor());
        o.addProperty("gradientEndColor",   g.gradientEndColor());
        o.addProperty("paintEnabled", g.paintEnabled());
        if (g.paint() != null) o.add("paint", paintToJson(g.paint()));
        JsonArray wps = new JsonArray();
        for (Waypoint w : g.waypoints()) {
            // Temporary waypoints are client-session ephemeral by contract.
            // Skipping them here is the single authoritative filter -- there is
            // no separate "before save" pass to keep in sync.
            if (w.isTemp()) continue;
            wps.add(waypointToJson(w));
        }
        o.add("waypoints", wps);
        return o;
    }

        static WaypointGroup groupFromJson(JsonObject o) {
        String id = o.has("id") ? o.get("id").getAsString() : java.util.UUID.randomUUID().toString();
        String name = o.has("name") ? o.get("name").getAsString() : "";
        String zone = o.has("zone") ? o.get("zone").getAsString() : "unknown";
        WaypointGroup g = new WaypointGroup(id, name, zone);
        if (o.has("enabled"))       g.setEnabled(o.get("enabled").getAsBoolean());
        if (o.has("defaultRadius")) g.setDefaultRadius(o.get("defaultRadius").getAsDouble());
        if (o.has("staticColor"))   g.setStaticColor(o.get("staticColor").getAsInt());
        if (o.has("gradientMode")) parseEnum(WaypointGroup.GradientMode.class,
                o.get("gradientMode").getAsString()).ifPresent(g::setGradientMode);
        if (o.has("loadMode")) parseEnum(WaypointGroup.LoadMode.class,
                o.get("loadMode").getAsString()).ifPresent(g::setLoadMode);
        if (o.has("skipAheadEnabled")) g.setSkipAheadEnabled(o.get("skipAheadEnabled").getAsBoolean());
        if (o.has("bestTimeMillis")) g.setBestTimeMillis(o.get("bestTimeMillis").getAsLong());
        // Gradient endpoints were added after schema v1 so both fields are optional;
        // missing values leave the group on its built-in cyan/red defaults.
        if (o.has("gradientStartColor")) g.setGradientStartColor(o.get("gradientStartColor").getAsInt());
        if (o.has("gradientEndColor"))   g.setGradientEndColor(o.get("gradientEndColor").getAsInt());
        if (o.has("paintEnabled")) g.setPaintEnabled(o.get("paintEnabled").getAsBoolean());
        if (o.has("paint") && !o.get("paint").isJsonNull()) {
            try {
                g.setPaint(paintFromJson(o.getAsJsonObject("paint")));
            } catch (RuntimeException invalidPaint) {
                // Paint is optional presentation metadata. A damaged texture must
                // not quarantine an otherwise valid route library and risk hiding
                // every waypoint from the user.
                Waypointer.LOGGER.warn("Ignoring invalid waypoint paint for group {}", id, invalidPaint);
            }
        }
        if (o.has("waypoints")) {
            List<Waypoint> waypoints = new ArrayList<>(o.getAsJsonArray("waypoints").size());
            for (JsonElement el : o.getAsJsonArray("waypoints")) {
                waypoints.add(waypointFromJson(el.getAsJsonObject()));
            }
            g.addAll(waypoints);
        }
        if (o.has("currentIndex")) g.setCurrentIndex(o.get("currentIndex").getAsInt());
        return g;
    }

    static JsonObject waypointToJson(Waypoint w) {
        JsonObject o = new JsonObject();
        o.addProperty("x", w.x());
        o.addProperty("y", w.y());
        o.addProperty("z", w.z());
        if (w.hasName())            o.addProperty("name", w.name());
        o.addProperty("color", w.color());
        if (w.flags() != 0)          o.addProperty("flags", w.flags());
        if (w.customRadius() > 0)    o.addProperty("radius", w.customRadius());
        if (w.hasCustomPrecisePosition()) {
            o.addProperty("preciseX", w.preciseX());
            o.addProperty("preciseY", w.preciseY());
            o.addProperty("preciseZ", w.preciseZ());
        }
        return o;
    }

    static Waypoint waypointFromJson(JsonObject o) {
        int x = o.get("x").getAsInt();
        int y = o.get("y").getAsInt();
        int z = o.get("z").getAsInt();
        String name  = o.has("name")   ? o.get("name").getAsString()   : "";
        int color    = o.has("color")  ? o.get("color").getAsInt()     : Waypoint.DEFAULT_COLOR;
        int flags    = o.has("flags")  ? o.get("flags").getAsInt()     : 0;
        double rad   = o.has("radius") ? o.get("radius").getAsDouble() : 0.0;
        Waypoint base = new Waypoint(x, y, z, name, color, flags, rad);
        int preciseX = o.has("preciseX") ? o.get("preciseX").getAsInt() : base.preciseX();
        int preciseY = o.has("preciseY") ? o.get("preciseY").getAsInt() : base.preciseY();
        int preciseZ = o.has("preciseZ") ? o.get("preciseZ").getAsInt() : base.preciseZ();
        return base.withPreciseSixteenths(preciseX, preciseY, preciseZ);
    }

    static JsonObject paintToJson(WaypointPaint paint) {
        JsonObject out = new JsonObject();
        JsonArray palette = new JsonArray();
        for (int color : paint.paletteCopy()) palette.add(color);
        out.add("palette", palette);
        out.addProperty("pixels", paint.pixelsBase64());
        return out;
    }

    static WaypointPaint paintFromJson(JsonObject o) {
        if (o == null || !o.has("palette") || !o.get("palette").isJsonArray()) {
            throw new IllegalArgumentException("waypoint paint palette is missing");
        }
        JsonArray paletteJson = o.getAsJsonArray("palette");
        if (paletteJson.size() != WaypointPaint.PALETTE_SIZE) {
            throw new IllegalArgumentException("waypoint paint palette must contain 16 colors");
        }
        int[] palette = new int[WaypointPaint.PALETTE_SIZE];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = paletteJson.get(i).getAsInt();
        }
        if (!o.has("pixels") || !o.get("pixels").isJsonPrimitive()) {
            throw new IllegalArgumentException("waypoint paint pixels are missing");
        }
        return new WaypointPaint(palette,
                WaypointPaint.decodePixels(o.get("pixels").getAsString()));
    }

    private static <E extends Enum<E>> Optional<E> parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(Enum.valueOf(type, raw.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
