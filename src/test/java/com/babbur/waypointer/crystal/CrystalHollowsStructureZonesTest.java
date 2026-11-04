package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointStyle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrystalHollowsStructureZonesTest {

    @Test
    void zoneIdsRoundTripForEveryMovableStructure() {
        for (CrystalHollowsStructure structure : CrystalHollowsStructure.values()) {
            String zoneId = CrystalHollowsStructureZones.zoneId(structure);
            if (CrystalHollowsStructureZones.supports(structure)) {
                assertEquals(structure, CrystalHollowsStructureZones.structureFor(zoneId));
                assertTrue(CrystalHollowsStructureZones.isStructureZone(zoneId));
            } else {
                assertNull(CrystalHollowsStructureZones.structureFor(zoneId));
            }
        }
        assertFalse(CrystalHollowsStructureZones.supports(CrystalHollowsStructure.CRYSTAL_NUCLEUS));
        assertFalse(CrystalHollowsStructureZones.supports(CrystalHollowsStructure.WISHING_TARGET));
        assertFalse(CrystalHollowsStructureZones.supports(CrystalHollowsStructure.FAIRY_GROTTO),
                "a structure that can appear twice per lobby has no single pivot");
        assertTrue(CrystalHollowsStructureZones.supports(CrystalHollowsStructure.JUNGLE_TEMPLE));
        assertFalse(CrystalHollowsStructureZones.isStructureZone("crystal_hollows"));
        assertFalse(CrystalHollowsStructureZones.isStructureZone(null));
        assertEquals("Mines of Divan",
                CrystalHollowsStructureZones.displayName(CrystalHollowsStructure.MINES_OF_DIVAN));
        assertEquals(CrystalHollowsStructureZones.definitions().size(),
                CrystalHollowsStructureZones.definitions().stream().map(d -> d.zoneId()).distinct().count());
    }

    @Test
    void onlyExactSightingsAnchorARouteAndTheNearestInstanceWins() {
        var rough = new StructureSighting(CrystalHollowsStructure.FAIRY_GROTTO, 300, 100, 300,
                SightingConfidence.NPC_CHAT, "chat", 1L);
        var shared = new StructureSighting(CrystalHollowsStructure.FAIRY_GROTTO, 310, 100, 310,
                SightingConfidence.SHARED_REMOTE, "relay", 1L);
        var far = new StructureSighting(CrystalHollowsStructure.FAIRY_GROTTO, 700, 100, 700,
                SightingConfidence.ENTITY, "entity", 1L);
        var near = new StructureSighting(CrystalHollowsStructure.FAIRY_GROTTO, 400, 100, 400,
                SightingConfidence.MANUAL, "manual", 1L);
        var other = new StructureSighting(CrystalHollowsStructure.ODAWA, 401, 100, 401,
                SightingConfidence.MANUAL, "manual", 1L);
        List<StructureSighting> sightings = List.of(rough, shared, far, near, other);
        assertNull(CrystalHollowsStructureZones.pivot(List.of(rough, shared),
                CrystalHollowsStructure.FAIRY_GROTTO, null));
        assertEquals(new CrystalHollowsPosition(700, 100, 700), CrystalHollowsStructureZones.pivot(
                sightings, CrystalHollowsStructure.FAIRY_GROTTO, null));
        assertEquals(new CrystalHollowsPosition(400, 100, 400), CrystalHollowsStructureZones.pivot(
                sightings, CrystalHollowsStructure.FAIRY_GROTTO, new CrystalHollowsPosition(450, 100, 450)));
        assertEquals(new CrystalHollowsPosition(700, 100, 700), CrystalHollowsStructureZones.pivot(
                sightings, CrystalHollowsStructure.FAIRY_GROTTO, new CrystalHollowsPosition(650, 100, 650)));
        assertNull(CrystalHollowsStructureZones.pivot(sightings, CrystalHollowsStructure.JUNGLE_TEMPLE, null));
        assertNull(CrystalHollowsStructureZones.pivot(null, CrystalHollowsStructure.JUNGLE_TEMPLE, null));
    }

    @Test
    void localAndWorldConversionsRoundTripWithPreciseOffsetsAndMetadata() {
        var pivot = new CrystalHollowsPosition(300, 100, -400);
        var style = new WaypointStyle(Map.of("tracerColor", "123"));
        Waypoint actual = new Waypoint(302, 103, -396, "Door", 0x123456, Waypoint.FLAG_THROUGH_WALL, 2.5)
                .withPreciseSixteenths(302 * 16 + 4, 103 * 16, -396 * 16 + 12)
                .withStyleOverride(style);
        Waypoint local = CrystalHollowsStructureZones.toStructureLocal(actual, pivot);
        assertEquals(2 * 16 + 4, local.preciseX());
        assertEquals(3 * 16, local.preciseY());
        assertEquals(4 * 16 + 12, local.preciseZ());
        assertEquals(2, local.x());
        assertEquals(3, local.y());
        assertEquals(4, local.z());
        assertEquals("Door", local.name());
        assertEquals(0x123456, local.color());
        assertEquals(Waypoint.FLAG_THROUGH_WALL, local.flags());
        assertEquals(2.5, local.customRadius());
        assertEquals(style, local.styleOverride());
        Waypoint world = CrystalHollowsStructureZones.toWorld(local, pivot);
        assertEquals(actual.preciseX(), world.preciseX());
        assertEquals(actual.preciseY(), world.preciseY());
        assertEquals(actual.preciseZ(), world.preciseZ());
        assertEquals(302, world.x());
        assertEquals(-396, world.z());
        int[] precise = CrystalHollowsStructureZones.toStructureLocalPrecise(
                actual.preciseX(), actual.preciseY(), actual.preciseZ(), pivot);
        assertEquals(local.preciseX(), precise[0]);
        assertEquals(local.preciseY(), precise[1]);
        assertEquals(local.preciseZ(), precise[2]);
    }
}
