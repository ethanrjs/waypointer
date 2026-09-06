package com.babbur.waypointer.crystal;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.config.AsyncSaver;
import com.babbur.waypointer.crystal.compass.Crystal;
import com.babbur.waypointer.crystal.compass.CrystalState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import net.fabricmc.loader.api.FabricLoader;

/** Debounced per-lobby persistence for Crystal Hollows sightings. */
public final class CrystalHollowsStore {

    public static final int SCHEMA_VERSION = 1;
    public static final long SAVE_DEBOUNCE_MILLIS = 500L;
    private static final long MIN_EXPIRY_MILLIS = 30L * 60L * 1_000L;
    private static final long DAY_MILLIS = 20L * 60L * 1_000L;
    private static final String FILE_NAME = "crystal_hollows.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final LongSupplier clock;
    private final Map<String, CrystalHollowsLobbyState> lobbies = new LinkedHashMap<>();
    private final AsyncSaver saver;
    private volatile String pendingSnapshot;
    private volatile boolean writesBlockedForFutureSchema;
    private volatile IOException writeBlockCause;

    public static CrystalHollowsStore loadDefault() {
        Path directory = FabricLoader.getInstance().getConfigDir().resolve(Waypointer.MOD_ID);
        CrystalHollowsStore store = new CrystalHollowsStore(
                directory.resolve(FILE_NAME), System::currentTimeMillis);
        store.load();
        return store;
    }

    CrystalHollowsStore(Path file) {
        this(file, System::currentTimeMillis);
    }

    CrystalHollowsStore(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
        this.saver = new AsyncSaver("crystal-hollows", this::writePendingSnapshot,
                SAVE_DEBOUNCE_MILLIS);
    }

    public Path file() { return file; }
    public Map<String, CrystalHollowsLobbyState> lobbies() { return Map.copyOf(lobbies); }

    public void load() {
        lobbies.clear();
        if (!Files.exists(file)) return;
        try {
            parse(Files.readString(file));
            pruneExpired(clock.getAsLong());
        } catch (UnsupportedFutureSchemaException future) {
            lobbies.clear();
            writesBlockedForFutureSchema = true;
            Waypointer.LOGGER.error(
                    "Crystal Hollows data at {} uses newer schema version {} (current {}); using empty state for this session and blocking writes to preserve the original file",
                    file, future.schemaVersion, SCHEMA_VERSION);
        } catch (Exception failure) {
            lobbies.clear();
            writeBlockCause = quarantine(failure);
        }
    }

    public Optional<CrystalHollowsLobbyState> restore(String serverId, int currentDay) {
        CrystalHollowsLobbyState state = lobbies.get(serverId);
        if (state == null) return Optional.empty();
        long now = clock.getAsLong();
        if (expiresAtMillis(state) < now
                || currentDay >= 0 && currentDay + 1 < state.lastKnownDay()) {
            lobbies.remove(serverId);
            save();
            return Optional.empty();
        }
        state.touch(now, currentDay);
        return Optional.of(state);
    }

    public CrystalHollowsLobbyState getOrCreate(String serverId, int currentDay) {
        Optional<CrystalHollowsLobbyState> restored = restore(serverId, currentDay);
        if (restored.isPresent()) return restored.orElseThrow();
        long now = clock.getAsLong();
        CrystalHollowsLobbyState state = new CrystalHollowsLobbyState(serverId, now, currentDay);
        lobbies.put(serverId, state);
        save();
        return state;
    }

    public void put(CrystalHollowsLobbyState state) {
        lobbies.put(state.serverId(), state);
        save();
    }

    public void remove(String serverId) {
        if (lobbies.remove(serverId) != null) save();
    }

    public void save() {
        if (writesBlockedForFutureSchema) return;
        pruneExpired(clock.getAsLong());
        pendingSnapshot = encode();
        saver.markDirty();
    }

    public void flush() {
        if (writesBlockedForFutureSchema) return;
        pruneExpired(clock.getAsLong());
        pendingSnapshot = encode();
        saver.markDirty();
        saver.flush();
    }

