package dev.ethan.waypointer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZoneTest {

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
        // Hypixel's sidebar reads "Galatea Foraging 2" while players are on the
        // island, but Skyblocker imports use the bare "galatea" island id. Both
        // must converge on the same zone id so imported routes render live.
        Zone z = Zone.resolveFromDisplayName("Galatea Foraging 2");
        assertNotNull(z);
        assertEquals("galatea", z.id());
        assertEquals("Galatea", z.displayName());
    }

    @Test
    void galatea_hypixelApiMapPrefixResolvesToGalateaId() {
        // Same convergence guarantee via the mod-API path: if Hypixel reports the
        // map as "Galatea" (or something starting with it), we land on the
        // canonical id instead of sanitise-fallback.
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

    // Cross-field matching: Hypixel's mod-API doesn't strictly separate island
    // ids from friendly names between the `map` and `mode` fields. Resolution
    // must land on the right zone regardless of which slot the id arrives in.
    @Test
    void resolve_matchesOnEitherMapOrMode() {
        // Skyblocker-style id in mode.
        Zone a = Zone.resolve("SKYBLOCK", null, "foraging_1");
        assertNotNull(a);
        assertEquals("the_park", a.id());

        // Same zone keyed via friendly name in map.
        Zone b = Zone.resolve("SKYBLOCK", "The Park", null);
        assertNotNull(b);
        assertEquals("the_park", b.id());

        // And with the id in map (defensive -- some payloads swap).
        Zone c = Zone.resolve("SKYBLOCK", "foraging_1", null);
        assertNotNull(c);
        assertEquals("the_park", c.id());
    }

    @Test
    void resolve_coversNewerIslands() {
        // Every island added in the zone-coverage pass must land on the
        // documented canonical zone id when the Hypixel payload arrives in
        // either convention (id in mode, friendly name in map).
        assertEquals("crimson_isle",    Zone.resolve("SKYBLOCK", "Crimson Isle",    null).id());
        assertEquals("crimson_isle",    Zone.resolve("SKYBLOCK", null, "crimson_isle").id());
        assertEquals("backwater_bayou", Zone.resolve("SKYBLOCK", "Backwater Bayou", null).id());
        assertEquals("backwater_bayou", Zone.resolve("SKYBLOCK", null, "fishing_1").id());
        assertEquals("dark_auction",    Zone.resolve("SKYBLOCK", "Dark Auction",    null).id());
        assertEquals("winter",          Zone.resolve("SKYBLOCK", null, "winter").id());
        assertEquals("winter",          Zone.resolve("SKYBLOCK", "Jerry's Workshop", null).id());
        assertEquals("mineshaft",       Zone.resolve("SKYBLOCK", null, "mineshaft").id());
    }

    @Test
    void kuudra_acceptsShortAndLongDisplayName() {
        // Skyblocker's friendly name is "Kuudra's Hollow", but the sidebar /
        // older exports abbreviate to "Kuudra". Both must land on the same id.
        assertEquals("kuudra", Zone.resolveFromDisplayName("Kuudra").id());
        assertEquals("kuudra", Zone.resolveFromDisplayName("Kuudra's Hollow").id());
        assertEquals("Kuudra's Hollow", Zone.fromId("kuudra").displayName());
    }

    @Test
    void mineshaft_friendlyNamesResolveToUnknownVariant() {
        // Both scoreboard labels upgrade to the "Unknown Mineshaft" zone --
        // the generic mineshaft bucket spans ~33 distinct layouts, so a
        // waypoint group keyed to it is almost never what the user wants. The
        // unknown variant is an explicit signal "we know the island, not the
        // layout"; waypoint groups keyed to it trigger only in that exact
        // state.
        assertEquals("mineshaft_unknown", Zone.resolveFromDisplayName("Mineshaft").id());
        assertEquals("mineshaft_unknown", Zone.resolveFromDisplayName("Glacite Mineshafts").id());
        assertEquals("mineshaft_unknown", Zone.resolveFromDisplayName("Unknown Mineshaft").id());
    }

    @Test
    void privateIsland_matchesDynamicId() {
        // Hypixel's mod-api sends "dynamic" as the mode for Private Island --
        // one of the few cases where the id and friendly name share zero
        // spelling. Verify the id-side matcher works.
        assertEquals("private_island", Zone.resolve("SKYBLOCK", null, "dynamic").id());
        assertEquals("private_island", Zone.resolve("SKYBLOCK", "Private Island", null).id());
        assertEquals("private_island", Zone.resolveFromDisplayName("Your Island").id());
    }

    @Test
    void dwarven_glaciteSubAreas_resolveFromSidebarBlob() {
        // Mirrors Skyblocker Area.DwarvenMines -- Hypixel's packet often stays
        // mining_3 for all of these; the sidebar carries the real sub-area.
        assertEquals("great_glacite_lake",
                Zone.tryResolveDwarvenSubAreaFromSidebarBlob(
                        "Bits: 0\n⏣ Dwarven Mines\nNear Great Glacite Lake").id());
        assertEquals("glacite_tunnels",
                Zone.tryResolveDwarvenSubAreaFromSidebarBlob("⏣ Glacite Tunnels").id());
        // Generic "Glacite Mineshafts" (no variant identified) upgrades to the
        // unknown-variant zone. See refineIfDwarvenMinesContext / the
        // mineshaft_unknown Def comment for rationale.
        assertEquals("mineshaft_unknown",
                Zone.tryResolveDwarvenSubAreaFromSidebarBlob("⏣ Glacite Mineshafts").id());
        assertEquals("dwarven_base_camp",
                Zone.tryResolveDwarvenSubAreaFromSidebarBlob("Dwarven Base Camp").id());
        assertNull(Zone.tryResolveDwarvenSubAreaFromSidebarBlob("⏣ Crystal Hollows"));
    }

    @Test
    void dwarven_greatLake_beats_sharedGlacitePrefix() {
        assertEquals("great_glacite_lake",
                Zone.tryResolveDwarvenSubAreaFromSidebarBlob(
                        "x\nGreat Glacite Lake y\nGlacite Tunnels").id());
    }

    @Test
    void refineIfDwarvenMinesContext_onlyWhenPacketSaysDwarvenBucket() {
        Zone hub = Zone.resolve("SKYBLOCK", "hub", null);
        assertSame(hub, Zone.refineIfDwarvenMinesContext(hub, "Glacite Tunnels"));

        Zone dm = Zone.resolve("SKYBLOCK", "mining_3", null);
        Zone refined = Zone.refineIfDwarvenMinesContext(dm, "⏣ Glacite Tunnels");
        assertEquals("glacite_tunnels", refined.id());
    }

    @Test
    void mineshaftType_resolveFromSidebarBlob() {
        // Hypixel writes a compact code on the server-identifier line while
        // inside a mineshaft (e.g. "04/18/26 m197CD AQUA_C" in an Aquamarine
        // Crystal shaft). We match that code exactly, not the display name --
        // the display name never appears in the live scoreboard.
        assertEquals("mineshaft_topaz_1",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("⏣ Glacite Mineshafts\n04/18/26 m1 TOPA_1").id());
        assertEquals("mineshaft_aquamarine_crystal",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("04/18/26 m197CD AQUA_C").id());
        assertEquals("mineshaft_jasper_crystal",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("m14 JASP_C").id());
        assertEquals("mineshaft_jasper",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("m14 JASP_1").id());
        assertEquals("mineshaft_littlefoots_den",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("m1 LITT_L").id());
        assertEquals("mineshaft_vanguard",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("m1 FAIR_1").id());
        // "Glacite Mineshafts" alone (no variant code on any line) does not
        // resolve -- the caller falls back to mineshaft_unknown.
        assertNull(Zone.tryResolveMineshaftTypeFromSidebarBlob("⏣ Glacite Mineshafts"));
        assertNull(Zone.tryResolveMineshaftTypeFromSidebarBlob(null));
    }

    @Test
    void mineshaftType_crystalCodeDistinctFromPlainCode() {
        // Codes like JASP_1 and JASP_C are distinct tokens so no ordering
        // gymnastics are needed -- each resolves cleanly to its own variant.
        assertEquals("mineshaft_jasper",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("m1 JASP_1").id());
        assertEquals("mineshaft_jasper_crystal",
                Zone.tryResolveMineshaftTypeFromSidebarBlob("m1 JASP_C").id());
    }

    @Test
    void mineshaftType_rejectsSubstringInsideLargerWord() {
        // A username like "xTOPA_1x" should not activate the Topaz 1 zone.
        // containsAsToken requires boundary chars on both sides.
        assertNull(Zone.tryResolveMineshaftTypeFromSidebarBlob("⏣ Glacite Mineshafts\nxTOPA_1x"));
        assertNull(Zone.tryResolveMineshaftTypeFromSidebarBlob("prefixJASP_C"));
        // Sanity: the lowercase form doesn't match either. Codes are all-caps
        // in the live scoreboard; case-sensitive matching avoids false hits
        // on lowercase usernames containing the code as a substring.
        assertNull(Zone.tryResolveMineshaftTypeFromSidebarBlob("m1 aqua_c"));
    }

    @Test
    void mineshaft_packetWithoutVariantUpgradesToUnknown() {
        // Hypixel's packet sometimes reports "mineshaft" before the sidebar
        // has populated the variant line. We refine to the Unknown zone
        // rather than leaving the generic bucket active so variant-scoped
        // waypoint groups don't accidentally flash on.
        Zone raw = Zone.resolve("SKYBLOCK", null, "mineshaft");
        assertEquals("mineshaft", raw.id());
        Zone refined = Zone.refineIfDwarvenMinesContext(raw, "");
        assertEquals("mineshaft_unknown", refined.id());
    }

    @Test
    void mineshaft_dwarvenMinesWithOnlyGlaciteMineshaftsLineUpgradesToUnknown() {
        // Refinement via the sub-area path: packet stays on mining_3 but the
        // sidebar says "Glacite Mineshafts" with no variant line. Should land
        // on Unknown Mineshaft, not generic mineshaft.
        Zone dm = Zone.resolve("SKYBLOCK", "mining_3", null);
        Zone refined = Zone.refineIfDwarvenMinesContext(dm, "⏣ Glacite Mineshafts");
        assertEquals("mineshaft_unknown", refined.id());
    }

    @Test
    void mineshaft_unknownZoneRendersFriendlyDisplayName() {
        assertEquals("Unknown Mineshaft", Zone.fromId("mineshaft_unknown").displayName());
        // Legacy id still resolves for stored groups so imported data doesn't
        // crash; those groups simply never activate because the runtime path
        // upgrades past them.
        assertEquals("Glacite Mineshafts", Zone.fromId("mineshaft").displayName());
    }

    @Test
    void mineshaftType_refineUpgradesMineshaftPacket() {
        // Hypixel's packet flattens every variant to "mineshaft"; the sidebar
        // holds the variant code (e.g. "TOPA_2") on the server-identifier
        // line. refineIfDwarvenMinesContext must follow that signal so
        // variant-scoped waypoint groups activate.
        Zone raw = Zone.resolve("SKYBLOCK", null, "mineshaft");
        Zone refined = Zone.refineIfDwarvenMinesContext(raw, "⏣ Glacite Mineshafts\n04/18/26 m1 TOPA_2");
        assertEquals("mineshaft_topaz_2", refined.id());
    }

    @Test
    void mineshaftType_refineVariantBeatsGenericSubArea() {
        // If the blob mentions "Glacite Mineshafts" (generic sub-area) and a
        // specific variant code on another line, the code wins -- it's the
        // strictly-more-specific signal and matches what the user is doing in
        // this shaft.
        Zone raw = Zone.resolve("SKYBLOCK", null, "mineshaft");
        Zone refined = Zone.refineIfDwarvenMinesContext(raw,
                "⏣ Glacite Mineshafts\n04/18/26 m197CD AQUA_C");
        assertEquals("mineshaft_aquamarine_crystal", refined.id());
    }

    @Test
    void mineshaftType_fromIdRendersFriendlyDisplayName() {
        assertEquals("Mineshaft: Topaz 1", Zone.fromId("mineshaft_topaz_1").displayName());
        assertEquals("Mineshaft: Jasper Crystal", Zone.fromId("mineshaft_jasper_crystal").displayName());
        assertEquals("Mineshaft: Littlefoot's Den", Zone.fromId("mineshaft_littlefoots_den").displayName());
    }

    @Test
    void mineshaftType_resolveFromDisplayNameAcceptsBareVariant() {
        // If someone types the variant as a display name (e.g. from chat or an
        // imported route), it resolves to the same canonical id the refiner
        // produces, so the two paths agree on storage keys.
        assertEquals("mineshaft_topaz_1", Zone.resolveFromDisplayName("Topaz 1").id());
        assertEquals("mineshaft_jasper_crystal", Zone.resolveFromDisplayName("Jasper Crystal").id());
    }

    @Test
    void dungeonFloor_acceptsSwappedFieldOrdering() {
        // Defensive: accept both (map="dungeon", mode="F7") and the swapped
        // order. Field semantics have shifted between Hypixel packet versions
        // and we'd rather over-match than silently drop a dungeon run.
        assertEquals("dungeon_f7", Zone.resolve("SKYBLOCK", "dungeon", "F7").id());
        assertEquals("dungeon_f7", Zone.resolve("SKYBLOCK", "F7", "dungeon").id());
        assertEquals("dungeon_m4", Zone.resolve("SKYBLOCK", "M4", "dungeon").id());
    }
}
