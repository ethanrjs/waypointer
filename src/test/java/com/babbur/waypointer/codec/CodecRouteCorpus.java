package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict, auditable adapter for the external route-array corpus; never uses the forgiving importer. */
final class CodecRouteCorpus {
    record Route(int index, String sourceType, String sourceIsland, String normalizedHash,
                 String coordinateHash, boolean firstCoordinates, boolean firstNormalized,
                 WaypointGroup group) {
        String split() {
            // First 32 SHA-256 bits, unsigned, independent of input order or route names.
            return Long.parseUnsignedLong(coordinateHash.substring(0, 8), 16) % 5 == 0
                    ? "holdout" : "development";
        }
    }

    record Corpus(List<Route> routes, Map<String, Object> integrity) {}

    private CodecRouteCorpus() {}

    static Corpus load(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        JsonElement root;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            root = readStrict(reader);
            require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing JSON content");
        }
        require(root.isJsonArray(), "Expected a JSON array of routes");
        List<Route> routes = new ArrayList<>();
        Set<String> coordinateHashes = new HashSet<>();
        Set<String> normalizedHashes = new HashSet<>();
        Set<String> sourceHashes = new HashSet<>();
        Map<String, Integer> sourceTypes = new LinkedHashMap<>();
        Map<String, Integer> islandMappings = new LinkedHashMap<>();
        long pointCount = 0;
        long lexicalDecimalCoordinates = 0;
        long numericNames = 0;
        long quantizedChannels = 0;
        double maxChannelQuantizationError = 0;
        int sourceUrls = 0;
        for (JsonElement element : root.getAsJsonArray()) {
            int index = routes.size();
            require(element.isJsonObject(), "Route " + index + " must be an object");
            JsonObject raw = element.getAsJsonObject();
            keys(raw, Set.of("island", "name", "sourceUrl", "type", "waypoints"),
                    Set.of("island", "name", "type", "waypoints"), "route " + index);
            String type = string(raw.get("type"), "type");
            require(Set.of("skyhanni", "skyblocker", "skytils").contains(type), "Unknown source type: " + type);
            sourceTypes.merge(type, 1, Integer::sum);
            if (raw.has("sourceUrl")) {
                string(raw.get("sourceUrl"), "sourceUrl");
                sourceUrls++;
            }
            String sourceIsland = string(raw.get("island"), "island");
            String zone = WaypointImporter.normalizeZone(sourceIsland);
            islandMappings.merge(sourceIsland + " -> " + zone, 1, Integer::sum);
            WaypointGroup group = WaypointGroup.create(string(raw.get("name"), "name"), zone);
            group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
            require(raw.get("waypoints").isJsonArray(), "waypoints must be an array");
            JsonArray points = raw.getAsJsonArray("waypoints");
            require(!points.isEmpty(), "Empty route " + index + " is not benchmarkable");
            for (JsonElement pointElement : points) {
                require(pointElement.isJsonObject(), "Waypoint must be an object");
                JsonObject point = pointElement.getAsJsonObject();
                Set<String> pointKeys = Set.of("x", "y", "z", "r", "g", "b", "options");
                keys(point, pointKeys, pointKeys, "point");
                int[] xyz = new int[3];
                String[] axes = {"x", "y", "z"};
                for (int axis = 0; axis < 3; axis++) {
                    BigDecimal number = number(point.get(axes[axis]), axes[axis]);
                    if (number.scale() > 0) lexicalDecimalCoordinates++;
                    // intValueExact rejects fractional coordinates and overflow; never round/floor.
                    xyz[axis] = number.intValueExact();
                    require(xyz[axis] >= Waypoint.MIN_BLOCK_COORDINATE
                            && xyz[axis] <= Waypoint.MAX_BLOCK_COORDINATE, "Coordinate outside model range");
                }
                int rgb = 0;
                for (String channel : List.of("r", "g", "b")) {
                    BigDecimal normalized = number(point.get(channel), channel);
                    require(normalized.signum() >= 0 && normalized.compareTo(BigDecimal.ONE) <= 0,
                            "RGB channel outside [0,1]");
                    BigDecimal scaled = normalized.multiply(BigDecimal.valueOf(255));
                    int value = scaled.setScale(0, RoundingMode.HALF_UP).intValueExact();
                    if (scaled.compareTo(BigDecimal.valueOf(value)) != 0) quantizedChannels++;
                    maxChannelQuantizationError = Math.max(maxChannelQuantizationError,
                            Math.abs(normalized.doubleValue() - value / 255.0));
                    rgb = (rgb << 8) | value;
                }
                require(point.get("options").isJsonObject(), "options must be an object");
                JsonObject options = point.getAsJsonObject("options");
                keys(options, Set.of("name"), Set.of("name"), "options");
                JsonElement rawName = options.get("name");
                String name;
                if (rawName.isJsonPrimitive() && rawName.getAsJsonPrimitive().isNumber()) {
                    name = number(rawName, "options.name").toBigIntegerExact().toString();
                    numericNames++;
                } else {
                    name = string(rawName, "options.name");
                }
                group.add(new Waypoint(xyz[0], xyz[1], xyz[2], name, rgb, 0, 0.0));
            }
            String coordinates = hashCoordinates(group);
            String normalized = hashNormalized(group);
            sourceHashes.add(sha256(canonical(raw).getBytes(StandardCharsets.UTF_8)));
            routes.add(new Route(index, type, sourceIsland, normalized, coordinates,
                    coordinateHashes.add(coordinates), normalizedHashes.add(normalized), group));
            pointCount += group.size();
        }
        require(!routes.isEmpty(), "Corpus contains no routes");
        Map<String, Object> integrity = new LinkedHashMap<>();
        integrity.put("fileSha256", sha256(bytes));
        integrity.put("fileBytes", bytes.length);
        integrity.put("routeCount", routes.size());
        integrity.put("pointCount", pointCount);
        integrity.put("uniqueSourceRecords", sourceHashes.size());
        integrity.put("uniqueNormalizedRoutes", normalizedHashes.size());
        integrity.put("uniqueOrderedCoordinates", coordinateHashes.size());
        integrity.put("sourceTypes", sourceTypes);
        integrity.put("islandMappings", islandMappings);
        integrity.put("recordsWithSourceUrl", sourceUrls);
        integrity.put("unknownFields", 0);
        integrity.put("duplicateJsonKeys", 0);
        integrity.put("rejectedOrSkippedRoutes", 0);
        integrity.put("fractionalCoordinates", 0);
        integrity.put("coordinatesWithPositiveDecimalScale", lexicalDecimalCoordinates);
        integrity.put("integerPointNamesExplicitlyStringified", numericNames);
        integrity.put("rgbChannelsQuantizedTo8Bit", quantizedChannels);
        integrity.put("maximumRgbNormalizedQuantizationError", maxChannelQuantizationError);
        integrity.put("normalization", List.of(
                "Input array order and point order are preserved, including duplicate points.",
                "Coordinates require exact integral BigDecimal values inside the Waypoint range; fractions fail.",
                "Normalized RGB [0,1] is converted to 8-bit RGB by HALF_UP(channel*255); raw decimal RGB is not lossless.",
                "Numeric point names must be integers and are explicitly stringified; string names stay unchanged.",
                "Islands use the production importer alias map; every mapping is listed above.",
                "type and sourceUrl are source provenance, not native route fields; full fidelity means the normalized native model.",
                "Normalized route hash includes name, zone, ordered coordinates, point names, and RGB; coordinate hash excludes metadata.",
                "No raw corpus or share strings are embedded in the report."));
        return new Corpus(List.copyOf(routes), integrity);
    }

    static String hashCoordinates(WaypointGroup group) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(group.size());
                for (Waypoint point : group.waypoints()) {
                    out.writeInt(point.x());
                    out.writeInt(point.y());
                    out.writeInt(point.z());
                }
            }
            return sha256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hashNormalized(WaypointGroup group) {
        JsonArray fields = new JsonArray();
        fields.add(group.name());
        fields.add(group.zoneId());
        fields.add(hashCoordinates(group));
        for (Waypoint point : group.waypoints()) {
            fields.add(point.name());
            fields.add(point.color());
        }
        return sha256(fields.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String canonical(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            element.getAsJsonObject().keySet().stream().sorted().forEach(key ->
                    sorted.add(key, com.google.gson.JsonParser.parseString(canonical(element.getAsJsonObject().get(key)))));
            return sorted.toString();
        }
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                array.add(com.google.gson.JsonParser.parseString(canonical(item)));
            }
            return array.toString();
        }
        return element.toString();
    }

    private static void keys(JsonObject object, Set<String> allowed, Set<String> required, String context) {
        Set<String> unknown = new HashSet<>(object.keySet());
        unknown.removeAll(allowed);
        require(unknown.isEmpty(), "Unknown " + context + " fields: " + unknown);
        require(object.keySet().containsAll(required), "Missing " + context + " fields: " + required);
    }

    private static String string(JsonElement element, String field) {
        require(element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(),
                field + " must be a JSON string");
        return element.getAsString();
    }

    private static BigDecimal number(JsonElement element, String field) {
        require(element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber(),
                field + " must be a JSON number");
        return element.getAsBigDecimal();
    }

    private static JsonElement readStrict(JsonReader reader) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    require(!object.has(name), "Duplicate JSON key: " + name);
                    object.add(name, readStrict(reader));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readStrict(reader));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException("Unexpected JSON token at " + reader.getPath());
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
