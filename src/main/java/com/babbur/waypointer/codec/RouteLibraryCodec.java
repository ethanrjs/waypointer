package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Versioned wrapper for native routes plus local route-library metadata. */
public final class RouteLibraryCodec {
    public static final String MAGIC = "WPL:1:";

    private static final String MAGIC_ROOT = "WPL:";
    private static final char COMPACT_BODY_PREFIX = '.';
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final int MAX_JSON_TOKENS = 750_000;
    private static final int MAX_JSON_DEPTH = 8;

    private RouteLibraryCodec() {}

    public static boolean isPayload(String payload) {
        return payload != null && payload.startsWith(MAGIC_ROOT);
    }

    public static String encode(
            List<WaypointGroup> groups,
            WaypointCodec.Options options,
            RouteLibraryMetadata metadata) {
        RouteLibraryMetadata safeMetadata = metadata == null
                ? RouteLibraryMetadata.empty() : metadata;
        safeMetadata = metadataForOptions(safeMetadata, options);
        if (safeMetadata.isEmpty()) return WaypointCodec.encode(groups, options);
        safeMetadata.validateForGroups(groups);
        try {
            String library = WaypointCodec.MAGIC + V10RouteLibraryCodec
                    .encodeCandidate(groups, options, safeMetadata).transport();
            WaypointImporter.enforceTextPayloadLimit(library);
            return library;
        } catch (V10ProfileLimitException profileLimit) {
            // The only outbound WPL escape: the library exceeds the bounded V10
            // frame profile, mirroring the route writer's V9 fallback.
        } catch (IOException failure) {
            throw new IllegalStateException("route library export failed", failure);
        }
        return encodeLegacyWrapper(groups, options, safeMetadata);
    }

    /** Legacy {@code WPL:1:} wrapper for libraries exceeding V10 limits and compatibility tests. */
    static String encodeLegacyWrapper(
            List<WaypointGroup> groups,
            WaypointCodec.Options options,
            RouteLibraryMetadata metadata) {
        RouteLibraryMetadata safeMetadata = metadataForOptions(
                metadata == null ? RouteLibraryMetadata.empty() : metadata, options);
        if (safeMetadata.isEmpty()) return WaypointCodec.encode(groups, options);
        safeMetadata.validateForGroups(groups);
        String payload = WaypointCodec.encode(groups, options);

        JsonObject root = new JsonObject();
        root.addProperty("payload", payload);
        root.add("manualColors", encodeManualColors(safeMetadata.manualColors()));
        root.add("folders", encodeFolders(safeMetadata.folders()));
        // Written only when present so unpainted codes keep the pre-paint shape
        // and older clients (which ignore unknown keys) stay compatible.
        if (!safeMetadata.paints().isEmpty()) {
            root.add("paints", encodePaints(safeMetadata.paints()));
        }
        String body = encodeBody(root.toString());
        String wrapped = MAGIC + body;
        WaypointImporter.enforceTextPayloadLimit(wrapped);
        return wrapped;
    }

    private static RouteLibraryMetadata metadataForOptions(
            RouteLibraryMetadata metadata, WaypointCodec.Options options) {
        Objects.requireNonNull(options, "options");
        List<RouteLibraryMetadata.ManualColorsEntry> manualColors = options.includeColors
                ? metadata.manualColors() : List.of();
        List<RouteLibraryMetadata.FolderDefinition> folders;
        if (!options.includeGroupMeta) {
            folders = List.of();
        } else if (options.includeColors) {
            folders = metadata.folders();
        } else {
            folders = metadata.folders().stream()
                    .map(folder -> new RouteLibraryMetadata.FolderDefinition(
                            folder.name(), RouteFolder.DEFAULT_COLOR,
                            folder.collapsed(), folder.memberOrdinals()))
                    .toList();
        }
        List<RouteLibraryMetadata.PaintEntry> paints = options.includeColors
                ? metadata.paints() : List.of();
        return new RouteLibraryMetadata(manualColors, folders, paints);
    }