    public void discardPendingSave() {
        saver.discard();
    }

    public static long expiresAtMillis(CrystalHollowsLobbyState state) {
        long remainingDays = 35L - Math.max(state.lastKnownDay(), 0);
        long lifetime = Math.max(MIN_EXPIRY_MILLIS, remainingDays * DAY_MILLIS);
        return state.lastSeenMillis() + lifetime;
    }

    private void pruneExpired(long nowMillis) {
        lobbies.values().removeIf(state -> expiresAtMillis(state) < nowMillis);
    }

    private void parse(String raw) {
        JsonElement parsed = GSON.fromJson(raw, JsonElement.class);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("Crystal Hollows root must be an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        int schemaVersion = root.has("schema") ? root.get("schema").getAsInt() : 0;
        if (schemaVersion > SCHEMA_VERSION) {
            throw new UnsupportedFutureSchemaException(schemaVersion);
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported Crystal Hollows schema");
        }
        JsonElement lobbyElement = root.get("lobbies");
        if (lobbyElement == null || !lobbyElement.isJsonObject()) {
            throw new IllegalArgumentException("Crystal Hollows lobbies must be an object");
        }
        for (Map.Entry<String, JsonElement> entry : lobbyElement.getAsJsonObject().entrySet()) {
            CrystalHollowsLobbyState state = decodeLobby(entry.getValue().getAsJsonObject());
            if (!entry.getKey().equals(state.serverId())) {
                throw new IllegalArgumentException("lobby key and serverId differ");
            }
            lobbies.put(entry.getKey(), state);
        }
    }

    private static CrystalHollowsLobbyState decodeLobby(JsonObject json) {
        String serverId = json.get("serverId").getAsString();
        long firstSeen = json.get("firstSeenMillis").getAsLong();
        long lastSeen = json.get("lastSeenMillis").getAsLong();
        int lastDay = json.get("lastKnownDay").getAsInt();
        List<StructureSighting> sightings = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("sightings")) {
            JsonObject sighting = element.getAsJsonObject();
            List<CrystalHollowsStructure> candidates = new ArrayList<>();
            for (JsonElement candidate : sighting.getAsJsonArray("candidates")) {
                candidates.add(CrystalHollowsStructure.valueOf(candidate.getAsString()));
            }
            sightings.add(new StructureSighting(
                    CrystalHollowsStructure.valueOf(sighting.get("structure").getAsString()),
                    sighting.get("x").getAsInt(), sighting.get("y").getAsInt(),
                    sighting.get("z").getAsInt(),
                    SightingConfidence.valueOf(sighting.get("confidence").getAsString()),
                    sighting.get("source").getAsString(), sighting.get("atMillis").getAsLong(),
                    candidates, sighting.get("note").getAsString()));
        }
        // Older builds mistook the shared grunt profile name for a confirmed boss sighting.
        sightings.removeIf(sighting -> sighting.structure() == CrystalHollowsStructure.CORLEONE
                && sighting.confidence() == SightingConfidence.ENTITY
                && "entity:team treasurite".equals(CrystalHollowsSidebar.normalizeName(sighting.source())));
        EnumMap<Crystal, CrystalState> crystals = new EnumMap<>(Crystal.class);
        JsonObject crystalJson = json.getAsJsonObject("crystals");
        for (Map.Entry<String, JsonElement> entry : crystalJson.entrySet()) {
            crystals.put(Crystal.valueOf(entry.getKey()), CrystalState.valueOf(entry.getValue().getAsString()));
        }
        CrystalHollowsPosition divan = null;
        JsonElement divanElement = json.get("divanCentre");
        if (divanElement != null && !divanElement.isJsonNull()) {
            JsonObject position = divanElement.getAsJsonObject();
            divan = new CrystalHollowsPosition(position.get("x").getAsInt(),
                    position.get("y").getAsInt(), position.get("z").getAsInt());
        }
        return new CrystalHollowsLobbyState(serverId, firstSeen, lastSeen, lastDay,
                sightings, crystals, divan);
    }

