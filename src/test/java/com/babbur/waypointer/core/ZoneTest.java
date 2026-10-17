package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZoneTest {

    @Test
    void knownZonesExposeOfflineRouteTargetsWithoutLegacyMineshaftBucket() {
        assertTrue(Zone.knownZones().contains(Zone.fromId("hub")));
        assertTrue(Zone.knownZones().contains(Zone.fromId("dungeon_f7")));
        assertTrue(Zone.knownZones().contains(Zone.fromId("torrhus_canyon")));
        assertTrue(Zone.knownZones().contains(Zone.fromId("safari")));
        assertTrue(Zone.knownZones().contains(Zone.fromId("mineshaft_topaz_1")));
        assertTrue(Zone.knownZones().contains(Zone.fromId("mineshaft_littlefoots_den")));
        assertEquals(1, Zone.knownZones().stream()
                .filter(zone -> "mineshaft_crystal".equals(zone.id()))
                .count());
        assertFalse(Zone.knownZones().stream().anyMatch(zone -> "mineshaft".equals(zone.id())));
        assertFalse(Zone.knownZones().stream().anyMatch(zone ->
                "great_glacite_lake".equals(zone.id())
                        || "glacite_tunnels".equals(zone.id())
                        || "dwarven_base_camp".equals(zone.id())));
    }

    @Test
    void crystalHollowsStructureZonesAreRouteHomesThatNeverResolveFromTheServer() {
        Zone temple = Zone.fromId("crystal_hollows_structure_jungle_temple");
        assertEquals("Jungle Temple", temple.displayName());
        assertTrue(Zone.knownZones().contains(temple));
        assertTrue(Zone.knownZones().contains(Zone.fromId("crystal_hollows_structure_odawa")));
        assertFalse(Zone.knownZones().stream().anyMatch(zone ->
                zone.id().equals("crystal_hollows_structure_crystal_nucleus")
                        || zone.id().equals("crystal_hollows_structure_wishing_target")));
        assertEquals(Zone.fromId("crystal_hollows"), Zone.resolve("SKYBLOCK", "Crystal Hollows", "crystal_hollows"));
        assertFalse(Zone.resolveFromDisplayName("Jungle Temple (Crystal Hollows)").id()
                .startsWith("crystal_hollows_structure_"));
    }

    @Test
    void privateWorldHasStableUserFacingZoneIdentity() {
        assertEquals(Zone.PRIVATE_WORLD, Zone.fromId("private_world"));
        assertEquals("Private World", Zone.PRIVATE_WORLD.displayName());
        assertFalse(Zone.knownZones().contains(Zone.PRIVATE_WORLD));
    }

    @Test
    void resolve_returnsNullForNonSkyblock() {
        assertNull(Zone.resolve("BEDWARS", "anything", null));
        assertNull(Zone.resolve(null, "anything", null));
    }

    @Test
    void resolve_knownHubMap() {
        Zone z = Zone.resolve("SKYBLOCK", "Hub", null);
        assertNotNull(z);
        assertEquals("hub", z.id());
        assertEquals("Hub", z.displayName());
    }

    @Test
    void resolve_dungeonFloorMergesMapAndMode() {
        Zone f7 = Zone.resolve("SKYBLOCK", "dungeon", "F7");
        assertEquals("dungeon_f7", f7.id());
        assertEquals("Catacombs F7", f7.displayName());

        Zone m7 = Zone.resolve("SKYBLOCK", "dungeon", "M7");
        assertEquals("dungeon_m7", m7.id());
    }

    @Test
    void resolve_unknownMapFallsThroughToSanitizedId() {
        Zone weird = Zone.resolve("SKYBLOCK", "My Custom Island!", null);
        assertEquals("my_custom_island", weird.id());
        assertEquals("My Custom Island!", weird.displayName());
    }

    @Test
    void fromId_collapsesDuplicatedFallbackDisplayNames() {
        Zone lotus = Zone.fromId("lotus_atoll_lotus_atoll");
        assertEquals("lotus_atoll_lotus_atoll", lotus.id());
        assertEquals("Lotus Atoll", lotus.displayName());

        Zone generic = Zone.fromId("future_place_future_place");
        assertEquals("future_place_future_place", generic.id());
        assertEquals("Future Place", generic.displayName());
    }

    @Test
    void resolveFromDisplayName_matchesKnownZone() {
        Zone z = Zone.resolveFromDisplayName("Crystal Hollows");
        assertEquals("crystal_hollows", z.id());
    }

    @Test
    void resolveFromDisplayName_handlesUnknown() {
        Zone z = Zone.resolveFromDisplayName("Some New Area");
        assertEquals("some_new_area", z.id());
    }

    @Test
    void resolveFromDisplayName_nullOrBlank() {
        assertNull(Zone.resolveFromDisplayName(null));
        assertNull(Zone.resolveFromDisplayName("   "));
    }

    @Test
    void galatea_scoreboardSuffixedNameStillResolvesToGalateaId() {
        // Sidebar names and imported island IDs must share a storage key.
        Zone z = Zone.resolveFromDisplayName("Galatea Foraging 2");
        assertNotNull(z);
        assertEquals("galatea", z.id());
        assertEquals("Galatea", z.displayName());
    }

    @Test
    void galatea_hypixelApiMapPrefixResolvesToGalateaId() {
        Zone z = Zone.resolve("SKYBLOCK", "Galatea", null);
        assertNotNull(z);
        assertEquals("galatea", z.id());
    }

    @Test
    void galatea_fromIdResolvesDisplayName() {
        Zone z = Zone.fromId("galatea");
        assertEquals("galatea", z.id());
        assertEquals("Galatea", z.displayName());
    }

    // Hypixel may put island IDs or display names in either map or mode.
    @Test
    void resolve_matchesOnEitherMapOrMode() {
        Zone a = Zone.resolve("SKYBLOCK", null, "foraging_1");
        assertNotNull(a);
        assertEquals("the_park", a.id());

        Zone b = Zone.resolve("SKYBLOCK", "The Park", null);
        assertNotNull(b);
        assertEquals("the_park", b.id());

        Zone c = Zone.resolve("SKYBLOCK", "foraging_1", null);
        assertNotNull(c);
        assertEquals("the_park", c.id());
    }

    @Test
    void resolve_coversNewerIslands() {
        assertEquals("crimson_isle",    Zone.resolve("SKYBLOCK", "Crimson Isle",    null).id());
        assertEquals("crimson_isle",    Zone.resolve("SKYBLOCK", null, "crimson_isle").id());
        assertEquals("backwater_bayou", Zone.resolve("SKYBLOCK", "Backwater Bayou", null).id());
        assertEquals("backwater_bayou", Zone.resolve("SKYBLOCK", null, "fishing_1").id());
        assertEquals("dark_auction",    Zone.resolve("SKYBLOCK", "Dark Auction",    null).id());
        assertEquals("winter",          Zone.resolve("SKYBLOCK", null, "winter").id());
        assertEquals("winter",          Zone.resolve("SKYBLOCK", "Jerry's Workshop", null).id());
        assertEquals("mineshaft_unknown", Zone.resolve("SKYBLOCK", null, "mineshaft").id());
        assertEquals("lotus_atoll",     Zone.resolve("SKYBLOCK", null, "lotus_atoll").id());
        assertEquals("lotus_atoll",     Zone.resolve("SKYBLOCK", "Lotus Atoll", null).id());
        assertEquals("torrhus_canyon",  Zone.resolve("SKYBLOCK", null, "foraging_3").id());
        assertEquals("torrhus_canyon",  Zone.resolve("SKYBLOCK", "Torrhus Canyon", null).id());
        assertEquals("safari",          Zone.resolve("SKYBLOCK", null, "safari").id());
        assertEquals("safari",          Zone.resolve("SKYBLOCK", "Safari Zone", null).id());
    }

    @Test
    void kuudra_acceptsShortAndLongDisplayName() {
        // Older exports and the sidebar abbreviate "Kuudra's Hollow" to "Kuudra".
        assertEquals("kuudra", Zone.resolveFromDisplayName("Kuudra").id());
        assertEquals("kuudra", Zone.resolveFromDisplayName("Kuudra's Hollow").id());
        assertEquals("Kuudra's Hollow", Zone.fromId("kuudra").displayName());
    }

    @Test
    void mineshaft_friendlyNamesResolveToUnknownVariant() {
        // Without a layout code, mineshaft labels resolve only to Unknown Mineshaft.
        assertEquals("mineshaft_unknown", Zone.resolveFromDisplayName("Mineshaft").id());
        assertEquals("mineshaft_unknown", Zone.resolveFromDisplayName("Glacite Mineshafts").id());
        assertEquals("mineshaft_unknown", Zone.resolveFromDisplayName("Unknown Mineshaft").id());
    }

    @Test
    void privateIsland_matchesDynamicId() {
        // Hypixel identifies Private Island with mode "dynamic".
        assertEquals("private_island", Zone.resolve("SKYBLOCK", null, "dynamic").id());
        assertEquals("private_island", Zone.resolve("SKYBLOCK", "Private Island", null).id());
        assertEquals("private_island", Zone.resolveFromDisplayName("Your Island").id());
    }

    @Test
    void retiredDwarvenSurfaceZonesCanonicalizeToPacketZone() {
        for (String legacyId : new String[]{
                "great_glacite_lake", "glacite_tunnels", "dwarven_base_camp"
        }) {
            assertEquals("dwarven_mines", Zone.canonicalId(legacyId));
            assertEquals(new Zone("dwarven_mines", "Dwarven Mines"), Zone.fromId(legacyId));
        }

        for (String legacyName : new String[]{
                "Great Glacite Lake", "Glacite Tunnels", "Dwarven Base Camp"
        }) {
            assertEquals(new Zone("dwarven_mines", "Dwarven Mines"),
                    Zone.resolveFromDisplayName(legacyName));
            assertEquals("dwarven_mines", Zone.resolve("SKYBLOCK", legacyName, null).id());
        }
    }

    @Test
    void legacyMineshaftBucketCanonicalizesToUnknownLayout() {
        assertEquals("mineshaft_unknown", Zone.canonicalId("mineshaft"));
        assertEquals("mineshaft_unknown", Zone.fromId("mineshaft").id());
        assertEquals("Unknown Mineshaft", Zone.fromId("mineshaft").displayName());
    }

    @Test
    void importedUnknownMineshaftOnlyRetargetsInsideASpecificLayout() {
        assertTrue(Zone.shouldRetargetImportedZone("mineshaft_unknown", "mineshaft_ruby_1"));
        assertTrue(Zone.shouldRetargetImportedZone("mineshaft", "mineshaft_crystal"));
        assertFalse(Zone.shouldRetargetImportedZone("mineshaft_unknown", "mineshaft_unknown"));
        assertFalse(Zone.shouldRetargetImportedZone("mineshaft_unknown", "dwarven_mines"));
        assertFalse(Zone.shouldRetargetImportedZone("mineshaft_unknown", "hub"));
        assertFalse(Zone.shouldRetargetImportedZone("mineshaft_unknown", "mineshaft_fake"));
        assertTrue(Zone.shouldRetargetImportedZone("unknown", "hub"));
        assertFalse(Zone.shouldRetargetImportedZone("unknown", "unknown"));
    }

    @Test
    void dwarvenSidebarRefinementOnlySplitsMineshaftLayouts() {
        assertEquals("dwarven_mines", Zone.tryResolveDwarvenSubAreaFromSidebarBlob(
                "Bits: 0\n⏣ Dwarven Mines\nNear Great Glacite Lake").id());
        assertEquals("dwarven_mines",
                Zone.tryResolveDwarvenSubAreaFromSidebarBlob("⏣ Glacite Tunnels").id());
        assertEquals("dwarven_mines",
                Zone.tryResolveDwarvenSubAreaFromSidebarBlob("Dwarven Base Camp").id());
        assertEquals("mineshaft_unknown",
                Zone.tryResolveDwarvenSubAreaFromSidebarBlob("⏣ Glacite Mineshafts").id());
        assertEquals("mineshaft_unknown",
                Zone.tryResolveDwarvenSubAreaFromSidebarBlob(
                        "Dwarven Base Camp\n⏣ Glacite Mineshafts").id());
        assertNull(Zone.tryResolveDwarvenSubAreaFromSidebarBlob("⏣ Crystal Hollows"));
    }

    @Test
    void refineIfDwarvenMinesContext_onlyWhenPacketSaysDwarvenBucket() {
        Zone hub = Zone.resolve("SKYBLOCK", "hub", null);
        assertSame(hub, Zone.refineIfDwarvenMinesContext(hub, "Glacite Tunnels"));

        Zone dm = Zone.resolve("SKYBLOCK", "mining_3", null);
        Zone refined = Zone.refineIfDwarvenMinesContext(dm, "⏣ Glacite Tunnels");
        assertSame(dm, refined);
    }

        @Test
    void mineshaftType_resolveFromSidebarBlob() {
        // The sidebar carries a layout code such as AQUA_C, not its display name.
        assertEquals("mineshaft_topaz_1",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("⏣ Glacite Mineshafts\n04/18/26 m1 TOPA_1").id());
        assertEquals("mineshaft_crystal",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("04/18/26 m197CD AQUA_C").id());
        assertEquals("mineshaft_crystal",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("m14 JASP_C").id());
        assertEquals("mineshaft_jasper",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("m14 JASP_1").id());
        assertEquals("mineshaft_littlefoots_den",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("m1 LITT_L").id());
        assertEquals("mineshaft_vanguard",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("m1 FAIR_1").id());
        assertEquals("mineshaft_tungsten",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("m1 TUNG_1").id());
        // Without a layout code, the caller falls back to mineshaft_unknown.
        assertNull(Zone.tryResolveMineshaftTypeFromSidebarBlob("⏣ Glacite Mineshafts"));
        assertNull(Zone.tryResolveMineshaftTypeFromSidebarBlob(null));
    }

        @Test
    void mineshaftType_crystalCodeSharesZoneButPlainCodeStaysDistinct() {
        // Crystal layouts share one route bucket across gem types.
        assertEquals("mineshaft_jasper",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("m1 JASP_1").id());
        assertEquals("mineshaft_crystal",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("m1 JASP_C").id());
    }

        @Test
    void mineshaftType_allCrystalCodesShareZone() {
        String[] crystalCodes = {"RUBY_C", "ONYX_C", "AQUA_C", "CITR_C", "PERI_C", "JASP_C", "OPAL_C"};
        for (String code : crystalCodes) {
            assertEquals("mineshaft_crystal",
                    Zone.tryResolveMineshaftTypeFromSidebarBlob("m1 " + code).id(),
                    code + " should share the crystal mineshaft zone");
        }
    }

    @Test
    void mineshaftType_rejectsSubstringInsideLargerWord() {
        // Require token boundaries to avoid matching usernames.
        assertNull(Zone.tryResolveMineshaftTypeFromSidebarBlob("⏣ Glacite Mineshafts\nxTOPA_1x"));
        assertNull(Zone.tryResolveMineshaftTypeFromSidebarBlob("prefixJASP_C"));
        // Live layout codes are uppercase; lowercase username fragments must not match.
        assertNull(Zone.tryResolveMineshaftTypeFromSidebarBlob("m1 aqua_c"));
    }

    @Test
    void mineshaft_packetWithoutVariantUpgradesToUnknown() {
        // A packet can precede the sidebar layout code; keep variant routes inactive until it arrives.
        Zone raw = Zone.resolve("SKYBLOCK", null, "mineshaft");
        assertEquals("mineshaft_unknown", raw.id());
        Zone refined = Zone.refineIfDwarvenMinesContext(raw, "");
        assertEquals("mineshaft_unknown", refined.id());
    }

    @Test
    void mineshaft_dwarvenMinesWithOnlyGlaciteMineshaftsLineUpgradesToUnknown() {
        Zone dm = Zone.resolve("SKYBLOCK", "mining_3", null);
        Zone refined = Zone.refineIfDwarvenMinesContext(dm, "⏣ Glacite Mineshafts");
        assertEquals("mineshaft_unknown", refined.id());
    }

    @Test
    void mineshaft_unknownZoneRendersFriendlyDisplayName() {
        assertEquals("Unknown Mineshaft", Zone.fromId("mineshaft_unknown").displayName());
        // Legacy IDs remain readable, but runtime refinement skips the generic bucket.
        assertEquals("Unknown Mineshaft", Zone.fromId("mineshaft").displayName());
    }

    @Test
    void mineshaftType_refineUpgradesMineshaftPacket() {
        // The packet gives only "mineshaft"; the sidebar layout code selects the route zone.
        Zone raw = Zone.resolve("SKYBLOCK", null, "mineshaft");
        Zone refined = Zone.refineIfDwarvenMinesContext(raw, "⏣ Glacite Mineshafts\n04/18/26 m1 TOPA_2");
        assertEquals("mineshaft_topaz_2", refined.id());
    }

        @Test
    void mineshaftType_refineVariantBeatsGenericSubArea() {
        // A specific layout code takes precedence over the generic sub-area name.
        Zone raw = Zone.resolve("SKYBLOCK", null, "mineshaft");
        Zone refined = Zone.refineIfDwarvenMinesContext(raw,
                "⏣ Glacite Mineshafts\n04/18/26 m197CD AQUA_C");
        assertEquals("mineshaft_crystal", refined.id());
    }

        @Test
    void mineshaftType_fromIdRendersFriendlyDisplayName() {
        assertEquals("Mineshaft: Topaz 1", Zone.fromId("mineshaft_topaz_1").displayName());
        assertEquals("Mineshaft: Crystal", Zone.fromId("mineshaft_jasper_crystal").displayName());
        assertEquals("Mineshaft: Crystal", Zone.fromId("mineshaft_crystal").displayName());
        assertEquals("Mineshaft: Littlefoot's Den", Zone.fromId("mineshaft_littlefoots_den").displayName());
    }

        @Test
    void mineshaftType_resolveFromDisplayNameAcceptsBareVariant() {
        // Imported display names must resolve to the same key as live layout codes.
        assertEquals("mineshaft_topaz_1", Zone.resolveFromDisplayName("Topaz 1").id());
        assertEquals("mineshaft_crystal", Zone.resolveFromDisplayName("Jasper Crystal").id());
    }

    @Test
    void dungeonFloor_acceptsSwappedFieldOrdering() {
        // Hypixel packet versions have swapped map and mode; accept both orders.
        assertEquals("dungeon_f7", Zone.resolve("SKYBLOCK", "dungeon", "F7").id());
        assertEquals("dungeon_f7", Zone.resolve("SKYBLOCK", "F7", "dungeon").id());
        assertEquals("dungeon_m4", Zone.resolve("SKYBLOCK", "M4", "dungeon").id());
    }

    @Test
    void dungeonGenericPayloadDoesNotDisplayDungeonDungeon() {
        Zone zone = Zone.resolve("SKYBLOCK", "Dungeon", "Dungeon");

        assertEquals("dungeon", zone.id());
        assertEquals("Catacombs", zone.displayName());
    }

    @Test
    void masterModeSharesRoutesWithItsFloorOnly() {
        assertTrue(Zone.sharesRoutes("dungeon_f7", "dungeon_m7"));
        assertTrue(Zone.sharesRoutes("dungeon_m3", "dungeon_f3"));
        assertTrue(Zone.sharesRoutes("dungeon_m3", "dungeon_m3"));
        assertFalse(Zone.sharesRoutes("dungeon_f6", "dungeon_m7"));
        assertFalse(Zone.sharesRoutes("dungeon", "dungeon_m7"));
        assertFalse(Zone.sharesRoutes("hub", "dungeon_f1"));
        assertEquals("arrow-trap", Zone.routeZoneKey("arrow-trap"));
    }
}