    public static Decoded decode(String payload) {
        if (payload != null && payload.trim().startsWith(WaypointCodec.MAGIC)) {
            return decodeUniversal(payload.trim());
        }
        Envelope envelope = readEnvelope(payload);
        JsonObject root = envelope.root();
        String inner = envelope.nativePayload();

        List<RouteLibraryMetadata.ManualColorsEntry> manualColors = decodeManualColors(
                requireArray(root, "manualColors"));
        List<RouteLibraryMetadata.FolderDefinition> folders = decodeFolders(
                requireArray(root, "folders"));
        // Optional: codes written before waypoint paints existed omit the key.
        List<RouteLibraryMetadata.PaintEntry> paints = root.has("paints")
                ? decodePaints(requireArray(root, "paints"))
                : List.of();
        RouteLibraryMetadata metadata =
                new RouteLibraryMetadata(manualColors, folders, paints);

        DecodeDebug nativePayload = WaypointCodec.debugDecode(inner);
        if (nativePayload.version() != WaypointCodec.WIRE_VERSION
                && nativePayload.version() != WaypointCodec.V10_WIRE_VERSION) {
            throw new IllegalArgumentException(
                    "route library payload must contain a wire-v9 or wire-v10 native route");
        }
        List<WaypointGroup> groups = nativePayload.decodedGroups();
        metadata.validateForGroups(groups);
        return new Decoded(groups, nativePayload.label(), metadata);
    }

    /** Decode a V10 route-library share. */
    private static Decoded decodeUniversal(String payload) {
        WaypointImporter.enforceTextPayloadLimit(payload);
        String transport = payload.substring(WaypointCodec.MAGIC.length());
        V10Transport.CheckedFrame frame;
        try {
            if (!V10Transport.looksLikeV10(transport)) {
                throw new IllegalArgumentException("not a route library payload");
            }
            frame = V10Transport.probe(transport);
        } catch (IOException failure) {
            throw new IllegalArgumentException("not a route library payload: "
                    + failure.getMessage(), failure);
        }
        if (!V10RouteLibraryCodec.isLibrarySemantic(frame.semantic())) {
            throw new IllegalArgumentException("not a route library payload");
        }
        try {
            return V10RouteLibraryCodec.decode(frame);
        } catch (IOException failure) {
            throw new IllegalArgumentException("route library decode failed: "
                    + failure.getMessage(), failure);
        }
    }

    public static String unwrapNativePayload(String payload) {
        return readEnvelope(payload).nativePayload();
    }

    private static Envelope readEnvelope(String payload) {
        if (payload == null) throw new IllegalArgumentException("null route library payload");
        WaypointImporter.enforceTextPayloadLimit(payload);
        if (!payload.startsWith(MAGIC)) {
            if (payload.startsWith(MAGIC_ROOT)) {
                throw new IllegalArgumentException("unsupported route library version");
            }
            throw new IllegalArgumentException("not a route library payload");
        }

        String encoded = payload.substring(MAGIC.length());
        if (encoded.isEmpty()) throw new IllegalArgumentException("empty route library payload");
        JsonObject root = parseRoot(decodeBody(encoded));
        String inner = requireString(root, "payload");
        WaypointImporter.enforceTextPayloadLimit(inner);
        if (!inner.equals(inner.trim()) || !inner.startsWith(WaypointCodec.MAGIC)) {
            throw new IllegalArgumentException("route library payload must contain a native route");
        }

        return new Envelope(root, inner);
    }

    private record Envelope(JsonObject root, String nativePayload) {}

    public record Decoded(
            List<WaypointGroup> groups, String label, RouteLibraryMetadata metadata) {
        public Decoded {
            groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
            label = label == null ? "" : label;
            metadata = Objects.requireNonNull(metadata, "metadata");
        }
    }

