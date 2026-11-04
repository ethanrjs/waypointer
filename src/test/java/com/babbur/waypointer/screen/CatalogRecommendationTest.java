package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogInstallState;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CatalogRecommendationTest {
    private static final Instant NOW = Instant.parse("2026-10-17T20:00:00Z");
    private static final String OLD = "2026-06-01T00:00:00Z";

    private static CatalogRouteSummary route(String id, String zone, long downloads) {
        return new CatalogRouteSummary(id, "Route " + id, "", "Someone", "", false, "public", zone, zone,
                10, 1, 9, 1, downloads, OLD, OLD, "/r/" + id, List.of());
    }

    @Test
    void theExactPlaceLeadsThenItsZoneThenEverythingElseByInstalls() {
        var here = CatalogRecommendation.here(Zone.fromId("crystal_hollows"), "crystal_hollows_structure_mines_of_divan");
        var divan = route("divan", "crystal_hollows_structure_mines_of_divan", 3);
        var hollowsPopular = route("ch1", "crystal_hollows", 900);
        var hollows = route("ch2", "crystal_hollows", 40);
        var temple = route("temple", "crystal_hollows_structure_jungle_temple", 500);
        var hub = route("hub", "hub", 5000);
        var ordered = CatalogRecommendation.recommended(List.of(hub, hollows, temple, divan, hollowsPopular),
                r -> null, here, false, NOW);
        assertEquals(List.of(divan, hollowsPopular, hollows, temple, hub), ordered);
    }

    @Test
    void updatesComeFirstAndInstalledRoutesAlwaysLast() {
        var here = CatalogRecommendation.here(Zone.fromId("hub"), null);
        var installed = route("a", "hub", 9999);
        var update = route("b", "the_park", 1);
        var fresh = route("c", "hub", 10);
        Map<String, CatalogInstallState.Action> state = Map.of(
                "a", CatalogInstallState.Action.INSTALLED, "b", CatalogInstallState.Action.UPDATE);
        var ordered = CatalogRecommendation.recommended(List.of(installed, fresh, update),
                r -> state.get(r.id()), here, false, NOW);
        assertEquals(List.of(update, fresh, installed), ordered);
        assertEquals(List.of(fresh, update, installed), CatalogRecommendation.installedLast(
                List.of(installed, fresh, update), r -> state.get(r.id())), "other sorts keep their order, installed last");
    }

    @Test
    void searchKeepsTheCatalogsRelevanceInsideEachGroup() {
        var a = route("a", "hub", 1);
        var b = route("b", "the_end", 1000);
        assertEquals(List.of(a, b), CatalogRecommendation.recommended(List.of(a, b), r -> null,
                CatalogRecommendation.here(Zone.fromId("the_end"), null), true, NOW));
    }

    @Test
    void mineshaftsSitInsideTheDwarvenMines() {
        var here = CatalogRecommendation.here(Zone.fromId("mineshaft_topaz_1"), null);
        assertEquals(List.of("mineshaft_topaz_1", "dwarven_mines"), here);
        assertTrue(CatalogRecommendation.place("mineshaft_jade_1", here)
                < CatalogRecommendation.place("hub", here), "another mineshaft beats an unrelated zone");
    }

    @Test
    void trustAndFreshnessNudgeButInstallsDecide() {
        var fresh = new CatalogRouteSummary("f", "F", "", "x", "", true, "public", "hub", "Hub", 1, 1, 9, 1, 10,
                OLD, "2026-10-16T00:00:00Z", "/r/f", List.of(CatalogRouteSummary.TAG_MINING_CULT));
        assertTrue(CatalogRecommendation.popularity(fresh, NOW) > CatalogRecommendation.popularity(route("o", "hub", 10), NOW));
        assertTrue(CatalogRecommendation.popularity(route("big", "hub", 1000), NOW) > CatalogRecommendation.popularity(fresh, NOW));
    }
}
