package com.babbur.waypointer.catalog;

import com.babbur.waypointer.api.ImportOptions;
import com.babbur.waypointer.api.ImportSummary;
import com.babbur.waypointer.api.WaypointerApi;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.codec.WaypointImporter;
import com.babbur.waypointer.core.WaypointGroup;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CatalogRouteInstaller {
    private static final int CATALOG_CODEC_VERSION = 9;
    private static final int MAX_CATALOG_GROUPS = 64;
    private static final int MAX_CATALOG_WAYPOINTS = 10_000;

    private CatalogRouteInstaller() {
    }

    public static PreparedRoute prepare(CatalogRouteDetails details) {
        Objects.requireNonNull(details, "details");
        CatalogRouteSummary expected = details.summary();
        if (expected == null || expected.codecVersion() != CATALOG_CODEC_VERSION) {
            throw new IllegalArgumentException("Catalog route is not wire-v9");
        }

        WaypointCodec.Decoded decoded = WaypointCodec.decodeCanonicalV9(details.payload());
        WaypointImporter.validateCatalogEmbeddedZones(decoded.groups());
        WaypointImporter.ImportResult result = WaypointImporter.validatePreparedImport(
                WaypointImporter.Source.WAYPOINTER, decoded.groups(), decoded.label());

        int groupCount = result.groups().size();
        int waypointCount = result.groups().stream().mapToInt(WaypointGroup::size).sum();
        if (groupCount > MAX_CATALOG_GROUPS || waypointCount > MAX_CATALOG_WAYPOINTS) {
            throw new IllegalArgumentException("Catalog route exceeds install limits");
        }

        Set<String> zones = new LinkedHashSet<>();
        for (WaypointGroup group : result.groups()) zones.add(group.zoneId());
        String derivedZone = zones.size() == 1 ? zones.iterator().next() : "multiple";
        if (groupCount != expected.groupCount()
                || waypointCount != expected.waypointCount()
                || !derivedZone.equals(expected.zoneId())) {
            throw new IllegalArgumentException(
                    "Catalog route metadata does not match its route code");
        }
        return new PreparedRoute(result);
    }

    public static List<WaypointGroup> decodeForPreview(CatalogRouteDetails details) {
        return prepare(details).previewGroups();
    }

    public static ImportSummary install(WaypointerApi api, CatalogRouteDetails details) {
        return install(api, prepare(details));
    }

    public static ImportSummary install(WaypointerApi api, PreparedRoute prepared) {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(prepared, "prepared");
        return api.importPreparedRoutes(prepared.importResult, ImportOptions.defaults());
    }

    public static final class PreparedRoute {
        private final WaypointImporter.ImportResult importResult;

        private PreparedRoute(WaypointImporter.ImportResult importResult) {
            this.importResult = importResult;
        }

        public List<WaypointGroup> previewGroups() {
            return importResult.groups().stream()
                    .map(WaypointGroup::exportSnapshot)
                    .toList();
        }
    }
}
