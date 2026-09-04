package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Opt-in, fail-closed size benchmark using production share writers and decoders.
 * Run {@code test --tests '*CodecRouteBenchmarkTest' -Pcodec.routes=/external/routes.json
 * -Pcodec.report=/external/report.json}. The raw corpus must stay outside the repository.
 * JSON and sibling CSV outputs count complete UTF-8 bytes, code points, and Java string chars.
 */
class CodecRouteBenchmarkTest {
    private static final WaypointCodec.Options COORDS_V9 = WaypointCodec.Options.BARE_COORDINATES
            .toBuilder().bareCoordinatesOnly(false).build();
    private static final WaypointCodec.Options COMMON = WaypointCodec.Options.builder()
            .includeNames(true).includeColors(true).includeRadii(false)
            .includeWaypointFlags(false).includeGroupMeta(false).includeZone(true).build();
    private static final List<WaypointExportCodec.Target> PEERS = List.of(
            WaypointExportCodec.Target.SKYBLOCKER, WaypointExportCodec.Target.SKYTILS,
            WaypointExportCodec.Target.SKYHANNI, WaypointExportCodec.Target.CHUNKLOGGER);

    record Wire(int utf8Bytes, int chars, int codePoints, int maximumSampleUtf8Bytes,
                int maximumSampleChars, int samples, String format, long firstExportEpochMillis,
                long lastExportEpochMillis) {}

    record Row(String cohort, int sourceIndex, String coordinateHash, String normalizedHash,
               String split, int points, String projection, String competitor, String status,
               String detail, boolean uniqueCoordinates, boolean uniqueNormalized,
               Wire v10, Wire other) {}

    @Test
    @EnabledIfSystemProperty(named = "codec.routes", matches = ".+")
    void routeSizeReport() throws Exception {
        CodecRouteCorpus.Corpus corpus = CodecRouteCorpus.load(Path.of(System.getProperty("codec.routes")));
        List<Row> rows = new ArrayList<>();
        Set<String> syntheticCoordinates = new HashSet<>();
        for (CodecRouteCorpus.Route route : corpus.routes()) {
            measure(rows, route, route.group(), "real", route.firstCoordinates(), route.firstNormalized());
            // Prefixes are derived stress cases, not additional real routes or independent holdout data.
            if (route.firstCoordinates()) {
                for (int count : new int[]{1, 2, 3, 5, 10}) {
                    if (count >= route.group().size()) continue;
                    WaypointGroup prefix = copy(route.group(), count, false, false);
                    String hash = CodecRouteCorpus.hashCoordinates(prefix);
                    measure(rows, route, prefix, "synthetic_prefix_" + count,
                            syntheticCoordinates.add(hash), false);
                }
            }
        }
        List<Map<String, Object>> summaries = summarize(rows);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("createdAt", Instant.now().toString());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("integrity", corpus.integrity());
        report.put("methodology", List.of(
                "Every measured string comes from an existing production writer, except two explicitly generic JSON controls.",
                "Sizes include the complete share string and all prefixes, JSON syntax, checksums, base64, and escaping; no UI quotation marks or clipboard newline.",
                "UTF-8 bytes are primary; chars are Java UTF-16 code units; Unicode code points are also recorded.",
                "Every production sample is decoded by the production decoder and checked for exact ordered block and precise coordinates; unexpected failure fails the test.",
                "coordinates compares a native coordinate-only projection against peers with names/colors disabled; mandatory peer route name/island/default fields remain and are disclosed per row.",
                "common_SKYBLOCKER/common_SKYTILS_V1 preserve group name, mapped island, point names, 8-bit colors, and point order on both sides; other settings are projected to defaults.",
                "Skytils cannot encode unknown or newer unsupported islands. Such cases are explicit unsupported rows excluded from matched totals; no substitute island is used.",
                "Common projections apply the production peer label sanitizer to group and point names on both sides; source names stay intact in native full fidelity. Row details count changed names.",
                "Skytils substitutes Unnamed for blank point names; the matching native common projection explicitly makes that same substitution after sanitization.",
                "SkyHanni here means Waypointer's production raw JSON export, not a claim about the smallest representation any SkyHanni version accepts.",
                "Skytils here means the production V1 gzip export. The importer also accepts Skytils V2 Brotli, which this production-writer benchmark does not measure; no strongest-Skytils claim is supported.",
                "GENERIC_COORDINATE_JSON and GENERIC_COORDINATE_JSON_GZIP_BASE64 are minimal x/y/z controls, not named peer codecs; gzip/base64 has no additional transport prefix.",
                "Skytils embeds System.currentTimeMillis. Three unmodified production exports are checked; the smallest sample favors the peer, and maximum lengths/time bounds expose variability.",
                "Native full fidelity is reported separately against V9 and means normalized native-model fidelity, not preservation of raw RGB decimals or sourceUrl/type provenance.",
                "Real source-weighted totals include every record. Real deduplicated totals use ordered-coordinate hashes for coordinates and normalized metadata hashes otherwise.",
                "Deduplicated populations retain the FIRST source occurrence. Mandatory peer names/islands can make peer sizes or eligibility vary between geometry duplicates, so these peer deduplicated totals depend on input order; source-weighted totals are the principal comparison.",
                "Real split: unsigned first 32 SHA-256 coordinate-hash bits modulo 5 == 0 gives holdout (approximately 20%); coordinate duplicates stay together regardless of names/islands.",
                "Holdout is deterministic reporting only: this corpus has the same route/point counts as the pre-existing V10LockedCorpusTest, so independence from historical tuning is NOT established.",
                "Synthetic 1/2/3/5/10-point prefixes retain the first N points from unique real coordinate routes. They have no statistical holdout split; deduplicated synthetic totals collapse identical prefixes.",
                "No latency/CPU benchmark or claim of representative gameplay is made."));
        report.put("summaries", summaries);
        report.put("rows", rows);
        String reportProperty = System.getProperty("codec.report");
        Path destination = reportProperty == null || reportProperty.isBlank()
                ? Path.of("build", "reports", "codec-route-benchmark.json") : Path.of(reportProperty);
        Path absolute = destination.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Files.writeString(absolute, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .create().toJson(report), StandardCharsets.UTF_8);
        Path csv = absolute.resolveSibling(absolute.getFileName().toString().replaceFirst("\\.json$", "") + ".csv");
        Files.writeString(csv, csv(rows), StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "Codec corpus: routes=%s points=%s SHA256=%s%n",
                corpus.integrity().get("routeCount"), corpus.integrity().get("pointCount"), corpus.integrity().get("fileSha256"));
        for (Map<String, Object> summary : summaries) {
            if (summary.get("population").equals("real_source_weighted")
                    && summary.get("split").equals("all") && summary.get("pointBucket").equals("all")) {
                System.out.println(summary);
            }
        }
        System.out.println("Complete JSON: " + absolute + "\nPer-route CSV: " + csv);
    }

