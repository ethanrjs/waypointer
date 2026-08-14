package com.babbur.waypointer.catalog;

import com.babbur.waypointer.core.CatalogRouteProvenance;
import com.babbur.waypointer.core.WaypointGroup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record CatalogInstallState(
        Action action, int localVersion, List<String> matchingGroupIds) {

    public CatalogInstallState {
        action = Objects.requireNonNull(action, "action");
        matchingGroupIds = List.copyOf(matchingGroupIds);
    }

    public static CatalogInstallState inspect(
            String apiRoot,
            CatalogRouteSummary route,
            Collection<WaypointGroup> groups) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(groups, "groups");
        String normalizedApiRoot = CatalogProtocol.normalizeApiRoot(apiRoot);

        List<MatchedGroup> matches = new ArrayList<>();
        int localVersion = 0;
        for (WaypointGroup group : groups) {
            CatalogRouteProvenance provenance = group.catalogProvenance();
            if (provenance == null
                    || !provenance.belongsTo(normalizedApiRoot, route.id())) {
                continue;
            }
            matches.add(new MatchedGroup(group.id(), provenance));
            localVersion = Math.max(localVersion, provenance.routeVersion());
        }
        List<String> matchingIds = matches.stream().map(MatchedGroup::groupId).toList();

        if (route.codecVersion() != CatalogProtocol.currentCodecVersion()) {
            return new CatalogInstallState(Action.UNSUPPORTED_CODEC, localVersion, matchingIds);
        }
        if (matches.isEmpty()) {
            return new CatalogInstallState(Action.INSTALL, 0, List.of());
        }
        if (localVersion > route.version()) {
            return new CatalogInstallState(Action.LOCAL_NEWER, localVersion, matchingIds);
        }
        if (localVersion < route.version()) {
            return new CatalogInstallState(Action.UPDATE, localVersion, matchingIds);
        }

        List<MatchedGroup> current = matches.stream()
                .filter(match -> match.provenance().routeVersion() == route.version())
                .sorted(Comparator.comparingInt(match -> match.provenance().groupIndex()))
                .toList();
        Set<Integer> indexes = new HashSet<>();
        Set<String> payloadHashes = new HashSet<>();
        boolean complete = matches.size() == route.groupCount()
                && current.size() == route.groupCount();
        for (MatchedGroup match : current) {
            CatalogRouteProvenance provenance = match.provenance();
            complete &= provenance.codecVersion() == route.codecVersion();
            complete &= provenance.groupCount() == route.groupCount();
            complete &= indexes.add(provenance.groupIndex());
            payloadHashes.add(provenance.payloadSha256());
        }
        complete &= indexes.size() == route.groupCount();
        complete &= payloadHashes.size() == 1;
        for (int index = 0; complete && index < route.groupCount(); index++) {
            complete = indexes.contains(index);
        }
        return new CatalogInstallState(
                complete ? Action.INSTALLED : Action.REPAIR, localVersion, matchingIds);
    }

    public boolean canInstall() {
        return action == Action.INSTALL || action == Action.REPAIR || action == Action.UPDATE;
    }

    public enum Action {
        INSTALL,
        REPAIR,
        UPDATE,
        INSTALLED,
        LOCAL_NEWER,
        UNSUPPORTED_CODEC
    }

    private record MatchedGroup(String groupId, CatalogRouteProvenance provenance) {
    }
}
