package com.babbur.waypointer.screen.settings;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity and round-trip guards for the settings catalog.
 *
 * <p>The catalog is the single source of truth for the settings UI and the
 * config-code import diff. These tests pin it to the actual config classes in
 * both directions: a new {@link WaypointerConfig} field fails here until it is
 * either cataloged or consciously exempted, and a cataloged entry that the
 * codec silently drops fails the per-entry round-trip. This is the guard that
 * would have caught the old {@code countChangedSettings} missing
 * {@code showContributorBadges}.
 */
class SettingsCatalogTest {

    /** Internal-only WaypointerConfig fields with no settings-surface meaning. */
    private static final Set<String> MAIN_EXEMPT = Set.of(
            "configSchemaVersion",
            "waypointPainterPalette",
            "waypointPainterDefaultPalette",
            "waypointPainterDefaultPixels");

    /** DungeonConfig fields deliberately kept out of the GUI (debug/UX-state). */
    private static final Set<String> DUNGEON_EXEMPT = Set.of(
            "debugLogRoomChanges", "routesPromptDismissed", "hiddenRouteRoomIds",
            // Assumed room rotation is applied automatically / via /wpd; deliberately not surfaced in the GUI.
            "defaultDirection");

    @Test
    void mainEntriesCoverEveryWaypointerConfigField() {
        Set<String> fields = persistedFieldNames(WaypointerConfig.class, MAIN_EXEMPT);
        Set<String> catalogIds = idsByStore(Setting.Store.MAIN);
        assertEquals(fields, catalogIds,
                "WaypointerConfig fields and catalog MAIN ids must match exactly; "
                        + "catalog new fields or add them to MAIN_EXEMPT deliberately");
    }

    @Test
    void dungeonEntriesCoverEveryPlayerFacingDungeonConfigField() {
        Set<String> fields = persistedFieldNames(DungeonConfig.class, DUNGEON_EXEMPT);
        Set<String> catalogIds = idsByStore(Setting.Store.DUNGEON);
        assertEquals(fields, catalogIds);
    }

