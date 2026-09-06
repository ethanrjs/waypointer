package com.babbur.waypointer.screen.settings;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        assertEquals("appearance", matches.get(0).categoryId());
        assertEquals("Appearance", matches.get(0).categoryLabel());
    }

    @Test
    void localizedLabelsCategoriesAndGroupsAreSearchable() {
        Map<String, String> french = Map.of(
                "waypointer.settings.setting.showTracer.label", "Afficher les traceurs",
                "waypointer.settings.category.appearance", "Apparence",
                "waypointer.settings.group.appearance.tracers", "Traceurs");
        SettingsSearch.TranslationResolver resolver =
                (key, fallback) -> french.getOrDefault(key, fallback);

        List<SettingsSearch.Match> labelMatches = SettingsSearch.search(
                "afficher", SettingsCatalog.categories(), resolver);
        assertEquals("showTracer", labelMatches.getFirst().setting().id());

        SettingsSearch.Match groupMatch = SettingsSearch.search(
                        "traceurs", SettingsCatalog.categories(), resolver).stream()
                .filter(match -> match.setting().id().equals("tracerOpacity"))
                .findFirst().orElseThrow();
        assertEquals("Apparence", groupMatch.categoryLabel());
        assertEquals("Traceurs", groupMatch.groupLabel());
    }

    @Test
    void actionButtonsAreSearchableByTheirTranslatedText() {
        Map<String, String> french = Map.of(
                "waypointer.screen.settings.config.copy", "Copier le code",
                "waypointer.screen.settings.config.import", "Importer le code",
                "waypointer.screen.settings.action.open_painter", "Ouvrir le peintre",
                "waypointer.screen.settings.preset.nothing", "Tout désactiver");
        SettingsSearch.TranslationResolver resolver =
                (key, fallback) -> french.getOrDefault(key, fallback);

        for (String query : List.of("copier", "importer")) {
            assertEquals(SettingsCatalog.ACTION_CONFIG_CODE,
                    SettingsSearch.search(query, SettingsCatalog.categories(), resolver)
                            .getFirst().setting().id());
        }
        assertEquals(SettingsCatalog.ACTION_WAYPOINT_PAINT,
                SettingsSearch.search("ouvrir peintre", SettingsCatalog.categories(), resolver)
                        .getFirst().setting().id());
        assertEquals(SettingsCatalog.ACTION_PRESETS,
                SettingsSearch.search("tout désactiver", SettingsCatalog.categories(), resolver)
                        .getFirst().setting().id());
    }

    @Test
    void settingNamesRankBeforeActionNamesAndButtonText() {
        Setting action = Setting.action("action", "Smooth action", "")
                .buttons(Map.of("button", "Smooth button"));
        Setting option = Setting.enumCycle("option", Setting.Store.MAIN, "Style", "",
                List.of(new Setting.EnumOption("smooth", "Smooth", "smooth")), null, null);
        Setting named = Setting.bool("named", Setting.Store.MAIN, "Smooth lines", "", null, null);
        List<SettingsCatalog.Category> categories = List.of(SettingsCatalog.Category.of(
                "test", "Test", SettingsCatalog.Group.plain(action, option, named)));

        assertEquals(List.of("named", "action", "option"), SettingsSearch.search("smooth", categories)
                .stream().map(match -> match.setting().id()).toList());
    }

    @Test
    void enumButtonLabelsUseCurrentAndLegacyTranslations() {
        Setting style = SettingsCatalog.byId("boxStyle");
        for (String key : List.of(style.enumOptionTranslationKey(0), style.legacyEnumOptionTranslationKey(0))) {
            List<SettingsSearch.Match> matches = SettingsSearch.search("kontur",
                    SettingsCatalog.categories(),
                    (translationKey, fallback) -> translationKey.equals(key) ? "Kontur" : fallback);
            assertEquals("boxStyle", matches.getFirst().setting().id());
            assertEquals(1, matches.getFirst().tier());
        }
    }
}
