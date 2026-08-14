package com.babbur.waypointer.catalog;

import com.babbur.waypointer.api.ImportSource;
import com.babbur.waypointer.api.ImportSummary;
import com.babbur.waypointer.codec.WaypointImporter;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.CatalogRouteProvenance;
import com.babbur.waypointer.core.WaypointGroup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CatalogRouteInstaller {
    private static final int MAX_CATALOG_GROUPS = 64;
    private static final int MAX_CATALOG_WAYPOINTS = 10_000;

    private CatalogRouteInstaller() {
    }

    public static PreparedRoute prepare(CatalogRouteDetails details) {
        Objects.requireNonNull(details, "details");
        CatalogRouteSummary expected = details.summary();
        CatalogProtocol.requireInstallable(expected);

        CatalogProtocol.PayloadManifest manifest = CatalogProtocol.inspectPayload(details.payload());
        WaypointImporter.ImportResult result = WaypointImporter.validatePreparedImport(
                WaypointImporter.Source.WAYPOINTER, manifest.groups(), manifest.label());

        int groupCount = result.groups().size();
        int waypointCount = result.groups().stream().mapToInt(WaypointGroup::size).sum();
        if (groupCount > MAX_CATALOG_GROUPS || waypointCount > MAX_CATALOG_WAYPOINTS) {
            throw new IllegalArgumentException("Catalog route exceeds install limits");
        }

        CatalogProtocol.validateSummaryAgainstPayload(expected, manifest);
        return new PreparedRoute(expected, result, manifest.payloadSha256());
    }

    public static List<WaypointGroup> decodeForPreview(CatalogRouteDetails details) {
        return prepare(details).previewGroups();
    }

    public static ImportSummary install(
            ActiveGroupManager manager, String apiRoot, CatalogRouteDetails details) {
        return install(manager, apiRoot, prepare(details));
    }

    public static ImportSummary install(
            ActiveGroupManager manager, String apiRoot, PreparedRoute prepared) {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(prepared, "prepared");
        String normalizedApiRoot = CatalogProtocol.normalizeApiRoot(apiRoot);

        CatalogInstallState prior = CatalogInstallState.inspect(
                normalizedApiRoot, prepared.summary, manager.allGroups());
        if (!prior.canInstall()) {
            throw new IllegalStateException(
                    "Catalog route cannot replace the installed version: " + prior.action());
        }
        Map<Integer, WaypointGroup> priorGroups = priorGroupsByIndex(
                manager, prior.matchingGroupIds(), prepared.summary,
                prepared.payloadSha256);
        List<WaypointGroup> groups = prepared.detachedGroups(
                normalizedApiRoot, priorGroups);
        manager.replaceGroupsAtomically(
                prior.matchingGroupIds(), groups,
                transferredFolderMemberships(manager, priorGroups, groups),
                replacementAnchors(priorGroups, groups));

        int waypointCount = groups.stream().mapToInt(WaypointGroup::size).sum();
        return new ImportSummary(
                ImportSource.WAYPOINTER, prepared.importResult.label(), groups.size(),
                waypointCount, groups.stream().map(WaypointGroup::id).toList());
    }

    private static Map<Integer, WaypointGroup> priorGroupsByIndex(
            ActiveGroupManager manager, List<String> matchingGroupIds,
            CatalogRouteSummary expected, String expectedPayloadSha256) {
        Map<Integer, WaypointGroup> groups = new LinkedHashMap<>();
        for (String groupId : matchingGroupIds) {
            WaypointGroup group = manager.get(groupId);
            CatalogRouteProvenance provenance = group == null
                    ? null : group.catalogProvenance();
            if (provenance != null) {
                WaypointGroup selected = groups.get(provenance.groupIndex());
                if (selected == null || preferPriorGroup(
                        group, selected, expected, expectedPayloadSha256)) {
                    groups.put(provenance.groupIndex(), group);
                }
            }
        }
        return Map.copyOf(groups);
    }

    private static boolean preferPriorGroup(
            WaypointGroup candidate, WaypointGroup selected,
            CatalogRouteSummary expected, String expectedPayloadSha256) {
        CatalogRouteProvenance candidateSource = candidate.catalogProvenance();
        CatalogRouteProvenance selectedSource = selected.catalogProvenance();
        boolean candidateExact = matchesExpectedInstall(
                candidateSource, expected, expectedPayloadSha256);
        boolean selectedExact = matchesExpectedInstall(
                selectedSource, expected, expectedPayloadSha256);
        if (candidateExact != selectedExact) return candidateExact;
        if (candidateSource.routeVersion() != selectedSource.routeVersion()) {
            return candidateSource.routeVersion() > selectedSource.routeVersion();
        }
        boolean candidateHash = candidateSource.payloadSha256()
                .equals(expectedPayloadSha256);
        boolean selectedHash = selectedSource.payloadSha256()
                .equals(expectedPayloadSha256);
        if (candidateHash != selectedHash) return candidateHash;
        return candidateSource.codecVersion() == expected.codecVersion()
                && selectedSource.codecVersion() != expected.codecVersion();
    }

    private static boolean matchesExpectedInstall(
            CatalogRouteProvenance source, CatalogRouteSummary expected,
            String expectedPayloadSha256) {
        return source.routeVersion() == expected.version()
                && source.codecVersion() == expected.codecVersion()
                && source.groupCount() == expected.groupCount()
                && source.payloadSha256().equals(expectedPayloadSha256);
    }

    private static Map<String, String> transferredFolderMemberships(
            ActiveGroupManager manager, Map<Integer, WaypointGroup> priorGroups,
            List<WaypointGroup> replacements) {
        Map<String, String> transfers = new LinkedHashMap<>();
        for (int index = 0; index < replacements.size(); index++) {
            WaypointGroup previous = priorGroups.get(index);
            WaypointGroup replacement = replacements.get(index);
            if (previous == null || !previous.zoneId().equals(replacement.zoneId())) continue;
            String folderId = manager.folderIdForGroup(previous.id());
            if (folderId != null) transfers.put(replacement.id(), folderId);
        }
        return Map.copyOf(transfers);
    }

    private static Map<String, String> replacementAnchors(
            Map<Integer, WaypointGroup> priorGroups,
            List<WaypointGroup> replacements) {
        Map<String, String> anchors = new LinkedHashMap<>();
        for (int index = 0; index < replacements.size(); index++) {
            WaypointGroup previous = priorGroups.get(index);
            if (previous != null) {
                anchors.put(previous.id(), replacements.get(index).id());
            }
        }
        return Map.copyOf(anchors);
    }

    public static final class PreparedRoute {
        private final CatalogRouteSummary summary;
        private final WaypointImporter.ImportResult importResult;
        private final String payloadSha256;

        private PreparedRoute(
                CatalogRouteSummary summary,
                WaypointImporter.ImportResult importResult,
                String payloadSha256) {
            this.summary = summary;
            this.importResult = importResult;
            this.payloadSha256 = payloadSha256;
        }

        public List<WaypointGroup> previewGroups() {
            return importResult.groups().stream()
                    .map(WaypointGroup::exportSnapshot)
                    .toList();
        }

        public CatalogRouteSummary summary() {
            return summary;
        }

        private List<WaypointGroup> detachedGroups(
                String apiRoot, Map<Integer, WaypointGroup> priorGroups) {
            List<WaypointGroup> detached = new ArrayList<>(importResult.groups().size());
            int groupCount = importResult.groups().size();
            for (int index = 0; index < groupCount; index++) {
                WaypointGroup group = importResult.groups().get(index).exportSnapshot();
                group.setCatalogProvenance(new CatalogRouteProvenance(
                        apiRoot, summary.id(), summary.version(), summary.codecVersion(),
                        payloadSha256, index, groupCount));
                WaypointGroup previous = priorGroups.get(index);
                if (previous != null) {
                    group.setEnabled(previous.enabled());
                }
                detached.add(group);
            }
            WaypointImporter.ImportResult validated = WaypointImporter.validatePreparedImport(
                    importResult.source(), detached, importResult.label());
            return List.copyOf(validated.groups());
        }
    }
}