    private static void measure(List<Row> rows, CodecRouteCorpus.Route route, WaypointGroup source,
                                String cohort, boolean uniqueCoordinates, boolean uniqueNormalized) throws IOException {
        boolean real = cohort.equals("real");
        WaypointGroup coordinates = copy(source, source.size(), true, false);
        Wire v10 = nativeWire(coordinates, WaypointCodec.Options.BARE_COORDINATES, false, false);
        add(rows, route, source, cohort, "coordinates", "V9", "ok", "Same ordered coordinates only",
                uniqueCoordinates, uniqueNormalized, v10, nativeWire(coordinates, COORDS_V9, true, false));
        JsonArray minimal = new JsonArray();
        for (Waypoint point : source.waypoints()) {
            JsonObject json = new JsonObject();
            json.addProperty("x", point.x());
            json.addProperty("y", point.y());
            json.addProperty("z", point.z());
            minimal.add(json);
        }
        String json = minimal.toString();
        String zipped = gzipBase64(json);
        assertEquals(json, ungzipBase64(zipped), "Generic control compression must be lossless");
        assertEquals(minimal, JsonParser.parseString(json));
        add(rows, route, source, cohort, "coordinates", "GENERIC_COORDINATE_JSON", "ok",
                "Generic minimal x/y/z array, no named peer or checksum", uniqueCoordinates, uniqueNormalized,
                v10, wire(json, "raw-json", 1, json.length(), json.getBytes(StandardCharsets.UTF_8).length, 0, 0));
        add(rows, route, source, cohort, "coordinates", "GENERIC_COORDINATE_JSON_GZIP_BASE64", "ok",
                "Generic gzip plus Base64, no prefix, default JDK compression", uniqueCoordinates, uniqueNormalized,
                v10, wire(zipped, "gzip-base64", 1, zipped.length(), zipped.getBytes(StandardCharsets.UTF_8).length, 0, 0));
        for (WaypointExportCodec.Target peer : PEERS) {
            String peerLabel = peer == WaypointExportCodec.Target.SKYTILS ? "SKYTILS_V1" : peer.name();
            String unsupported = unsupported(peer, source);
            if (unsupported != null) {
                add(rows, route, source, cohort, "coordinates", peerLabel, "unsupported", unsupported,
                        uniqueCoordinates, uniqueNormalized, v10, null);
                if (real && peer.supportsNames()) {
                    add(rows, route, source, cohort, "common_" + peerLabel, peerLabel, "unsupported",
                            unsupported, uniqueCoordinates, uniqueNormalized, null, null);
                }
                continue;
            }
            WaypointGroup peerCoordinates = copy(source, source.size(), false, false);
            Wire other = peerWire(peerCoordinates, COORDS_V9, peer, false);
            add(rows, route, source, cohort, "coordinates", peerLabel, "ok", peerExtra(peer),
                    uniqueCoordinates, uniqueNormalized, v10, other);
            if (real && peer.supportsNames()) {
                WaypointGroup common = commonProjection(source, peer);
                long changedNames = (source.name().equals(common.name()) ? 0 : 1)
                        + java.util.stream.IntStream.range(0, source.size())
                        .filter(index -> !source.get(index).name().equals(common.get(index).name())).count();
                Wire nativeCommon = nativeWire(common, COMMON, false, true);
                Wire peerCommon = peerWire(common, COMMON, peer, true);
                add(rows, route, source, cohort, "common_" + peerLabel, peerLabel, "ok",
                        "Same group name/island, point order/names/RGB; explicitly normalized names="
                                + changedNames + "; " + peerExtra(peer),
                        uniqueCoordinates, uniqueNormalized, nativeCommon, peerCommon);
                add(rows, route, source, cohort, "common_" + peerLabel, "V9", "ok",
                        "Same common-feature projection", uniqueCoordinates, uniqueNormalized,
                        nativeCommon, nativeWire(common, COMMON, true, true));
            }
        }
        if (real) {
            Wire full = nativeWire(source, WaypointCodec.Options.FULL_FIDELITY, false, true);
            add(rows, route, source, cohort, "native_full_fidelity", "V9", "ok",
                    "Normalized native model, including names/island/RGB and default group settings; excludes sourceUrl/type",
                    uniqueCoordinates, uniqueNormalized, full,
                    nativeWire(source, WaypointCodec.Options.FULL_FIDELITY, true, true));
        }
    }

