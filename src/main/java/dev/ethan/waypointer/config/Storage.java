package dev.ethan.waypointer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    public void load(ActiveGroupManager manager) {
        try {
            if (!Files.exists(file)) return;
            String raw = Files.readString(file);
            if (raw.isBlank()) return;
            List<WaypointGroup> groups = parseGroups(raw);
            manager.replaceAll(groups);
            Waypointer.LOGGER.info("Loaded {} waypoint group(s) from {}", groups.size(), file);
        } catch (Exception e) {
            Waypointer.LOGGER.error("Failed to load waypoints from {}", file, e);
        }
    }

    /**
     * Wire the storage up as a data-change listener. The listener-triggered
     * path is the only live-save channel we support -- callers don't invoke
     * {@link #save(ActiveGroupManager)} directly any more. Kept separate from
     * {@link #load} so callers can rehydrate without immediately writing the
     * canonical form back.
     */
    /*[[AI-FN-DOC
Function:
attach
Purpose:
Register this storage instance as the waypoint manager's debounced persistence listener.
Why this exists:
Loading and saving are separate phases: callers can load existing routes first, then attach autosave only after the in-memory state is ready to persist future user changes.
When to use:
Use once the ActiveGroupManager should begin saving future data changes to this storage file. Do not call it before load when you want to avoid immediately canonicalizing an old file.
Inputs:
manager is a non-null ActiveGroupManager whose data-change events occur on the client mutation thread and whose current state can be snapshotted safely there.
Outputs:
No return value. The storage keeps references to the manager and saver for future dirty marks and flushes.
Side effects:
Creates an AsyncSaver, stores the manager reference, captures an initial JSON snapshot, and registers a data listener callback on the manager.
Failure modes:
A null manager would fail when snapshotting or registering the listener. Later save failures are handled by writeToDisk logging IOException.
Important invariants:
The registered listener must capture JSON on the manager mutation thread before AsyncSaver schedules any off-thread write. The saver thread must not iterate live manager/group collections.
Internal logic:
Store the manager reference, capture the current manager state as JSON, construct the debounced saver around writeToDisk, and register markDirtyFromManager as the data listener.
Pseudocode:
Set managerRef to manager.
Set pendingSnapshotJson to snapshotToJson(manager).
Create AsyncSaver named waypoints with writeToDisk and the debounce delay.
Register markDirtyFromManager as the manager data listener.
Implementation notes:
The initial snapshot is cheap and gives flush/writeToDisk a valid payload if a caller forces a write after attach. The listener is a named method so its callback behavior is documented and reusable by save.
AI self-check:
Verify attach no longer registers saver.markDirty directly, snapshotting happens before async scheduling, and no live collection is read from the saver thread.
]]*/
    public void attach(ActiveGroupManager manager) {
        this.managerRef = manager;
        this.pendingSnapshotJson = snapshotToJson(manager);
        this.saver = new AsyncSaver("waypoints", this::writeToDisk, SAVE_DEBOUNCE_MS);
        manager.addDataListener(this::markDirtyFromManager);
    }

    /**
     * Public entrypoint for explicit saves (e.g. tests, one-off writes before
     * {@link #attach} has run). Normal live saves go through the async path
     * driven by {@link #attach}'s listener.
     */
    /*[[AI-FN-DOC
Function:
save
Purpose:
Capture the manager's current route state and either schedule or perform a waypoint-file write.
Why this exists:
Tests and one-off callers need an explicit save path, while attached live saves should still use the debounced AsyncSaver.
When to use:
Use for explicit persistence of an ActiveGroupManager. Do not use for normal live edits after attach unless a caller intentionally wants to mark the current state dirty.
Inputs:
manager is the ActiveGroupManager to persist. It may be the currently attached manager or a one-off manager in tests.
Outputs:
No return value. The method updates pendingSnapshotJson and may schedule or perform a disk write.
Side effects:
Serializes the manager's current non-temp, non-runtime route data into pendingSnapshotJson. If attached to the same manager, marks the saver dirty. Otherwise writes synchronously to disk.
Failure modes:
Snapshot construction can throw runtime exceptions if the manager contains malformed data. Synchronous disk failures are logged by writeToDisk.
Important invariants:
Snapshot creation must happen before markDirty so the background thread never serializes live collections. The attached-manager check must use the previous managerRef before this method updates it.
Internal logic:
Remember whether the manager is already attached to this saver, store the manager reference, capture JSON from the manager, then either schedule the saver or write immediately.
Pseudocode:
Set attachedToSameManager to saver exists and managerRef == manager.
Set managerRef to manager.
Set pendingSnapshotJson to snapshotToJson(manager).
If attachedToSameManager is true, call saver.markDirty and return.
Call writeToDisk synchronously.
Implementation notes:
This preserves the old behavior where save on a non-attached or different manager writes immediately. The JSON string snapshot is immutable and safe to hand across threads.
AI self-check:
Verify the snapshot precedes async scheduling, direct saves still write immediately, and managerRef changes do not make the attached-manager check accidentally always true.
]]*/
    public void save(ActiveGroupManager manager) {
        boolean attachedToSameManager = saver != null && managerRef == manager;
        this.managerRef = manager;
        this.pendingSnapshotJson = snapshotToJson(manager);
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
        if (saver != null) saver.flush();
    }

    /*[[AI-FN-DOC
Function:
markDirtyFromManager
Purpose:
Capture the latest attached manager state and notify the debounced saver that a waypoint write is pending.
Why this exists:
Manager data listeners run on the mutation thread, which is the safe place to iterate live group and waypoint collections before the AsyncSaver crosses onto its background thread.
When to use:
Use only as the ActiveGroupManager data-listener callback installed by attach. Do not call it for one-off direct saves; save handles that path explicitly.
Inputs:
No parameters. Reads managerRef, which attach must have set to the active manager.
Outputs:
No return value. Updates pendingSnapshotJson and marks the saver dirty when both managerRef and saver are available.
Side effects:
Serializes the current attached manager state to an immutable JSON string and schedules a debounced async write.
Failure modes:
If managerRef is null the method returns without work. If saver is null it captures the snapshot but cannot schedule a write. Snapshot runtime failures propagate to the caller/listener.
Important invariants:
Snapshotting must complete before saver.markDirty is called. The saver thread must only see pendingSnapshotJson, never live manager collections.
Internal logic:
Read managerRef into a local variable, return if absent, build the JSON snapshot, then mark the saver dirty if the saver exists.
Pseudocode:
Copy managerRef into manager.
If manager is null, return.
Set pendingSnapshotJson to snapshotToJson(manager).
If saver is not null, call saver.markDirty.
Implementation notes:
Reading managerRef once keeps the snapshot source stable for this callback. The volatile JSON field provides the cross-thread handoff to writeToDisk.
AI self-check:
Verify there is no live collection access after markDirty, null manager is harmless, and the callback does not write synchronously on the mutation thread.
]]*/
    private void markDirtyFromManager() {
        ActiveGroupManager manager = managerRef;
        if (manager == null) return;
        pendingSnapshotJson = snapshotToJson(manager);
        if (saver != null) saver.markDirty();
    }

    /*[[AI-FN-DOC
Function:
writeToDisk
Purpose:
Write the latest captured waypoint JSON snapshot to the storage file with an atomic replace.
Why this exists:
AsyncSaver needs a small writer body that can run off-thread without touching live route collections, and direct saves reuse the same atomic file-write behavior.
When to use:
Use only through AsyncSaver or save after pendingSnapshotJson has been refreshed. Do not call it to serialize live manager state; snapshotToJson owns that work.
Inputs:
No parameters. Reads file and pendingSnapshotJson from this Storage instance.
Outputs:
No return value. On success, the storage file contains pendingSnapshotJson.
Side effects:
Creates the parent directory, writes a temporary file, atomically moves it over the target file, and logs IOException failures.
Failure modes:
If no snapshot is pending, returns without work. Directory creation, temp writes, or atomic moves can throw IOException and are logged.
Important invariants:
This method must not iterate managerRef, allGroups, or group.waypoints because it may run on the background saver thread. It writes exactly the immutable snapshot captured earlier.
Internal logic:
Read pendingSnapshotJson into a local string, return if it is null, ensure the parent directory exists, write the string to a sibling tmp file, and atomically replace the target.
Pseudocode:
Copy pendingSnapshotJson into json.
If json is null, return.
Try to create parent directories.
Build tmp path beside file.
Write json to tmp.
Move tmp to file with replace existing and atomic move.
On IOException, log the failure.
Implementation notes:
The local json copy keeps one write internally consistent even if a newer mutation updates pendingSnapshotJson while this disk write is running.
AI self-check:
Verify the implementation uses only the local snapshot string, preserves atomic replace semantics, and logs but does not throw IO failures from the async path.
]]*/
    private void writeToDisk() {
        String json = pendingSnapshotJson;
        if (json == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Waypointer.LOGGER.error("Failed to save waypoints to {}", file, e);
        }
    }

    /*[[AI-FN-DOC
Function:
snapshotToJson
Purpose:
Serialize the manager's current persisted waypoint groups into a detached JSON string snapshot.
Why this exists:
Storage must cross from client-thread mutations to a background disk writer without carrying live collections across that thread boundary.
When to use:
Use immediately on the manager mutation thread before scheduling or performing a save. Do not use from the AsyncSaver writer thread.
Inputs:
manager is the ActiveGroupManager to snapshot. A null manager produces null so legacy null-save behavior remains a no-op.
Outputs:
Returns a JSON string containing schema and persisted groups, or null when manager is null.
Side effects:
Allocates a JsonObject, JsonArray, and JSON string. Reads manager/group/waypoint state but does not mutate it or write files.
Failure modes:
Malformed live model values can surface as runtime exceptions from JSON conversion. Temp and runtime-only groups are intentionally skipped.
Important invariants:
Only non-temp and non-runtime groups are included. Temporary waypoints are still filtered inside groupToJson. The returned String is immutable and safe for background writing.
Internal logic:
Return null for null manager. Build the root object with schema. Iterate all groups while still on the mutation thread, add persisted groups through groupToJson, attach the group array, and stringify with GSON.
Pseudocode:
If manager is null, return null.
Create root object.
Add schema property.
Create groups array.
For each group in manager.allGroups:
If group is not temp and not runtimeOnly, add groupToJson(group).
Add groups array to root.
Return GSON.toJson(root).
Implementation notes:
This deliberately reuses groupToJson so storage filtering stays in one place. The important change is when it runs: before AsyncSaver schedules the off-thread write.
AI self-check:
Verify persisted group filtering matches the previous writeToDisk behavior, the output is detached from live collections, and null input remains a no-op.
]]*/
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

    // --- JSON codec -----------------------------------------------------------------

    private static List<WaypointGroup> parseGroups(String raw) {
        JsonElement parsed = GSON.fromJson(raw, JsonElement.class);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("waypoints root must be a JSON object");
        }

        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("groups")) return List.of();

        JsonElement groupsElement = root.get("groups");
        if (groupsElement == null || groupsElement.isJsonNull() || !groupsElement.isJsonArray()) {
            throw new IllegalArgumentException("waypoints groups must be a JSON array");
        }

        JsonArray groupsJson = groupsElement.getAsJsonArray();
        List<WaypointGroup> groups = new ArrayList<>(groupsJson.size());
        for (JsonElement el : groupsJson) {
            if (el == null || !el.isJsonObject()) {
                throw new IllegalArgumentException("waypoint group entry must be a JSON object");
            }
            groups.add(groupFromJson(el.getAsJsonObject()));
        }
        return groups;
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
        o.addProperty("staticColor", g.staticColor());
        // Per-group gradient endpoints. Stored as ints rather than hex strings
        // because the rest of the waypoint colour fields are already ints -- one
        // less parser branch in load().
        o.addProperty("gradientStartColor", g.gradientStartColor());
        o.addProperty("gradientEndColor",   g.gradientEndColor());
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
        // Gradient endpoints were added after schema v1 so both fields are optional;
        // missing values leave the group on its built-in cyan/red defaults.
        if (o.has("gradientStartColor")) g.setGradientStartColor(o.get("gradientStartColor").getAsInt());
        if (o.has("gradientEndColor"))   g.setGradientEndColor(o.get("gradientEndColor").getAsInt());
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

    private static <E extends Enum<E>> Optional<E> parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(Enum.valueOf(type, raw.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
