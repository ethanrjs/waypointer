package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * Deterministic JVM oracle for implementations of the native {@code WP:} wire format.
 *
 * <p>This class lives in the test source set on purpose. It emits a stable JSON
 * document that another runtime can consume without loading Minecraft classes.
 * Run {@link #main(String[])} with no arguments to print the fixture. Pass one
 * path argument to write it as UTF-8.
 */
public final class WaypointCodecGoldenVectorGenerator {

    static final String FIXTURE_RESOURCE = "/fixtures/waypointer-native-golden-vectors.json";
    static final String LEGACY_DICTIONARY_SHA256 =
            "322afa1c3cafe7013283652fe160da4d78a84bbc2824bb745121f260f1be6d8d";

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private WaypointCodecGoldenVectorGenerator() {}

    public static void main(String[] args) throws Exception {
        String fixture = generateJson();
        if (args.length == 0) {
            System.out.print(fixture);
            return;
        }
        if (args.length != 1) {
            throw new IllegalArgumentException("expected zero arguments or one output path");
        }
        write(Path.of(args[0]), fixture);
    }

    public static String generateJson() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("format", "waypointer-native-codec-golden-vectors");
        root.addProperty("schema", 1);
        root.addProperty("wireMagic", WaypointCodec.MAGIC);
        root.add("dictionaries", dictionariesJson());

        JsonArray vectors = new JsonArray();
        for (VectorSpec vector : vectors()) {
            vectors.add(vectorJson(vector));
        }
        root.add("vectors", vectors);
        return GSON.toJson(root) + "\n";
    }

    static void write(Path output, String fixture) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(absolute, fixture, StandardCharsets.UTF_8);
    }

    static JsonObject decodedSemantics(String code) {
        WaypointCodec.Decoded decoded = WaypointCodec.decodeFull(code);
        JsonObject result = new JsonObject();
        result.addProperty("label", decoded.label());
        JsonArray groups = new JsonArray();
        for (WaypointGroup group : decoded.groups()) {
            groups.add(groupSemantics(group));
        }
        result.add("groups", groups);
        return result;
    }

    private static List<VectorSpec> vectors() throws Exception {
        List<WaypointGroup> legacyRoutes = legacyRoutes();
        WaypointCodec.Options legacyOptions = WaypointCodec.Options.NO_NAMES.toBuilder()
                .label("Legacy route")
                .build();

        String v1 = encodeLegacyV1OrV2(legacyRoutes, legacyOptions, 1);
        String v2 = encodeLegacyV1OrV2(legacyRoutes, legacyOptions, 2);
        String v3 = encodeLegacyV3OrV4(legacyRoutes, "Legacy route", 3);
        String v4 = encodeLegacyV3OrV4(legacyRoutes, "Legacy route", 4);
        String v5 = WaypointCodec.encodeLegacyForTest(legacyRoutes, legacyOptions,
                WaypointCodec.PackingMode.FORCE_VECTOR, 5);
        String v6 = WaypointCodec.encodeLegacyForTest(legacyRoutes, legacyOptions,
                WaypointCodec.PackingMode.FORCE_VECTOR, 6);
        String v7 = WaypointCodec.encodeLegacyForTest(legacyRoutes, legacyOptions,
                WaypointCodec.PackingMode.FORCE_VECTOR, 7);
        String v8 = WaypointCodec.encodeLegacyForTest(legacyRoutes, legacyOptions,
                WaypointCodec.PackingMode.FORCE_VECTOR, 8);

        WaypointCodec.Options richOptions = WaypointCodec.Options.FULL_FIDELITY.toBuilder()
                .label("Rich v9 route")
                .build();
        String v9General = WaypointCodec.encodeV9ForTest(List.of(richV9Route()), richOptions);
        String v9Compact = WaypointCodec.encodeV9ForTest(List.of(compactV9Route()),
                WaypointCodec.Options.FULL_FIDELITY.toBuilder().label("Compact v9 route").build());
        String v9Coordinates = WaypointCodec.encodeV9ForTest(List.of(coordinateV9Route()),
                WaypointCodec.Options.NO_NAMES.toBuilder().label("Coordinate v9 route").build());
        String v9MetadataCoordinates = WaypointCodec.encodeV9ForTest(List.of(metadataCoordinateV9Route()),
                WaypointCodec.Options.NO_NAMES.toBuilder().label("Metadata v9 route").build());

        return List.of(
                new VectorSpec("v1-general", 1, null, v1),
                new VectorSpec("v2-general", 2, null, v2),
                new VectorSpec("v3-general", 3, null, v3),
                new VectorSpec("v4-general", 4, null, v4),
                new VectorSpec("v5-general", 5, null, v5),
                new VectorSpec("v6-general", 6, null, v6),
                new VectorSpec("v7-general", 7, null, v7),
                new VectorSpec("v8-general", 8, null, v8),
                new VectorSpec("v9-kind-0-general-rich", 9,
                        WaypointCodec.V9_CONTENT_KIND_GENERAL_ROUTE, v9General),
                new VectorSpec("v9-kind-1-compact-full", 9,
                        WaypointCodec.V9_CONTENT_KIND_COMPACT_ROUTE, v9Compact),
                new VectorSpec("v9-kind-2-compact-coordinates", 9,
                        WaypointCodec.V9_CONTENT_KIND_COORDINATE_ROUTE, v9Coordinates),
                new VectorSpec("v9-kind-5-coordinate-metadata", 9,
                        WaypointCodec.V9_CONTENT_KIND_COORDINATE_ROUTE_WITH_META,
                        v9MetadataCoordinates));
    }

    private static JsonObject vectorJson(VectorSpec vector) throws Exception {
        PayloadParts payload = inspect(vector.code(), vector.wireVersion());
        int header = payload.body()[0] & 0xFF;
        int actualVersion = header & 0x0F;
        if (actualVersion != vector.wireVersion()) {
            throw new IllegalStateException(vector.id() + " emitted v" + actualVersion);
        }
        Integer actualKind = actualVersion == 9 ? WaypointCodec.v9ContentKind(header) : null;
        if (!java.util.Objects.equals(vector.v9ContentKind(), actualKind)) {
            throw new IllegalStateException(vector.id() + " emitted content kind " + actualKind);
        }

        JsonObject json = new JsonObject();
        json.addProperty("id", vector.id());
        json.addProperty("wireVersion", vector.wireVersion());
        if (vector.v9ContentKind() == null) {
            json.add("v9ContentKind", JsonNull.INSTANCE);
        } else {
            json.addProperty("v9ContentKind", vector.v9ContentKind());
        }
        json.addProperty("code", vector.code());
        json.addProperty("compressedHex", hex(payload.compressed()));
        json.addProperty("bodyHex", hex(payload.body()));
        if (payload.crc32() == null) {
            json.add("crc32Hex", JsonNull.INSTANCE);
        } else {
            json.addProperty("crc32Hex", hex(payload.crc32()));
        }
        json.add("decoded", decodedSemantics(vector.code()));
        return json;
    }

    private static JsonObject dictionariesJson() throws Exception {
        JsonObject dictionaries = new JsonObject();
        dictionaries.add("legacyV1ToV8", dictionaryJson(CodecDictionary.BYTES));
        dictionaries.add("v9", dictionaryJson(V9CodecDictionary.BYTES));
        return dictionaries;
    }

    private static JsonObject dictionaryJson(byte[] bytes) throws Exception {
        JsonObject dictionary = new JsonObject();
        dictionary.addProperty("bytes", bytes.length);
        dictionary.addProperty("sha256", sha256(bytes));
        return dictionary;
    }

    private static JsonObject groupSemantics(WaypointGroup group) {
        JsonObject json = new JsonObject();
        json.addProperty("name", group.name());
        json.addProperty("zoneId", group.zoneId());
        json.addProperty("gradientMode", group.gradientMode().name());
        json.addProperty("loadMode", group.loadMode().name());
        json.addProperty("routeKind", group.routeKind().name());
        json.addProperty("defaultRadius", group.defaultRadius());
        json.addProperty("skipAheadEnabled", group.skipAheadEnabled());
        json.addProperty("staticColor", group.staticColor());
        json.addProperty("gradientStartColor", group.gradientStartColor());
        json.addProperty("gradientEndColor", group.gradientEndColor());
        json.addProperty("enabled", group.enabled());
        json.addProperty("currentIndex", group.currentIndex());

        JsonArray waypoints = new JsonArray();
        for (Waypoint waypoint : group.waypoints()) {
            JsonObject point = new JsonObject();
            point.addProperty("x", waypoint.x());
            point.addProperty("y", waypoint.y());
            point.addProperty("z", waypoint.z());
            point.addProperty("preciseX", waypoint.preciseX());
            point.addProperty("preciseY", waypoint.preciseY());
            point.addProperty("preciseZ", waypoint.preciseZ());
            point.addProperty("name", waypoint.name());
            point.addProperty("color", waypoint.color());
            point.addProperty("flags", waypoint.flags());
            point.addProperty("flagsUnsigned", Integer.toUnsignedLong(waypoint.flags()));
            point.addProperty("customRadius", waypoint.customRadius());
            point.addProperty("tempMode", waypoint.tempMode());
            point.addProperty("expiresAtMillis", waypoint.expiresAtMillis());
            waypoints.add(point);
        }
        json.add("waypoints", waypoints);
        return json;
    }

    private static List<WaypointGroup> legacyRoutes() {
        WaypointGroup first = WaypointGroup.create("Legacy Alpha", "hub");
        first.add(Waypoint.at(10, 70, -20));
        first.add(Waypoint.at(15, 71, -18));
        first.add(Waypoint.at(30, 68, 5));

        WaypointGroup second = WaypointGroup.create("Legacy Beta", "dungeon_f7");
        second.add(Waypoint.at(-4, 64, 9));
        second.add(Waypoint.at(-3, 65, 11));
        return List.of(first, second);
    }

    private static WaypointGroup richV9Route() {
        WaypointGroup route = WaypointGroup.create("Rich Route \uD83E\uDDED", "custom_test_zone");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.setLoadMode(WaypointGroup.LoadMode.STATIC);
        route.setDefaultRadius(3.0005);
        route.setSkipAheadEnabled(false);
        route.setStaticColor(0x123456);
        route.setGradientStartColor(0x010203);
        route.setGradientEndColor(0xA0B0C0);

        Waypoint first = new Waypoint(4, 70, -8, "Start <3 \u2728", 0xABCDEF,
                Integer.MIN_VALUE | Waypoint.FLAG_DEPTH_CHECKED | Waypoint.FLAG_DISABLED,
                4.54).withPreciseSixteenths(4 * 16 + 3, 70 * 16 + 12, -8 * 16 + 5);
        Waypoint second = new Waypoint(5, 71, -7, "Child", 0x102030,
                Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT
                        | Waypoint.FLAG_FILLED_SUBWAYPOINT,
                0.0).withPreciseSixteenths(5 * 16 + 9, 71 * 16 + 1, -7 * 16 + 15);
        route.add(first);
        route.add(second);
        return route;
    }

    private static WaypointGroup compactV9Route() {
        WaypointGroup route = WaypointGroup.create("Route 1", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        for (int index = 0; index < 64; index++) {
            route.add(new Waypoint(
                    -120 + index * 3,
                    64 + index % 5,
                    300 - index * 2,
                    Integer.toString(index + 1),
                    index % 7 == 0 ? 0x55CCEE : 0x33AA55,
                    0,
                    0.0));
        }
        return route;
    }

    private static WaypointGroup coordinateV9Route() {
        WaypointGroup route = WaypointGroup.create("", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        Random random = new Random(0);
        int x = 0;
        int y = 64;
        int z = 0;
        for (int index = 0; index < 32; index++) {
            x += random.nextInt(41) - 20;
            y += random.nextInt(5) - 2;
            z += random.nextInt(41) - 20;
            route.add(Waypoint.at(x, y, z));
        }
        return route;
    }

    private static WaypointGroup metadataCoordinateV9Route() {
        WaypointGroup route = WaypointGroup.create("", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.setDefaultRadius(6.5);
        route.add(Waypoint.at(0, 64, 0));
        route.add(Waypoint.at(2, 65, 3));
        route.add(Waypoint.at(7, 66, 9));
        return route;
    }

    private static String encodeLegacyV1OrV2(List<WaypointGroup> groups,
                                              WaypointCodec.Options options,
                                              int version) throws Exception {
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException("legacy writer supports only v1 or v2");
        }
        Map<String, Integer> pool = new LinkedHashMap<>();
        intern(pool, "");
        for (WaypointGroup group : groups) {
            intern(pool, group.name());
            intern(pool, group.zoneId());
            if (options.includeNames) {
                for (Waypoint waypoint : group.waypoints()) {
                    if (waypoint.hasName()) intern(pool, waypoint.name());
                }
            }
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(body);
        int header = version;
        if (options.includeNames) header |= 0x10;
        if (!options.label.isEmpty()) header |= 0x20;
        output.writeByte(header);
        if (!options.label.isEmpty()) writeUtf8(output, options.label);
        WaypointCodec.writeVarint(output, pool.size());
        for (String value : pool.keySet()) writeUtf8(output, value);
        WaypointCodec.writeVarint(output, groups.size());

        for (WaypointGroup group : groups) {
            WaypointCodec.writeVarint(output, pool.get(group.name()));
            WaypointCodec.writeVarint(output, pool.get(group.zoneId()));
            int groupFlags = 0;
            if (options.includeGroupMeta) {
                if (group.gradientMode() == WaypointGroup.GradientMode.AUTO) groupFlags |= 0x02;
                if (group.loadMode() == WaypointGroup.LoadMode.SEQUENCE) groupFlags |= 0x04;
            } else {
                groupFlags |= 0x02;
            }
            output.writeByte(groupFlags);
            WaypointCodec.writeVarint(output, group.size());

            int lastX = 0;
            int lastY = 0;
            int lastZ = 0;
            for (int index = 0; index < group.size(); index++) {
                Waypoint waypoint = group.get(index);
                WaypointCodec.writeZigzag(output,
                        index == 0 ? waypoint.x() : waypoint.x() - lastX);
                WaypointCodec.writeZigzag(output,
                        index == 0 ? waypoint.y() : waypoint.y() - lastY);
                WaypointCodec.writeZigzag(output,
                        index == 0 ? waypoint.z() : waypoint.z() - lastZ);
                lastX = waypoint.x();
                lastY = waypoint.y();
                lastZ = waypoint.z();
            }
            for (Waypoint waypoint : group.waypoints()) {
                writeLegacyWaypointBody(output, pool, waypoint, options);
            }
        }
        output.flush();

        byte[] compressed = deflate(body.toByteArray(), CodecDictionary.BYTES);
        String text = version == 1
                ? CjkBase16384.encode(compressed)
                : AsciiPackCodec.encode(compressed);
        return WaypointCodec.MAGIC + text;
    }

    private static String encodeLegacyV3OrV4(List<WaypointGroup> groups, String label,
                                              int version) throws Exception {
        if (version != 3 && version != 4) {
            throw new IllegalArgumentException("legacy writer supports only v3 or v4");
        }
        WaypointCodec.Options options = WaypointCodec.Options.NO_NAMES.toBuilder()
                .label(label)
                .build();
        String current = WaypointCodec.encode(
                groups, options, WaypointCodec.PackingMode.FORCE_VECTOR);
        PayloadParts currentPayload = inspect(current, 9);
        byte[] body = currentPayload.body().clone();
        if (WaypointCodec.v9ContentKind(body[0] & 0xFF)
                != WaypointCodec.V9_CONTENT_KIND_GENERAL_ROUTE) {
            throw new IllegalStateException("legacy source did not use v9 general body");
        }
        body[0] = (byte) (version | (label.isEmpty() ? 0 : 0x20));
        byte[] compressed = deflate(body, CodecDictionary.BYTES);
        String text = version == 3
                ? AsciiStreamCodec.encodeLegacyV3(compressed)
                : WaypointCodec.escapeHypixelEmotes(AsciiStreamCodec.encodeLegacyV4(compressed));
        return WaypointCodec.MAGIC + text;
    }

    private static void writeLegacyWaypointBody(DataOutputStream output,
                                                 Map<String, Integer> pool,
                                                 Waypoint waypoint,
                                                 WaypointCodec.Options options) throws Exception {
        boolean hasName = options.includeNames && waypoint.hasName();
        boolean hasColor = options.includeColors
                && (waypoint.color() & 0xFFFFFF) != (Waypoint.DEFAULT_COLOR & 0xFFFFFF);
        boolean hasRadius = options.includeRadii && waypoint.customRadius() > 0;
        int extendedFlags = options.includeWaypointFlags ? waypoint.flags() & 0xFF : 0;

        int flags = 0;
        if (hasName) flags |= 0x01;
        if (hasColor) flags |= 0x02;
        if (hasRadius) flags |= 0x04;
        if (extendedFlags != 0) flags |= 0x08;
        output.writeByte(flags);
        if (hasName) WaypointCodec.writeVarint(output, pool.get(waypoint.name()));
        if (hasColor) {
            output.writeByte((waypoint.color() >>> 16) & 0xFF);
            output.writeByte((waypoint.color() >>> 8) & 0xFF);
            output.writeByte(waypoint.color() & 0xFF);
        }
        if (hasRadius) {
            WaypointCodec.writeVarint(output,
                    (int) Math.round(waypoint.customRadius() * 10.0));
        }
        if (extendedFlags != 0) WaypointCodec.writeVarint(output, extendedFlags);
    }

    private static int intern(Map<String, Integer> pool, String value) {
        String key = value == null ? "" : value;
        Integer existing = pool.get(key);
        if (existing != null) return existing;
        int index = pool.size();
        pool.put(key, index);
        return index;
    }

    private static void writeUtf8(DataOutputStream output, String value) throws Exception {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        WaypointCodec.writeVarint(output, bytes.length);
        output.write(bytes);
    }

    private static PayloadParts inspect(String code, int version) throws Exception {
        String text = code.substring(WaypointCodec.MAGIC.length());
        byte[] compressed = switch (version) {
            case 1 -> CjkBase16384.decode(text);
            case 2 -> AsciiPackCodec.decode(text);
            case 3 -> AsciiStreamCodec.decodeLegacyV3(text);
            case 4 -> AsciiStreamCodec.decodeLegacyV4(WaypointCodec.unescapeHypixelEmotes(text));
            default -> AsciiStreamCodec.decode(WaypointCodec.unescapeHypixelEmotes(text));
        };
        byte[] dictionary = version == 9 ? V9CodecDictionary.BYTES : CodecDictionary.BYTES;
        byte[] framed = inflate(compressed, dictionary);
        if (version != 8 && version != 9) {
            return new PayloadParts(compressed, framed, null);
        }
        if (framed.length < Integer.BYTES) {
            throw new IllegalStateException("checksummed fixture is too short");
        }
        int bodyLength = framed.length - Integer.BYTES;
        byte[] body = Arrays.copyOf(framed, bodyLength);
        byte[] checksum = Arrays.copyOfRange(framed, bodyLength, framed.length);
        CRC32 crc = new CRC32();
        crc.update(body);
        long expected = ((long) (checksum[0] & 0xFF) << 24)
                | ((long) (checksum[1] & 0xFF) << 16)
                | ((long) (checksum[2] & 0xFF) << 8)
                | (long) (checksum[3] & 0xFF);
        if (crc.getValue() != expected) {
            throw new IllegalStateException("fixture CRC-32 mismatch");
        }
        return new PayloadParts(compressed, body, checksum);
    }

    private static byte[] deflate(byte[] raw, byte[] dictionary) throws IOException {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setDictionary(dictionary);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(output, deflater)) {
            stream.write(raw);
        } finally {
            deflater.end();
        }
        return output.toByteArray();
    }

    private static byte[] inflate(byte[] compressed, byte[] dictionary)
            throws DataFormatException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            inflater.setDictionary(dictionary);
            ByteArrayOutputStream output = new ByteArrayOutputStream(compressed.length * 2);
            byte[] buffer = new byte[256];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IllegalArgumentException("truncated fixture stream");
                    }
                    throw new IllegalArgumentException("fixture inflater made no progress");
                }
                output.write(buffer, 0, count);
            }
            if (inflater.getRemaining() != 0) {
                throw new IllegalArgumentException("fixture has trailing compressed bytes");
            }
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private record VectorSpec(String id, int wireVersion, Integer v9ContentKind, String code) {}

    private record PayloadParts(byte[] compressed, byte[] body, byte[] crc32) {}
}
