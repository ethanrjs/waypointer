package com.babbur.waypointer.screen.settings;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsSearchTest {

    @Test
    void blankQueryReturnsNothing() {
        assertTrue(SettingsSearch.search(null, SettingsCatalog.categories()).isEmpty());
        assertTrue(SettingsSearch.search("", SettingsCatalog.categories()).isEmpty());
        assertTrue(SettingsSearch.search("   ", SettingsCatalog.categories()).isEmpty());
    }

    @Test
    void tierOrderingLabelBeatsAliasBeatsCategoryBeatsTooltipBeatsSubsequence() {
        List<String> aliases = List.of("esp");
        assertEquals(0, SettingsSearch.tierFor("tracer", "show tracers", aliases, "", "tracers", ""));
        assertEquals(1, SettingsSearch.tierFor("esp", "show tracers", aliases, "", "tracers", ""));
        assertEquals(2, SettingsSearch.tierFor("tracers", "opacity", List.of(), "", "tracers", ""));
        assertEquals(3, SettingsSearch.tierFor("crosshair", "show tracers", List.of(),
                "draw lines from crosshair to active waypoints.", "", ""));
        assertEquals(4, SettingsSearch.tierFor("trcr", "tracer color", List.of(), "", "", ""));
        assertEquals(-1, SettingsSearch.tierFor("zzz", "show tracers", aliases, "", "tracers", ""));
    }

    @Test
    void subsequenceMatchingIsGatedToFourCharacterTokens() {
        // Three-character tokens no longer fuzz onto everything: "shw" is a
        // subsequence of "show tracers" but must not match.
        assertEquals(-1, SettingsSearch.tierFor("shw", "show tracers", List.of(), "", "", ""));
        assertEquals(4, SettingsSearch.tierFor("shwt", "show tracers", List.of(), "", "", ""));
    }

    @Test
    void multiTokenQueriesAndTogetherAndRankByWorstToken() {
        Setting tracerOpacity = SettingsCatalog.byId("tracerOpacity");
        assertEquals(1, SettingsSearch.entryTier(new String[]{"tracer", "fade"},
                tracerOpacity, "Tracers", null), "fade only matches via alias, so the worst tier wins");
        assertEquals(-1, SettingsSearch.entryTier(new String[]{"tracer", "zzz"},
                tracerOpacity, "Tracers", null));
    }

    @Test
    void espFindsTracersFirst() {
        List<SettingsSearch.Match> matches = SettingsSearch.search("esp", SettingsCatalog.categories());
        assertFalse(matches.isEmpty());
        assertEquals("showTracer", matches.get(0).setting().id());
    }

    @Test
    void colorQuerySurfacesEveryColorSetting() {
        List<SettingsSearch.Match> matches = SettingsSearch.search("color", SettingsCatalog.categories());
        List<String> ids = matches.stream().map(m -> m.setting().id()).toList();
        assertTrue(ids.contains("defaultWaypointColor"));
        assertTrue(ids.contains("tracerColor"));
        assertTrue(ids.contains("routeLineColor"));
        assertTrue(ids.contains("dungeonEntryPathColor"));
        assertTrue(ids.contains("importedRouteDefaultColor"));
    }

    @Test
    void performanceQuerySurfacesImpactRatedSettings() {
        List<SettingsSearch.Match> matches = SettingsSearch.search("performance", SettingsCatalog.categories());
        List<String> ids = matches.stream().map(m -> m.setting().id()).toList();
        assertTrue(ids.contains("maxWaypointLabels"));
        assertTrue(ids.contains("maxStaticWaypointRenderDistance"));
        assertTrue(ids.contains("showWaypointNames"));
        assertTrue(ids.contains("useBeaconBeamTextures"));
    }

    @Test
    void categoryNameSurfacesTheWholeFamily() {
        List<SettingsSearch.Match> matches = SettingsSearch.search("chat", SettingsCatalog.categories());
        List<String> ids = matches.stream().map(m -> m.setting().id()).toList();
        assertTrue(ids.contains("chatCoordDetection"));
        assertTrue(ids.contains("autoAddChatTempWaypoints"));
        assertTrue(ids.contains("showContributorBadges"), "category-label tier catches label-less matches");
    }

    @Test
    void resultsAreSortedByTierThenCatalogOrder() {
        List<SettingsSearch.Match> matches = SettingsSearch.search("tracer", SettingsCatalog.categories());
        assertFalse(matches.isEmpty());
        assertEquals("showTracer", matches.get(0).setting().id(),
                "first tier-0 label match in catalog order");
        int previousTier = 0;
        for (SettingsSearch.Match match : matches) {
            assertTrue(match.tier() >= previousTier, "tiers must be non-decreasing");
            previousTier = match.tier();
        }
    }

    @Test
    void hiddenEntriesNeverMatch() {
        List<SettingsSearch.Match> matches =
                SettingsSearch.search("blacklist dungeonWaypointsFeatureEnabled", SettingsCatalog.categories());
        for (SettingsSearch.Match match : matches) {
            assertTrue(match.setting().kind() != Setting.Kind.HIDDEN);
        }
    }

    @Test
    void matchesCarryTheirCategoryForChipsAndJumping() {
        List<SettingsSearch.Match> matches = SettingsSearch.search("esp", SettingsCatalog.categories());
        assertEquals("tracers", matches.get(0).categoryId());
        assertEquals("Tracers", matches.get(0).categoryLabel());
    }
}