    private static WaypointGroup copy(WaypointGroup source, int count, boolean bare, boolean skytilsNames) {
        WaypointGroup result = WaypointGroup.create(bare ? "" : source.name(), bare ? "unknown" : source.zoneId());
        result.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        for (int index = 0; index < count; index++) {
            Waypoint point = source.get(index);
            String name = bare ? "" : skytilsNames && point.name().isEmpty() ? "Unnamed" : point.name();
            result.add(new Waypoint(point.x(), point.y(), point.z(), name,
                    bare ? Waypoint.DEFAULT_COLOR : point.color(), 0, 0.0));
        }
        return result;
    }

    static WaypointGroup commonProjection(WaypointGroup source, WaypointExportCodec.Target target) {
        String groupName = WaypointCodec.Options.sanitizeLabel(source.name());
        if (groupName.isBlank()) {
            groupName = com.babbur.waypointer.core.Zone.fromId(source.zoneId()).displayName();
        }
        WaypointGroup result = WaypointGroup.create(groupName, source.zoneId());
        result.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        for (Waypoint point : source.waypoints()) {
            String name = WaypointCodec.Options.sanitizeLabel(point.name());
            if (target == WaypointExportCodec.Target.SKYTILS && name.isEmpty()) name = "Unnamed";
            result.add(new Waypoint(point.x(), point.y(), point.z(), name, point.color(), 0, 0.0));
        }
        return result;
    }

