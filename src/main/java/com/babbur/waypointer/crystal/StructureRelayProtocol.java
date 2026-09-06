package com.babbur.waypointer.crystal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

public final class StructureRelayProtocol {
    public static final int MAX_MESSAGE = 16_384;
    public static final long MAX_AGE = 1_000_000_000_000L;

    private StructureRelayProtocol() {}

    public static boolean validServer(String server) {
        return server != null && server.matches("m(?:ini)?[0-9]{1,6}[a-z]{0,3}");
    }

    public static boolean shareable(CrystalHollowsStructure structure) {
        return structure != null && switch (structure) {
            case CRYSTAL_NUCLEUS, WISHING_TARGET, CORLEONE, KEY_GUARDIAN -> false;
            default -> true;
        };
    }

    public static boolean local(StructureSighting sighting) {
        return shareable(sighting.structure()) && CrystalHollowsGeometry.insideHollows(
                sighting.x(), sighting.y(), sighting.z()) && switch (sighting.confidence()) {
            case ENTITY, NPC_CHAT, COMPASS -> true;
            default -> false;
        };
    }

    public static String encode(StructureSighting sighting, long age) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "sighting");
        json.addProperty("structure", sighting.structure().id());
        json.addProperty("x", sighting.x());
        json.addProperty("y", sighting.y());
        json.addProperty("z", sighting.z());
        json.addProperty("age", age);
        String evidence = switch (sighting.confidence()) {
            case ENTITY -> "entity";
            case COMPASS -> "compass";
            case NPC_CHAT -> "npc";
            default -> null;
        };
        if (evidence != null) json.addProperty("evidence", evidence);
        return json.toString();
    }

    public static List<StructureSighting> decode(String message, long age, long now) {
        if (message == null || message.length() > MAX_MESSAGE || age < 0 || age > MAX_AGE) return List.of();
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.get("type").getAsString();
            List<StructureSighting> result = new ArrayList<>();
            if (type.equals("snapshot")) {
                var sightings = json.getAsJsonArray("sightings");
                if (sightings.size() > 128) return List.of();
                for (var value : sightings) result.add(decodeSighting(value.getAsJsonObject(), age, now));
            } else if (type.equals("sighting")) {
                result.add(decodeSighting(json, age, now));
            }
            return List.copyOf(result);
        } catch (RuntimeException invalid) {
            return List.of();
        }
    }

    private static StructureSighting decodeSighting(JsonObject json, long age, long now) {
        String id = json.get("structure").getAsString();
        CrystalHollowsStructure structure = null;
        for (var candidate : CrystalHollowsStructure.values()) {
            if (candidate.id().equals(id)) structure = candidate;
        }
        if (!shareable(structure)) throw new IllegalArgumentException();
        int x = Math.toIntExact(integer(json, "x"));
        int y = Math.toIntExact(integer(json, "y"));
        int z = Math.toIntExact(integer(json, "z"));
        long observedAge = integer(json, "age");
        long at = integer(json, "at");
        if (!CrystalHollowsGeometry.insideHollows(x, y, z)
                || observedAge < 0 || observedAge > MAX_AGE
                || at < now - 1_800_000 || at > now + 60_000
                || Math.abs(observedAge + Math.max(0, now - at) / 50 - age) > 1_200) {
            throw new IllegalArgumentException();
        }
        SightingConfidence evidence = null;
        if (json.has("evidence")) {
            var value = json.getAsJsonPrimitive("evidence");
            if (!value.isString()) throw new IllegalArgumentException();
            evidence = switch (value.getAsString()) {
                case "entity" -> SightingConfidence.ENTITY;
                case "compass" -> SightingConfidence.COMPASS;
                case "npc" -> SightingConfidence.NPC_CHAT;
                default -> throw new IllegalArgumentException();
            };
        }
        String source = "relay";
        if (json.has("reporter")) {
            var reporter = json.getAsJsonPrimitive("reporter");
            if (!reporter.isString() || !reporter.getAsString().matches(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw new IllegalArgumentException();
            }
            source += ":" + reporter.getAsString();
        }
        return new StructureSighting(structure, x, y, z, SightingConfidence.SHARED_REMOTE,
                source, at, List.of(), "", evidence);
    }

    private static long integer(JsonObject json, String key) {
        var value = json.getAsJsonPrimitive(key);
        if (!value.isNumber()) throw new IllegalArgumentException();
        return value.getAsBigDecimal().longValueExact();
    }
}
