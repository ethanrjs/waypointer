package com.babbur.waypointer.crystal;

import com.babbur.waypointer.Waypointer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where each Fairy Grotto route was last placed, per lobby, in
 * {@code config/waypointer/fairy-grottos.json}. A relog into the same lobby finds the route again
 * even after most of its glass is mined. The layouts themselves live on the routes.
 */
public final class FairyGrottoStore {

    private static final Gson GSON = new GsonBuilder().create();
    private static final int VERSION = 2;
    private static final int MAX_ENTRIES = 512;

    public record LastPlacement(String serverId, StructurePlacement placement) {}

    private final Path file;
    private final Map<String, LastPlacement> entries = new LinkedHashMap<>();

    public FairyGrottoStore(Path file) {
        this.file = file;
    }

    public static FairyGrottoStore loadDefault() {
        FairyGrottoStore store = new FairyGrottoStore(FabricLoader.getInstance().getConfigDir()
                .resolve(Waypointer.MOD_ID).resolve("fairy-grottos.json"));
        store.load();
        return store;
    }

    public LastPlacement lastPlacement(String routeId) {
        return entries.get(routeId);
    }

    public void putLastPlacement(String routeId, String serverId, StructurePlacement placement) {
        if (routeId == null || serverId == null || placement == null) return;
        LastPlacement last = new LastPlacement(serverId, placement);
        if (last.equals(entries.get(routeId))) return;
        entries.remove(routeId);
        entries.put(routeId, last);
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
        save();
    }

    public void load() {
        entries.clear();
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject routes = root.getAsJsonObject("routes");
            if (routes == null) return;
            for (Map.Entry<String, JsonElement> route : routes.entrySet()) {
                JsonObject last = route.getValue().getAsJsonObject().getAsJsonObject("last");
                if (last == null || !last.has("server")) continue;
                entries.put(route.getKey(), new LastPlacement(last.get("server").getAsString(),
                        new StructurePlacement(new CrystalHollowsPosition(last.get("x").getAsInt(),
                                last.get("y").getAsInt(), last.get("z").getAsInt()),
                                last.get("turns").getAsInt())));
            }
        } catch (RuntimeException | IOException invalid) {
            Waypointer.LOGGER.warn("Could not read Fairy Grotto placements from {}", file, invalid);
        }
    }

    String encode() {
        JsonObject routes = new JsonObject();
        for (Map.Entry<String, LastPlacement> route : entries.entrySet()) {
            LastPlacement last = route.getValue();
            JsonObject lastJson = new JsonObject();
            lastJson.addProperty("server", last.serverId());
            lastJson.addProperty("x", last.placement().pivot().x());
            lastJson.addProperty("y", last.placement().pivot().y());
            lastJson.addProperty("z", last.placement().pivot().z());
            lastJson.addProperty("turns", last.placement().quarterTurns());
            JsonObject json = new JsonObject();
            json.add("last", lastJson);
            routes.add(route.getKey(), json);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("routes", routes);
        return GSON.toJson(root);
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, encode(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failed) {
            Waypointer.LOGGER.warn("Could not save Fairy Grotto placements to {}", file, failed);
        }
    }
}