    static String encodeBody(String json) {
        Objects.requireNonNull(json, "json");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("route library metadata is too large");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(out, deflater)) {
            compressed.write(bytes);
        } catch (IOException failure) {
            throw new IllegalStateException("route library export failed", failure);
        } finally {
            deflater.end();
        }
        return COMPACT_BODY_PREFIX + AsciiStreamCodec.encode(out.toByteArray());
    }

    static String decodeBody(String body) {
        if (body.startsWith(String.valueOf(COMPACT_BODY_PREFIX))) {
            byte[] compressed;
            try {
                compressed = AsciiStreamCodec.decode(body.substring(1));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("invalid route library encoding", failure);
            }
            try (InflaterInputStream input = new InflaterInputStream(
                    new ByteArrayInputStream(compressed))) {
                return decodeUtf8(readLimited(input));
            } catch (IOException failure) {
                throw new IllegalArgumentException("invalid route library encoding", failure);
            }
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(body);
            if (bytes.length > MAX_JSON_BYTES) {
                throw new IllegalArgumentException("route library metadata is too large");
            }
            return decodeUtf8(bytes);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid route library encoding", failure);
        }
    }

    private static byte[] readLimited(java.io.InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_JSON_BYTES) {
                throw new IllegalArgumentException("route library metadata is too large");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static JsonArray encodeManualColors(
            List<RouteLibraryMetadata.ManualColorsEntry> entries) {
        JsonArray out = new JsonArray();
        for (RouteLibraryMetadata.ManualColorsEntry entry : entries) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("group", entry.groupOrdinal());
            JsonArray colors = new JsonArray();
            for (int color : entry.colors()) colors.add(color);
            encoded.add("colors", colors);
            out.add(encoded);
        }
        return out;
    }

    private static JsonArray encodeFolders(
            List<RouteLibraryMetadata.FolderDefinition> folders) {
        JsonArray out = new JsonArray();
        for (RouteLibraryMetadata.FolderDefinition folder : folders) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("name", folder.name());
            encoded.addProperty("color", folder.color());
            encoded.addProperty("collapsed", folder.collapsed());
            JsonArray members = new JsonArray();
            for (int ordinal : folder.memberOrdinals()) members.add(ordinal);
            encoded.add("members", members);
            out.add(encoded);
        }
        return out;
    }

    private static JsonArray encodePaints(List<RouteLibraryMetadata.PaintEntry> paints) {
        JsonArray out = new JsonArray();
        for (RouteLibraryMetadata.PaintEntry entry : paints) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("group", entry.groupOrdinal());
            JsonArray palette = new JsonArray();
            for (int color : entry.paint().paletteCopy()) palette.add(color);
            encoded.add("palette", palette);
            encoded.addProperty("pixels", entry.paint().pixelsBase64());
            encoded.addProperty("enabled", entry.enabled());
            out.add(encoded);
        }
        return out;
    }

    private static List<RouteLibraryMetadata.PaintEntry> decodePaints(JsonArray encoded) {
        if (encoded.size() > RouteLibraryMetadata.MAX_GROUPS) {
            throw new IllegalArgumentException("route library has too many paint entries");
        }
        List<RouteLibraryMetadata.PaintEntry> out = new ArrayList<>(encoded.size());
        for (JsonElement element : encoded) {
            JsonObject entry = requireObject(element, "paint entry");
            int group = requireInt(entry, "group");
            JsonArray paletteJson = requireArray(entry, "palette");
            if (paletteJson.size() != WaypointPaint.PALETTE_SIZE) {
                throw new IllegalArgumentException(
                        "waypoint paint palette must contain 16 colors");
            }
            int[] palette = new int[WaypointPaint.PALETTE_SIZE];
            for (int i = 0; i < palette.length; i++) {
                int color = requireInt(paletteJson.get(i), "paint palette color");
                if (color < 0 || color > 0xFFFFFF) {
                    throw new IllegalArgumentException(
                            "paint palette color is outside the RGB range");
                }
                palette[i] = color;
            }
            byte[] pixels = WaypointPaint.decodePixels(requireString(entry, "pixels"));
            boolean enabled = requireBoolean(entry, "enabled");
            out.add(new RouteLibraryMetadata.PaintEntry(
                    group, new WaypointPaint(palette, pixels), enabled));
        }
        return List.copyOf(out);
    }

    private static List<RouteLibraryMetadata.ManualColorsEntry> decodeManualColors(
            JsonArray encoded) {
        if (encoded.size() > RouteLibraryMetadata.MAX_GROUPS) {
            throw new IllegalArgumentException("route library has too many manual color entries");
        }
        List<RouteLibraryMetadata.ManualColorsEntry> out = new ArrayList<>(encoded.size());
        int totalColors = 0;
        for (JsonElement element : encoded) {
            JsonObject entry = requireObject(element, "manual color entry");
            int group = requireInt(entry, "group");
            JsonArray colors = requireArray(entry, "colors");
            if (colors.size() > WaypointImporter.MAX_WAYPOINTS_PER_GROUP) {
                throw new IllegalArgumentException("manual color list is too large");
            }
            totalColors += colors.size();
            if (totalColors > WaypointImporter.MAX_TOTAL_WAYPOINTS_PER_IMPORT) {
                throw new IllegalArgumentException("route library has too many manual colors");
            }
            List<Integer> decodedColors = new ArrayList<>(colors.size());
            for (JsonElement color : colors) {
                int value = requireInt(color, "manual color");
                if (value < 0 || value > 0xFFFFFF) {
                    throw new IllegalArgumentException("manual color is outside the RGB range");
                }
                decodedColors.add(value);
            }
            out.add(new RouteLibraryMetadata.ManualColorsEntry(group, decodedColors));
        }
        return List.copyOf(out);
    }

    private static List<RouteLibraryMetadata.FolderDefinition> decodeFolders(JsonArray encoded) {
        if (encoded.size() > RouteLibraryMetadata.MAX_FOLDERS) {
            throw new IllegalArgumentException("route library has too many folders");
        }
        List<RouteLibraryMetadata.FolderDefinition> out = new ArrayList<>(encoded.size());
        for (JsonElement element : encoded) {
            JsonObject folder = requireObject(element, "folder entry");
            String name = requireString(folder, "name");
            int color = requireInt(folder, "color");
            boolean collapsed = requireBoolean(folder, "collapsed");
            JsonArray members = requireArray(folder, "members");
            if (members.size() == 0 || members.size() > RouteLibraryMetadata.MAX_GROUPS) {
                throw new IllegalArgumentException("route library folder has an invalid member count");
            }
            List<Integer> ordinals = new ArrayList<>(members.size());
            for (JsonElement member : members) {
                ordinals.add(requireInt(member, "folder member"));
            }
            out.add(new RouteLibraryMetadata.FolderDefinition(
                    name, color, collapsed, ordinals));
        }
        return List.copyOf(out);
    }

    private static JsonObject parseRoot(String json) {
        validateJsonBudget(json);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement parsed = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("route library JSON has trailing data");
            }
            return requireObject(parsed, "route library root");
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("malformed route library JSON", failure);
        }
    }

    private static void validateJsonBudget(String json) {
        int tokens = 0;
        int depth = 0;
        Deque<Set<String>> objectNames = new ArrayDeque<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            while (true) {
                JsonToken token = reader.peek();
                if (token == JsonToken.END_DOCUMENT) return;
                if (++tokens > MAX_JSON_TOKENS) {
                    throw new IllegalArgumentException("route library JSON has too many values");
                }
                switch (token) {
                    case BEGIN_ARRAY -> {
                        reader.beginArray();
                        if (++depth > MAX_JSON_DEPTH) {
                            throw new IllegalArgumentException("route library JSON is too deep");
                        }
                    }
                    case BEGIN_OBJECT -> {
                        reader.beginObject();
                        if (++depth > MAX_JSON_DEPTH) {
                            throw new IllegalArgumentException("route library JSON is too deep");
                        }
                        objectNames.push(new HashSet<>());
                    }
                    case END_ARRAY -> {
                        reader.endArray();
                        depth--;
                    }
                    case END_OBJECT -> {
                        reader.endObject();
                        depth--;
                        objectNames.pop();
                    }
                    case NAME -> {
                        String name = reader.nextName();
                        if (objectNames.isEmpty() || !objectNames.peek().add(name)) {
                            throw new IllegalArgumentException(
                                    "route library JSON has a duplicate field");
                        }
                    }
                    case STRING, NUMBER -> reader.nextString();
                    case BOOLEAN -> reader.nextBoolean();
                    case NULL -> reader.nextNull();
                    default -> throw new IllegalArgumentException("malformed route library JSON");
                }
            }
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("malformed route library JSON", failure);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("route library JSON is not UTF-8", failure);
        }
    }

    private static JsonObject requireObject(JsonElement element, String field) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return element.getAsJsonArray();
    }

    private static String requireString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) throw new IllegalArgumentException(field + " must be a string");
        return primitive.getAsString();
    }

    private static boolean requireBoolean(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return primitive.getAsBoolean();
    }

    private static int requireInt(JsonObject object, String field) {
        return requireInt(object.get(field), field);
    }

    private static int requireInt(JsonElement element, String field) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            BigDecimal value = element.getAsBigDecimal();
            return value.intValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }

}