    @Test
    void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Setting setting : SettingsCatalog.allSettings()) {
            assertTrue(seen.add(setting.id()), "duplicate setting id: " + setting.id());
        }
    }

    @Test
    void everyVisibleEntryHasALabelAndNoWhitespaceOnlyTooltip() {
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.kind() == Setting.Kind.HIDDEN) continue;
            assertFalse(setting.label().isBlank(), setting.id() + " needs a label");
            // Tooltips are optional -- self-explanatory rows deliberately omit
            // them -- but a whitespace-only tooltip is a mistake.
            assertEquals(setting.tooltip().isEmpty(), setting.tooltip().isBlank(),
                    setting.id() + " has a whitespace-only tooltip");
        }
    }

    @Test
    void enumEntriesListDistinctOptionsIncludingTheDefault() {
        WaypointerConfig defaults = new WaypointerConfig();
        DungeonConfig dungeonDefaults = new DungeonConfig();
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.kind() != Setting.Kind.ENUM) continue;
            List<Setting.EnumOption> options = setting.enumOptions();
            assertTrue(options.size() >= 2, setting.id() + " needs at least two options");
            Set<Object> values = new HashSet<>();
            for (Setting.EnumOption option : options) {
                assertFalse(option.label().isBlank());
                assertTrue(values.add(option.value()), setting.id() + " has duplicate option values");
            }
            Object defaultValue = setting.defaultValue(defaults, dungeonDefaults);
            assertTrue(values.contains(defaultValue),
                    setting.id() + " default " + defaultValue + " is not among its options");
        }
    }

    @Test
    void parentedGroupsLeadWithTheirParentAndMastersExist() {
        for (SettingsCatalog.Category category : SettingsCatalog.categories()) {
            Set<String> categoryIds = new HashSet<>();
            for (SettingsCatalog.Group group : category.groups()) {
                for (Setting setting : group.settings()) categoryIds.add(setting.id());
                if (group.parentSettingId() != null) {
                    assertEquals(group.parentSettingId(), group.settings().get(0).id(),
                            "parented group in " + category.id() + " must lead with its parent");
                    assertNotNull(group.childrenVisibleWhen(),
                            "parented group in " + category.id() + " needs a collapse predicate");
                }
            }
            if (category.masterSettingId() != null) {
                assertTrue(categoryIds.contains(category.masterSettingId()),
                        category.id() + " master must be one of its own settings");
                assertNotNull(category.bodyVisibleWhen());
            }
        }
    }

    @Test
    void everyMainEntrySurvivesTheCodecRoundTrip() {
        WaypointerConfig defaults = new WaypointerConfig();
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.store() != Setting.Store.MAIN) continue;

            WaypointerConfig live = new WaypointerConfig();
            Object probe = probeValue(setting, defaults);
            setting.set(live, null, probe);
            assertNotEquals(setting.get(defaults, null), setting.get(live, null),
                    setting.id() + " probe did not change the live value (clamped back to default?)");

            assertEquals(1, SettingsCatalog.countChangedSettings(defaults, live),
                    setting.id() + " probe should register as exactly one changed setting");

            WaypointerConfig decoded = WaypointerConfigCodec.decode(WaypointerConfigCodec.encode(live));
            assertEquals(setting.get(live, null), setting.get(decoded, null),
                    setting.id() + " did not survive the config-code round trip");
        }
    }

    @Test
    void probingEveryMainEntryCountsThemAllAndRoundTripsToZeroDiff() {
        WaypointerConfig defaults = new WaypointerConfig();
        WaypointerConfig live = new WaypointerConfig();
        int mainEntries = 0;
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.store() != Setting.Store.MAIN) continue;
            setting.set(live, null, probeValue(setting, defaults));
            mainEntries++;
        }
        assertEquals(mainEntries, SettingsCatalog.countChangedSettings(defaults, live));

        WaypointerConfig decoded = WaypointerConfigCodec.decode(WaypointerConfigCodec.encode(live));
        assertEquals(0, SettingsCatalog.countChangedSettings(live, decoded));
    }

    @Test
    void hiddenLegacyEntriesStillCountInTheImportDiff() {
        WaypointerConfig defaults = new WaypointerConfig();
        WaypointerConfig decoded = new WaypointerConfig();
        decoded.setDungeonWaypointsFeatureEnabled(true);
        assertEquals(1, SettingsCatalog.countChangedSettings(defaults, decoded));

        WaypointerConfig withBadges = new WaypointerConfig();
        withBadges.setShowContributorBadges(false);
        assertEquals(1, SettingsCatalog.countChangedSettings(defaults, withBadges),
                "the old hand-written diff missed showContributorBadges; the derived one must not");
    }

    @Test
    void dungeonEntriesReadAndWriteTheDungeonConfig() {
        DungeonConfig dungeon = new DungeonConfig();
        DungeonConfig dungeonDefaults = new DungeonConfig();
        WaypointerConfig config = new WaypointerConfig();
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.store() != Setting.Store.DUNGEON) continue;
            Object defaultValue = setting.get(config, dungeonDefaults);
            Object probe = probeValue(setting, new WaypointerConfig(), dungeonDefaults);
            setting.set(config, dungeon, probe);
            assertNotEquals(defaultValue, setting.get(config, dungeon),
                    setting.id() + " probe did not change the dungeon value");
            assertTrue(setting.isModified(config, dungeon, config, dungeonDefaults));
        }
    }

    @Test
    void combinedDiffIncludesMainAndDungeonSettings() {
        WaypointerConfig liveMain = new WaypointerConfig();
        DungeonConfig liveDungeon = new DungeonConfig();
        WaypointerConfig nextMain = new WaypointerConfig();
        DungeonConfig nextDungeon = new DungeonConfig();
        nextMain.setShowTracer(false);
        nextDungeon.setEnabled(false);

        assertEquals(2, SettingsCatalog.countChangedSettings(
                liveMain, liveDungeon, nextMain, nextDungeon));
    }

    @Test
    void actionEntriesNeverDiffOrReportModified() {
        WaypointerConfig a = new WaypointerConfig();
        WaypointerConfig b = new WaypointerConfig();
        assertEquals(0, SettingsCatalog.countChangedSettings(a, b));
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.kind() != Setting.Kind.ACTION) continue;
            assertEquals(Setting.Store.NONE, setting.store());
            assertFalse(setting.isModified(a, null, b, null));
        }
    }

    @Test
    void waypointPainterIsAnActionInTheWaypointsCategory() {
        Setting paint = SettingsCatalog.byId(SettingsCatalog.ACTION_WAYPOINT_PAINT);

        assertNotNull(paint);
        assertEquals("Paint", paint.label());
        assertEquals(Setting.Kind.ACTION, paint.kind());
        assertEquals("waypoints", SettingsCatalog.categories().stream()
                .filter(category -> category.groups().stream()
                        .flatMap(group -> group.settings().stream())
                        .anyMatch(setting -> setting.id().equals(SettingsCatalog.ACTION_WAYPOINT_PAINT)))
                .findFirst().orElseThrow().id());
    }

    @Test
    void routeTimesIsOffByDefaultUnderRoutesAndProgression() {
        Setting routeTimes = SettingsCatalog.byId("routeTimesEnabled");

        assertNotNull(routeTimes);
        assertEquals("Route times", routeTimes.label());
        assertEquals(false, routeTimes.get(new WaypointerConfig(), null));
        assertEquals("routes", SettingsCatalog.categories().stream()
                .filter(category -> category.groups().stream()
                        .flatMap(group -> group.settings().stream())
                        .anyMatch(setting -> setting.id().equals("routeTimesEnabled")))
                .findFirst().orElseThrow().id());
    }

    @Test
    void routeIndicesAreOptInUnderRoutesAndSurviveCodecRoundTrip() {
        Setting routeIndices = SettingsCatalog.byId("showRouteIndicesInGui");

        assertNotNull(routeIndices);
        assertEquals("Show route indices", routeIndices.label());
        assertEquals(false, routeIndices.get(new WaypointerConfig(), null));
        assertEquals("routes", SettingsCatalog.categories().stream()
                .filter(category -> category.groups().stream()
                        .flatMap(group -> group.settings().stream())
                        .anyMatch(setting -> setting.id().equals("showRouteIndicesInGui")))
                .findFirst().orElseThrow().id());

        WaypointerConfig enabled = new WaypointerConfig();
        routeIndices.set(enabled, null, true);
        WaypointerConfig decoded = WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(enabled));
        assertEquals(true, routeIndices.get(decoded, null));
    }

    @Test
    void reachedSubwaypointHoldIsOnByDefaultUnderRoutesAndSurvivesCodecRoundTrip() {
        String id = "keepSubwaypointsVisibleUntilNextWaypoint";
        Setting setting = SettingsCatalog.byId(id);

        assertNotNull(setting);
        assertEquals("Keep subwaypoints until next waypoint", setting.label());
        assertEquals(true, setting.get(new WaypointerConfig(), null));
        assertEquals("routes", SettingsCatalog.categories().stream()
                .filter(category -> category.groups().stream()
                        .flatMap(group -> group.settings().stream())
                        .anyMatch(candidate -> candidate.id().equals(id)))
                .findFirst().orElseThrow().id());

        WaypointerConfig disabled = new WaypointerConfig();
        setting.set(disabled, null, false);
        WaypointerConfig decoded = WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(disabled));
        assertEquals(false, setting.get(decoded, null));
    }

    @Test
    void dungeonEntryPathIsOffByDefaultInTheCatalog() {
        Setting entryPath = SettingsCatalog.byId("showDungeonEntryPathToFirstWaypoint");

        assertNotNull(entryPath);
        assertEquals(false, entryPath.get(new WaypointerConfig(), new DungeonConfig()));
    }

    @Test
    void dungeonRoutePresentationDefaultsLiveInTheDungeonsCategory() {
        WaypointerConfig main = new WaypointerConfig();
        DungeonConfig dungeon = new DungeonConfig();
        for (String id : List.of(
                "showDungeonRouteLines",
                "showDungeonTracers")) {
            Setting setting = SettingsCatalog.byId(id);
            assertNotNull(setting, id);
            assertEquals(Setting.Store.DUNGEON, setting.store(), id);
            assertEquals("dungeons", categoryContaining(id), id);
        }
        assertEquals(true, SettingsCatalog.byId("showDungeonRouteLines").get(main, dungeon));
        assertEquals(false, SettingsCatalog.byId("showDungeonTracers").get(main, dungeon));
    }

    @Test
    void automaticDungeonColorsAreEditableInTheDungeonsCategory() {
        WaypointerConfig main = new WaypointerConfig();
        DungeonConfig dungeon = new DungeonConfig();
        for (String id : List.of(
                "automaticSecretColor",
                "automaticEtherwarpColor",
                "automaticBreakBlocksColor",
                "automaticInteractColor",
                "automaticSuperboomColor",
                "automaticItemColor",
                "automaticBatColor",
                "automaticDungeonbreakerColor",
                "automaticPearlColor")) {
            Setting setting = SettingsCatalog.byId(id);
            assertNotNull(setting, id);
            assertEquals(Setting.Store.DUNGEON, setting.store(), id);
            assertEquals(Setting.Kind.COLOR, setting.kind(), id);
            assertEquals("dungeons", categoryContaining(id), id);
            setting.set(main, dungeon, 0xAA123456);
            assertEquals(0x123456, setting.get(main, dungeon), id);
        }
    }

    @Test
    void theExportRoutePreviewIsOffByDefaultUnderSharing() {
        Setting preview = SettingsCatalog.byId("showExportRoutePreview");

        assertNotNull(preview);
        assertEquals("3D route preview", preview.label());
        assertEquals(false, preview.get(new WaypointerConfig(), null));
        assertEquals("sharing", categoryContaining("showExportRoutePreview"));

        WaypointerConfig enabled = new WaypointerConfig();
        preview.set(enabled, null, true);
        WaypointerConfig decoded = WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(enabled));
        assertEquals(true, preview.get(decoded, null));
    }

    @Test
    void formatValueRendersEveryKindReadably() {
        assertEquals("On", Setting.formatValue(Setting.Kind.BOOL, Boolean.TRUE, List.of()));
        assertEquals("Off", Setting.formatValue(Setting.Kind.BOOL, Boolean.FALSE, List.of()));
        assertEquals("3", Setting.formatValue(Setting.Kind.NUMBER, 3.0, List.of()));
        assertEquals("0.95", Setting.formatValue(Setting.Kind.NUMBER, 0.95, List.of()));
        assertEquals("#00FF00", Setting.formatValue(Setting.Kind.COLOR, 0x00FF00, List.of()));
        assertEquals("Gradient", Setting.formatValue(Setting.Kind.ENUM, "AUTO",
                List.of(new Setting.EnumOption("One color", "STATIC"),
                        new Setting.EnumOption("Gradient", "AUTO"))));
    }

    private static Object probeValue(Setting setting, WaypointerConfig defaults) {
        return probeValue(setting, defaults, new DungeonConfig());
    }

    /** A value guaranteed to differ from the default even after setter clamping. */
    private static Object probeValue(Setting setting, WaypointerConfig defaults, DungeonConfig dungeonDefaults) {
        Object defaultValue = setting.get(defaults, dungeonDefaults);
        if (setting.kind() == Setting.Kind.ENUM) {
            for (Setting.EnumOption option : setting.enumOptions()) {
                if (!Objects.equals(option.value(), defaultValue)) return option.value();
            }
            throw new AssertionError(setting.id() + " has no non-default option");
        }
        if (setting.kind() == Setting.Kind.COLOR) {
            return ((Number) defaultValue).intValue() ^ 0x0F0F0F;
        }
        if (defaultValue instanceof Boolean b) return !b;
        if (defaultValue instanceof Double d) return d + 2.0;
        if (defaultValue instanceof List<?>) return List.of("ProbePlayer");
        throw new AssertionError(setting.id() + " has unprobeable default " + defaultValue);
    }

    private static Set<String> persistedFieldNames(Class<?> type, Set<String> exempt) {
        Set<String> out = new TreeSet<>();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) continue;
            if (exempt.contains(field.getName())) continue;
            out.add(field.getName());
        }
        return out;
    }

    private static Set<String> idsByStore(Setting.Store store) {
        Set<String> out = new TreeSet<>();
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.store() == store) out.add(setting.id());
        }
        return out;
    }

    private static String categoryContaining(String id) {
        return SettingsCatalog.categories().stream()
                .filter(category -> category.groups().stream()
                        .flatMap(group -> group.settings().stream())
                        .anyMatch(setting -> setting.id().equals(id)))
                .findFirst()
                .orElseThrow()
                .id();
    }
}
