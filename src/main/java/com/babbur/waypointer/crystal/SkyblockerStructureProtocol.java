package com.babbur.waypointer.crystal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

public final class SkyblockerStructureProtocol {
    public static final String SOURCE = "skyblocker";
    public static final String SERVICE = "CRYSTAL_WAYPOINTS";

    private SkyblockerStructureProtocol() {}

    public static long closesAt(int day, long now) {
        if (day < 0 || day >= 26 || now < 0) throw new IllegalArgumentException("Unknown lobby lifetime");
        return Math.addExact(now / 1000, (26L - day) * 1200);
    }

    public static String subscribe(String server, int day, long now) {
        JsonObject payload = envelope("subscribe", server);
        JsonObject message = new JsonObject();
        message.addProperty("timestamp", closesAt(day, now));
        payload.add("message", message);
        return payload.toString();
    }

    public static String publish(String server, StructureSighting sighting, long sessionStarted, long now) {
        if (!StructureRelayProtocol.local(sighting) || sighting.atMillis() < sessionStarted
                || sighting.atMillis() < now - 1_800_000 || sighting.atMillis() > now + 1000) {
            throw new IllegalArgumentException("Only fresh local observations can be shared");
        }
        JsonObject payload = envelope("publish", server);
        JsonObject message = new JsonObject();
        message.addProperty("name", sighting.structure().name());
        JsonArray coordinates = new JsonArray();
        coordinates.add(sighting.x());
        coordinates.add(sighting.y());
        coordinates.add(sighting.z());
        message.add("coordinates", coordinates);
        payload.add("message", message);
        return payload.toString();
    }

    private static JsonObject envelope(String type, String server) {
        if (server == null || !StructureRelayProtocol.validServer(server.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid lobby id");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        payload.addProperty("service", SERVICE);
        payload.addProperty("serverId", server);
        return payload;
    }

    public static List<StructureSighting> decode(String text, String server, long now) {
        if (text == null || text.length() > StructureRelayProtocol.MAX_MESSAGE) return List.of();
        try {
            JsonObject payload = JsonParser.parseString(text).getAsJsonObject();
            if (!SERVICE.equals(payload.get("service").getAsString())) return List.of();
            if (payload.has("serverId") && !server.equals(payload.get("serverId").getAsString())) return List.of();
            String type = payload.get("type").getAsString();
            List<StructureSighting> result = new ArrayList<>();
            if (type.equals("response")) addSighting(result, payload.getAsJsonObject("message"), now);
            else if (type.equals("initialMessage")) {
                JsonArray messages = payload.getAsJsonArray("message");
                if (messages.size() > 128) return List.of();
                for (var message : messages) {
                    try { addSighting(result, message.getAsJsonObject(), now); }
                    catch (RuntimeException malformedEntry) {                                              }
                }
            }
            return List.copyOf(result);
        } catch (RuntimeException invalid) {
            return List.of();
        }
    }

    private static void addSighting(List<StructureSighting> result, JsonObject message, long now) {
        final CrystalHollowsStructure structure;
        try { structure = CrystalHollowsStructure.valueOf(message.get("name").getAsString()); }
        catch (IllegalArgumentException unknownCategory) { return; }
        if (!StructureRelayProtocol.shareable(structure)) return;
        JsonArray coordinates = message.getAsJsonArray("coordinates");
        if (coordinates.size() != 3) throw new IllegalArgumentException();
        int[] values = new int[3];
        for (int i = 0; i < 3; i++) {
            var value = coordinates.get(i).getAsJsonPrimitive();
            if (!value.isNumber()) throw new IllegalArgumentException();
            values[i] = value.getAsBigDecimal().intValueExact();
        }
        if (!CrystalHollowsGeometry.insideHollows(values[0], values[1], values[2])) throw new IllegalArgumentException();
        result.add(new StructureSighting(structure, values[0], values[1], values[2],
                SightingConfidence.SHARED_REMOTE, SOURCE, now));
    }
}
