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
            JsonObject root = GSON.fromJson(raw, JsonObject.class);
            manager.clear();
            JsonArray groups = root.has("groups") ? root.getAsJsonArray("groups") : new JsonArray();
            for (JsonElement el : groups) {
                manager.add(groupFromJson(el.getAsJsonObject()));
            }
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
    public void attach(ActiveGroupManager manager) {
        this.managerRef = manager;
        this.saver = new AsyncSaver("waypoints", this::writeToDisk, SAVE_DEBOUNCE_MS);
        manager.addDataListener(saver::markDirty);
    }

    /**
     * Public entrypoint for explicit saves (e.g. tests, one-off writes before
     * {@link #attach} has run). Normal live saves go through the async path
     * driven by {@link #attach}'s listener.
     */
    public void save(ActiveGroupManager manager) {
        if (saver != null && managerRef == manager) {
            saver.markDirty();
            return;
        }
        this.managerRef = manager;
        writeToDisk();
    }

    /**
     * Synchronously flush any pending waypoint write. Called on client
     * shutdown so an atomic rename in flight lands before the JVM exits.
     */
    public void flush() {
        if (saver != null) saver.flush();
    }

    private void writeToDisk() {
        if (managerRef == null) return;
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("schema", SCHEMA_VERSION);
            JsonArray groups = new JsonArray();
            for (WaypointGroup g : managerRef.allGroups()) groups.add(groupToJson(g));
            root.add("groups", groups);

            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Waypointer.LOGGER.error("Failed to save waypoints to {}", file, e);
        }
    }

    // --- JSON codec -----------------------------------------------------------------

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
        if (o.has("gradientMode")) {
            try { g.setGradientMode(WaypointGroup.GradientMode.valueOf(o.get("gradientMode").getAsString())); }
            catch (IllegalArgumentException ignored) {}
        }
        if (o.has("loadMode")) {
            try { g.setLoadMode(WaypointGroup.LoadMode.valueOf(o.get("loadMode").getAsString())); }
            catch (IllegalArgumentException ignored) {}
        }
        // Gradient endpoints were added after schema v1 so both fields are optional;
        // missing values leave the group on its built-in cyan/red defaults.
        if (o.has("gradientStartColor")) g.setGradientStartColor(o.get("gradientStartColor").getAsInt());
        if (o.has("gradientEndColor"))   g.setGradientEndColor(o.get("gradientEndColor").getAsInt());
        if (o.has("waypoints")) {
            for (JsonElement el : o.getAsJsonArray("waypoints")) {
                g.add(waypointFromJson(el.getAsJsonObject()));
            }
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
        return new Waypoint(x, y, z, name, color, flags, rad);
    }
}