    private static Wire nativeWire(WaypointGroup source, WaypointCodec.Options options, boolean legacy,
                                   boolean metadata) throws IOException {
        String encoded = legacy ? WaypointCodec.encodeV9ForTest(List.of(source), options)
                : WaypointCodec.encode(List.of(source), options);
        List<WaypointGroup> decoded = WaypointCodec.decode(encoded);
        assertEquals(1, decoded.size());
        exactCoordinates(source, decoded.getFirst());
        if (metadata) exactMetadata(source, decoded.getFirst());
        if (options == WaypointCodec.Options.FULL_FIDELITY) {
            WaypointGroup actual = decoded.getFirst();
            assertEquals(source.loadMode(), actual.loadMode(), "Full-fidelity load mode");
            assertEquals(source.gradientMode(), actual.gradientMode(), "Full-fidelity gradient mode");
            assertEquals(source.routeKind(), actual.routeKind(), "Full-fidelity route kind");
            assertEquals(source.defaultRadius(), actual.defaultRadius(), "Full-fidelity group radius");
            assertEquals(source.skipAheadEnabled(), actual.skipAheadEnabled(), "Full-fidelity skip-ahead");
            for (int index = 0; index < source.size(); index++) {
                assertEquals(source.get(index).flags(), actual.get(index).flags(), "Full-fidelity point flags");
                assertEquals(source.get(index).customRadius(), actual.get(index).customRadius(), "Full-fidelity point radius");
            }
        }
        return wire(encoded, nativeFormat(encoded, legacy), 1, encoded.length(),
                encoded.getBytes(StandardCharsets.UTF_8).length, 0, 0);
    }

    static String nativeFormat(String encoded, boolean legacy) throws IOException {
        if (legacy) return "v9";
        // A first-byte nibble peek alone misclassifies about 1/16 of valid legacy codes.
        // Use the checked production decoder's version, including automatic V9 fallbacks.
        if (WaypointCodec.debugDecode(encoded).version() != WaypointCodec.V10_WIRE_VERSION) return "v9-fallback";
        V10Transport.CheckedFrame frame = V10Transport.probe(encoded.substring(WaypointCodec.MAGIC.length()));
        return "v10-kind" + frame.contentKind() + "-mode" + frame.mode();
    }

    @Test
    void commonProjectionMakesPeerNameChangesExplicitAndPreservesSource() throws IOException {
        WaypointGroup source = WaypointGroup.create("shaft ", "dwarven_mines");
        source.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        source.add(new Waypoint(1, 2, 3, "Waypoint ", 0x336699, 0, 0));
        source.add(new Waypoint(4, 5, 6, "", 0xFFFFFF, 0, 0));
        WaypointGroup common = commonProjection(source, WaypointExportCodec.Target.SKYTILS);
        assertEquals("shaft", common.name());
        assertEquals("Waypoint", common.get(0).name());
        assertEquals("Unnamed", common.get(1).name());
        assertEquals("shaft ", source.name());
        assertEquals("Waypoint ", source.get(0).name());
        nativeWire(common, COMMON, false, true);
        peerWire(common, COMMON, WaypointExportCodec.Target.SKYTILS, true);
    }

    @Test
    void classifiesValidLegacyShareWithoutTreatingAPeekAsProof() throws IOException {
        WaypointGroup group = WaypointGroup.create("", "unknown");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(Waypoint.at(425, 64, -71));
        String encoded = WaypointCodec.encodeV9ForTest(List.of(group), COORDS_V9);
        exactCoordinates(group, WaypointCodec.decode(encoded).getFirst());
        assertEquals("v9", nativeFormat(encoded, true));
        assertEquals("v9-fallback", nativeFormat(encoded, false));
    }

    private static Wire peerWire(WaypointGroup source, WaypointCodec.Options options,
                                 WaypointExportCodec.Target target, boolean metadata) {
        int samples = target == WaypointExportCodec.Target.SKYTILS ? 3 : 1;
        String best = null;
        int maximumBytes = 0;
        int maximumChars = 0;
        long start = System.currentTimeMillis();
        for (int sample = 0; sample < samples; sample++) {
            String encoded = WaypointExportCodec.encode(List.of(source), options, target);
            WaypointImporter.ImportResult result = WaypointImporter.importAny(encoded);
            assertEquals(target.name(), result.source().name(), "Peer source must be recognized exactly");
            assertEquals(1, result.groups().size());
            exactCoordinates(source, result.groups().getFirst());
            if (metadata) exactMetadata(source, result.groups().getFirst());
            int bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
            maximumBytes = Math.max(maximumBytes, bytes);
            maximumChars = Math.max(maximumChars, encoded.length());
            if (best == null || bytes < best.getBytes(StandardCharsets.UTF_8).length) best = encoded;
        }
        return wire(best, target == WaypointExportCodec.Target.SKYHANNI ? "production-raw-json" : target.name(),
                samples, maximumChars, maximumBytes, start, System.currentTimeMillis());
    }