    private String encode() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_VERSION);
        JsonObject encodedLobbies = new JsonObject();
        for (CrystalHollowsLobbyState state : lobbies.values()) {
            encodedLobbies.add(state.serverId(), encodeLobby(state));
        }
        root.add("lobbies", encodedLobbies);
        return GSON.toJson(root);
    }

    private static JsonObject encodeLobby(CrystalHollowsLobbyState state) {
        JsonObject json = new JsonObject();
        json.addProperty("serverId", state.serverId());
        json.addProperty("firstSeenMillis", state.firstSeenMillis());
        json.addProperty("lastSeenMillis", state.lastSeenMillis());
        json.addProperty("lastKnownDay", state.lastKnownDay());
        json.addProperty("expiresAtMillis", expiresAtMillis(state));
        JsonArray sightings = new JsonArray();
        for (StructureSighting sighting : state.localSightings()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("structure", sighting.structure().name());
            encoded.addProperty("x", sighting.x());
            encoded.addProperty("y", sighting.y());
            encoded.addProperty("z", sighting.z());
            encoded.addProperty("confidence", sighting.confidence().name());
            encoded.addProperty("source", sighting.source());
            encoded.addProperty("atMillis", sighting.atMillis());
            JsonArray candidates = new JsonArray();
            for (CrystalHollowsStructure candidate : sighting.candidates()) {
                candidates.add(candidate.name());
            }
            encoded.add("candidates", candidates);
            encoded.addProperty("note", sighting.note());
            sightings.add(encoded);
        }
        json.add("sightings", sightings);
        JsonObject crystals = new JsonObject();
        for (Map.Entry<Crystal, CrystalState> entry : state.crystals().entrySet()) {
            crystals.addProperty(entry.getKey().name(), entry.getValue().name());
        }
        json.add("crystals", crystals);
        if (state.divanCentre() == null) {
            json.add("divanCentre", null);
        } else {
            JsonObject position = new JsonObject();
            position.addProperty("x", state.divanCentre().x());
            position.addProperty("y", state.divanCentre().y());
            position.addProperty("z", state.divanCentre().z());
            json.add("divanCentre", position);
        }
        return json;
    }

    private void writePendingSnapshot() {
        if (writesBlockedForFutureSchema) return;
        if (writeBlockCause != null) {
            IOException retryFailure = quarantine(writeBlockCause);
            if (retryFailure != null) {
                writeBlockCause = retryFailure;
                throw new UncheckedIOException(
                        "Cannot save Crystal Hollows data until the invalid file is preserved: "
                                + file,
                        retryFailure);
            }
            writeBlockCause = null;
        }
        String json = pendingSnapshot;
        if (json == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to save Crystal Hollows data to " + file, failure);
        }
    }

    private static final class UnsupportedFutureSchemaException extends IllegalArgumentException {
        private final int schemaVersion;

        private UnsupportedFutureSchemaException(int schemaVersion) {
            super("Crystal Hollows schema version " + schemaVersion
                    + " is newer than supported version " + SCHEMA_VERSION);
            this.schemaVersion = schemaVersion;
        }
    }

    private IOException quarantine(Exception cause) {
        Path invalid = file.resolveSibling(file.getFileName() + ".invalid");
        int suffix = 1;
        while (Files.exists(invalid)) {
            invalid = file.resolveSibling(file.getFileName() + ".invalid." + suffix++);
        }
        try {
            Files.move(file, invalid);
            Waypointer.LOGGER.error("Invalid Crystal Hollows data moved from {} to {}", file, invalid, cause);
            return null;
        } catch (IOException moveFailure) {
            if (Files.notExists(file)) return null;
            Waypointer.LOGGER.error(
                    "Invalid Crystal Hollows data at {} could not be preserved; saves are blocked to prevent data loss",
                    file, moveFailure);
            return moveFailure;
        }
    }
}
