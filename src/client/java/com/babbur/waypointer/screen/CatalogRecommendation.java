package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogInstallState;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.crystal.CrystalHollowsStructureZones;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The Recommended order of the community browser. Routes with an update waiting come first and
 * installed, up-to-date routes last. In between, routes for where the player is lead: the exact
 * place (a Crystal Hollows structure, a mineshaft), then the zone around it, then the places
 * inside or beside it, then everything else. Within each place the most installed routes lead,
 * nudged up for verified publishers, the Mining Cult and recent updates.
 */
final class CatalogRecommendation {
    private static final int UPDATE_FIRST = 0, NOT_INSTALLED = 1, INSTALLED_LAST = 2;
    private static final int HERE = 0, AROUND = 1, NEARBY = 2, ELSEWHERE = 3;

    private CatalogRecommendation() {}

    /**
     * Where the player is, most specific first: e.g. {@code [crystal_hollows_structure_mines_of_divan,
     * crystal_hollows]} or {@code [mineshaft_topaz_1, dwarven_mines]}.
     */
    static List<String> here(Zone current, String structureZoneId) {
        List<String> zones = new ArrayList<>();
        if (structureZoneId != null) zones.add(structureZoneId);
        if (current != null && current != Zone.UNKNOWN) {
            String id = Zone.canonicalId(current.id());
            if (!zones.contains(id)) zones.add(id);
            String parent = parent(id);
            if (parent != null && !zones.contains(parent)) zones.add(parent);
        }
        return List.copyOf(zones);
    }

    /** The zone a place sits inside, or null: Hollows structures and Glacite mineshafts. */
    static String parent(String zoneId) {
        if (zoneId == null) return null;
        if (CrystalHollowsStructureZones.isStructureZone(zoneId)) return "crystal_hollows";
        if (zoneId.startsWith("mineshaft_")) return "dwarven_mines";
        return null;
    }

    static int place(String routeZoneId, List<String> here) {
        if (here.isEmpty() || routeZoneId == null) return ELSEWHERE;
        String zone = Zone.canonicalId(routeZoneId);
        if (zone.equals(here.getFirst())) return HERE;
        if (here.contains(zone)) return AROUND;
        String parent = parent(zone);
        // Inside where the player is (a structure while in the Hollows), or beside it (another
        // mineshaft while in one).
        if (parent != null && (here.contains(parent))) return NEARBY;
        return ELSEWHERE;
    }

    static int installTier(CatalogInstallState.Action action, boolean recommended) {
        if (action == CatalogInstallState.Action.INSTALLED || action == CatalogInstallState.Action.LOCAL_NEWER) {
            return INSTALLED_LAST;
        }
        if (recommended && (action == CatalogInstallState.Action.UPDATE
                || action == CatalogInstallState.Action.REPAIR)) {
            return UPDATE_FIRST;
        }
        return NOT_INSTALLED;
    }

    /** Mostly installs (on a log scale), plus small nudges for trust and freshness. */
    static double popularity(CatalogRouteSummary route, Instant now) {
        double score = Math.log1p(Math.max(0, route.downloads())) / Math.log(2);
        if (route.publisherVerified()) score += 0.75;
        if (route.hasTag(CatalogRouteSummary.TAG_MINING_CULT)) score += 0.75;
        Instant updated = parse(route.updatedAt());
        if (updated != null && now != null) {
            double days = Math.max(0, Duration.between(updated, now).toHours() / 24.0);
            if (days < 30) score += days <= 7 ? 1.0 : 1.0 * (30 - days) / 23.0;
        }
        return score;
    }

    /**
     * Orders routes for the Recommended sort. {@code byRelevance} keeps the catalog's own order
     * inside each group (search relevance while searching).
     */
    static List<CatalogRouteSummary> recommended(List<CatalogRouteSummary> routes,
                                                 Function<CatalogRouteSummary, CatalogInstallState.Action> actions,
                                                 List<String> here, boolean byRelevance, Instant now) {
        Comparator<CatalogRouteSummary> order = Comparator.comparingInt(
                route -> installTier(actions.apply(route), true));
        if (!byRelevance) {
            order = order.thenComparingInt(route -> place(route.zoneId(), here))
                    .thenComparing(Comparator.comparingDouble(
                            (CatalogRouteSummary route) -> popularity(route, now)).reversed())
                    .thenComparing(route -> route.title().toLowerCase(Locale.ROOT));
        }
        return routes.stream().sorted(order).toList();
    }

    /** Any other sort: installed, up-to-date routes still go last. */
    static List<CatalogRouteSummary> installedLast(List<CatalogRouteSummary> sorted,
                                                   Function<CatalogRouteSummary, CatalogInstallState.Action> actions) {
        return sorted.stream().sorted(Comparator.comparingInt(
                route -> installTier(actions.apply(route), false))).toList();
    }

    private static Instant parse(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return null;
        try {
            return Instant.parse(timestamp);
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