    private static void exactCoordinates(WaypointGroup expected, WaypointGroup actual) {
        assertEquals(expected.size(), actual.size(), "Point count must survive");
        for (int index = 0; index < expected.size(); index++) {
            Waypoint before = expected.get(index);
            Waypoint after = actual.get(index);
            assertEquals(List.of(before.x(), before.y(), before.z(), before.preciseX(), before.preciseY(), before.preciseZ()),
                    List.of(after.x(), after.y(), after.z(), after.preciseX(), after.preciseY(), after.preciseZ()),
                    "Exact ordered coordinates at index " + index);
        }
    }

    private static void exactMetadata(WaypointGroup expected, WaypointGroup actual) {
        assertEquals(expected.name(), actual.name(), "Group name");
        assertEquals(expected.zoneId(), actual.zoneId(), "Zone");
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).name(), actual.get(index).name(), "Point name " + index);
            assertEquals(expected.get(index).color(), actual.get(index).color(), "Point RGB " + index);
        }
    }

    private static Wire wire(String text, String format, int samples, int maximumChars,
                             int maximumBytes, long start, long end) {
        assertFalse(text.isEmpty(), "Empty export is a failure, not a zero-byte win");
        return new Wire(text.getBytes(StandardCharsets.UTF_8).length, text.length(),
                text.codePointCount(0, text.length()), maximumBytes, maximumChars, samples, format, start, end);
    }

    private static String unsupported(WaypointExportCodec.Target target, WaypointGroup source) {
        try {
            if (target == WaypointExportCodec.Target.SKYBLOCKER) WaypointExportCodec.skyblockerIslandId(source.zoneId());
            if (target == WaypointExportCodec.Target.SKYTILS) WaypointExportCodec.skytilsIslandId(source.zoneId());
            return null;
        } catch (IllegalArgumentException failure) {
            // Only this explicit production capability rejection may be omitted from matched totals.
            if (!failure.getMessage().contains("does not recognize") || !failure.getMessage().contains("zone")) throw failure;
            return failure.getMessage() + " (source island preserved: " + source.zoneId() + ")";
        }
    }

    private static String peerExtra(WaypointExportCodec.Target target) {
        return switch (target) {
            case SKYBLOCKER -> "Mandatory group name/island/ordered/render fields, point default RGB/name/alpha/render fields, gzip+Base64+prefix";
            case SKYTILS -> "Mandatory category name/island, point Unnamed/enabled/live addedAt timestamp, gzip+Base64+prefix";
            case SKYHANNI -> "Production RAW JSON; fixed green RGB and generated 1-based step names, no original names/island";
            case CHUNKLOGGER -> "Production raw x/y/z JSON plus empty coal marker per point; no names/island/colors";
            default -> throw new IllegalArgumentException("Expected peer target");
        };
    }

    private static void add(List<Row> rows, CodecRouteCorpus.Route route, WaypointGroup source,
                            String cohort, String projection, String competitor, String status, String detail,
                            boolean uniqueCoordinates, boolean uniqueNormalized, Wire v10, Wire other) {
        rows.add(new Row(cohort, route.index(), CodecRouteCorpus.hashCoordinates(source), route.normalizedHash(),
                cohort.equals("real") ? route.split() : "synthetic_no_holdout", source.size(), projection,
                competitor, status, detail, uniqueCoordinates, uniqueNormalized, v10, other));
    }

    private static List<Map<String, Object>> summarize(List<Row> rows) {
        Map<String, List<Row>> grouped = new LinkedHashMap<>();
        for (Row row : rows) {
            boolean real = row.cohort.equals("real");
            List<String> populations = new ArrayList<>();
            populations.add(real ? "real_source_weighted" : row.cohort + "_source_weighted");
            if (row.projection.equals("coordinates") ? row.uniqueCoordinates : row.uniqueNormalized) {
                populations.add(real ? "real_deduplicated" : row.cohort + "_deduplicated");
            }
            for (String population : populations) {
                for (String split : real ? List.of("all", row.split) : List.of("all")) {
                    for (String bucket : List.of("all", bucket(row.points))) {
                        String key = String.join("|", population, row.projection, row.competitor, split, bucket);
                        grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
                    }
                }
            }
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Map.Entry<String, List<Row>> entry : grouped.entrySet()) {
            String[] key = entry.getKey().split("\\|");
            List<Row> matched = entry.getValue().stream().filter(row -> row.status.equals("ok")).toList();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("population", key[0]);
            summary.put("projection", key[1]);
            summary.put("competitor", key[2]);
            summary.put("split", key[3]);
            summary.put("pointBucket", key[4]);
            summary.put("eligibleRoutes", entry.getValue().size());
            summary.put("matchedRoutes", matched.size());
            summary.put("unsupportedRoutes", entry.getValue().size() - matched.size());
            long nativeBytes = matched.stream().mapToLong(row -> row.v10.utf8Bytes).sum();
            long otherBytes = matched.stream().mapToLong(row -> row.other.utf8Bytes).sum();
            summary.put("v10Utf8Bytes", nativeBytes);
            summary.put("otherUtf8Bytes", otherBytes);
            summary.put("v10Chars", matched.stream().mapToLong(row -> row.v10.chars).sum());
            summary.put("otherChars", matched.stream().mapToLong(row -> row.other.chars).sum());
            summary.put("v10ByteSavingsPercent", otherBytes == 0 ? null : 100.0 * (otherBytes - nativeBytes) / otherBytes);
            summary.put("v10ByteWins", matched.stream().filter(row -> row.v10.utf8Bytes < row.other.utf8Bytes).count());
            summary.put("v10ByteTies", matched.stream().filter(row -> row.v10.utf8Bytes == row.other.utf8Bytes).count());
            summary.put("v10ByteLosses", matched.stream().filter(row -> row.v10.utf8Bytes > row.other.utf8Bytes).count());
            summary.put("v10CharWins", matched.stream().filter(row -> row.v10.chars < row.other.chars).count());
            summary.put("v10CharTies", matched.stream().filter(row -> row.v10.chars == row.other.chars).count());
            summary.put("v10CharLosses", matched.stream().filter(row -> row.v10.chars > row.other.chars).count());
            summary.put("v10Utf8Distribution", distribution(matched.stream().map(row -> row.v10.utf8Bytes).toList()));
            summary.put("otherUtf8Distribution", distribution(matched.stream().map(row -> row.other.utf8Bytes).toList()));
            summary.put("maximumV10MinusOtherBytes", matched.stream().mapToInt(row -> row.v10.utf8Bytes - row.other.utf8Bytes).max().orElse(0));
            summaries.add(summary);
        }
        return summaries;
    }

    private static Map<String, Number> distribution(List<Integer> lengths) {
        if (lengths.isEmpty()) return Map.of();
        List<Integer> sorted = lengths.stream().sorted().toList();
        Map<String, Number> values = new LinkedHashMap<>();
        values.put("min", sorted.getFirst());
        values.put("p50NearestRank", sorted.get((int) Math.ceil(sorted.size() * 0.5) - 1));
        values.put("p90NearestRank", sorted.get((int) Math.ceil(sorted.size() * 0.9) - 1));
        values.put("p95NearestRank", sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1));
        values.put("max", sorted.getLast());
        values.put("mean", sorted.stream().mapToInt(Integer::intValue).average().orElseThrow());
        return values;
    }

    private static String bucket(int points) {
        if (points <= 2) return "1-2";
        if (points <= 5) return "3-5";
        if (points <= 10) return "6-10";
        if (points <= 25) return "11-25";
        if (points <= 100) return "26-100";
        return "101+";
    }

    private static String gzipBase64(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static String ungzipBase64(String text) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(text)))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String csv(List<Row> rows) {
        StringBuilder out = new StringBuilder("cohort,source_index,coordinate_sha256,normalized_sha256,split,points,projection,competitor,status,v10_utf8_bytes,other_utf8_bytes,v10_chars,other_chars,other_max_sample_bytes,detail\n");
        for (Row row : rows) {
            List<String> cells = List.of(row.cohort, Integer.toString(row.sourceIndex), row.coordinateHash,
                    row.normalizedHash, row.split, Integer.toString(row.points), row.projection,
                    row.competitor, row.status, row.v10 == null ? "" : Integer.toString(row.v10.utf8Bytes),
                    row.other == null ? "" : Integer.toString(row.other.utf8Bytes),
                    row.v10 == null ? "" : Integer.toString(row.v10.chars),
                    row.other == null ? "" : Integer.toString(row.other.chars),
                    row.other == null ? "" : Integer.toString(row.other.maximumSampleUtf8Bytes), row.detail);
            out.append(cells.stream().map(cell -> "\"" + cell.replace("\"", "\"\"") + "\"")
                    .collect(java.util.stream.Collectors.joining(","))).append('\n');
        }
        return out.toString();
    }
}
